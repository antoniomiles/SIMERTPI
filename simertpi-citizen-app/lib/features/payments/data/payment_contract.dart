import 'dart:math';

import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../parking/data/parking_contract.dart';

enum PaymentStatus {
  pending,
  processing,
  approved,
  declined,
  failed,
  refunded,
  cancelled,
}

class CitizenPayment {
  CitizenPayment(Map data)
    : id = requiredText(data, 'id'),
      sessionId = requiredText(data, 'parkingSessionId'),
      provider = requiredText(data, 'provider'),
      method = requiredText(data, 'paymentMethod'),
      amount = money(data['amount']),
      currency = requiredText(data, 'currency'),
      status = switch (data['status']) {
        'PENDING' => PaymentStatus.pending,
        'PROCESSING' => PaymentStatus.processing,
        'APPROVED' => PaymentStatus.approved,
        'DECLINED' => PaymentStatus.declined,
        'FAILED' => PaymentStatus.failed,
        'REFUNDED' => PaymentStatus.refunded,
        'CANCELLED' => PaymentStatus.cancelled,
        _ => throw invalidParking,
      },
      paidAt = data['paidAt'] == null
          ? null
          : DateTime.parse(requiredText(data, 'paidAt')) {
    if (!RegExp(r'^[A-Z]{3}$').hasMatch(currency) ||
        (status == PaymentStatus.approved && paidAt == null)) {
      throw invalidParking;
    }
    // These timestamps are part of PaymentResponse; malformed success stays uncertain.
    DateTime.parse(requiredText(data, 'createdAt'));
    DateTime.parse(requiredText(data, 'updatedAt'));
  }
  final String id, sessionId, provider, method, amount, currency;
  final PaymentStatus status;
  final DateTime? paidAt;
  bool get waiting =>
      status == PaymentStatus.pending || status == PaymentStatus.processing;
  bool get retryable =>
      status == PaymentStatus.declined || status == PaymentStatus.failed;
}

class PaymentIntent {
  PaymentIntent(
    this.owner,
    this.sessionId,
    this.method, {
    String? key,
    DateTime? createdAt,
    this.paymentId,
  }) : key =
           key ??
           List.generate(
             16,
             (_) =>
                 Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'),
           ).join(),
       createdAt = createdAt ?? DateTime.now().toUtc();
  final String owner, sessionId, method, key;
  final DateTime createdAt;
  final String? paymentId;
  // Server CREATE_PAYMENT keys expire after 24h. Stop replaying earlier, never mint
  // another key for an unresolved request. GET by payment ID remains safe.
  bool replayAllowed(DateTime now) =>
      !now.isBefore(createdAt) &&
      now.difference(createdAt) < const Duration(hours: 23);
  PaymentIntent identified(String id) => PaymentIntent(
    owner,
    sessionId,
    method,
    key: key,
    createdAt: createdAt,
    paymentId: id,
  );
  Map<String, Object> get request => {
    'parkingSessionId': sessionId,
    'paymentMethod': method,
  };
  Map<String, Object> toJson() => {
    ...request,
    'owner': owner,
    'key': key,
    'createdAt': createdAt.toIso8601String(),
    'paymentId': ?paymentId,
  };
  factory PaymentIntent.fromJson(Map data) {
    final key = requiredText(data, 'key');
    final method = requiredText(data, 'paymentMethod');
    if (!RegExp(r'^[a-f0-9]{32}$').hasMatch(key) || method.length > 50) {
      throw invalidParking;
    }
    return PaymentIntent(
      requiredText(data, 'owner'),
      requiredText(data, 'parkingSessionId'),
      method,
      key: key,
      createdAt: DateTime.parse(requiredText(data, 'createdAt')),
      paymentId: data['paymentId'] == null
          ? null
          : requiredText(data, 'paymentId'),
    );
  }
}

abstract interface class PaymentGateway {
  Future<CitizenPayment> create(PaymentIntent intent);
  Future<CitizenPayment> status(String id);
  Future<CitizenPayment> refresh(String id);
}

class PaymentService implements PaymentGateway {
  PaymentService(this.api);
  final ApiClient? api;
  ApiClient get client => api ?? (throw invalidParking);
  Future<CitizenPayment> _read(
    ApiMethod method,
    String path, {
    PaymentIntent? intent,
  }) async {
    final response = await client.request(
      method,
      path,
      body: intent?.request,
      headers: intent == null ? const {} : {'Idempotency-Key': intent.key},
      responseDecoder: parkingJson,
    );
    if (response.statusCode != (intent == null ? 200 : 201) ||
        response.body is! Map) {
      throw invalidParking;
    }
    final payment = CitizenPayment(response.body as Map);
    if (intent != null &&
        (payment.sessionId != intent.sessionId ||
            payment.method != intent.method)) {
      throw invalidParking;
    }
    return payment;
  }

  @override
  Future<CitizenPayment> create(PaymentIntent intent) =>
      _read(ApiMethod.post, 'payments', intent: intent);
  @override
  Future<CitizenPayment> status(String id) async {
    final payment = await _read(
      ApiMethod.get,
      'payments/${Uri.encodeComponent(id)}',
    );
    if (payment.id != id) throw invalidParking;
    return payment;
  }

  @override
  Future<CitizenPayment> refresh(String id) async {
    final payment = await _read(
      ApiMethod.post,
      'payments/${Uri.encodeComponent(id)}/refresh',
    );
    if (payment.id != id) throw invalidParking;
    return payment;
  }
}

String paymentError(Object error) => error is AppFailure
    ? switch (error.kind) {
        FailureKind.unauthorized =>
          'Tu sesión terminó. Ingresa nuevamente para consultar el pago.',
        FailureKind.forbidden =>
          'No tienes autorización para operar este pago.',
        FailureKind.conflict => 'Hay una operación por confirmar. Comprueba su resultado antes de intentar otro pago.',
        FailureKind.invalidRequest => 'La solicitud ya no cumple las condiciones para pagar. Comprueba su estado.',
        FailureKind.unavailable => 'El servicio de pago no está disponible. No damos el pago por confirmado.',
        _ => 'No pudimos confirmar el resultado. Compruébalo antes de intentar otro pago.',
      }
    : 'No pudimos confirmar el resultado. Compruébalo antes de intentar otro pago.';
