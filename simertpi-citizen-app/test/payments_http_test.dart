import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/payments/data/payment_contract.dart';

final clock = DateTime.utc(2026, 10, 4, 15, 1);
Map<String, Object?> paymentData([String status = 'APPROVED']) => {
  'id': 'payment-fixture',
  'parkingSessionId': 'receipt',
  'provider': 'SANDBOX_STUB',
  'providerTransactionId': status == 'APPROVED' ? 'fixture-reference' : null,
  'amount': '0.31',
  'currency': 'USD',
  'status': status,
  'paymentMethod': 'TEST',
  'paidAt': status == 'APPROVED' ? clock.toIso8601String() : null,
  'createdAt': clock.toIso8601String(),
  'updatedAt': clock.toIso8601String(),
};
void main() {
  test('HTTP boundary matches CP12 exactly, Bearer, correlation, idempotency, decimals and recovery endpoints', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final received = <String>[];
    final sub = server.listen((request) async {
      received.add('${request.method} ${request.uri.path}');
      expect(request.headers.value('Authorization'), 'Bearer fixture-session');
      expect(
        request.headers.value(correlationHeader),
        matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
      );
      if (request.uri.path == '/api/v1/payments') {
        expect(
          request.headers.value('Idempotency-Key'),
          matches(RegExp(r'^[a-f0-9]{32}$')),
        );
        expect(jsonDecode(await utf8.decoder.bind(request).join()), {
          'parkingSessionId': 'receipt',
          'paymentMethod': 'TEST',
        });
        request.response.statusCode = 201;
      }
      request.response.headers.contentType = ContentType.json;
      request.response.write(
        jsonEncode(paymentData('PENDING')).replaceFirst('"0.31"', '0.31'),
      );
      await request.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () async => {'Authorization': 'Bearer fixture-session'},
    );
    try {
      final service = PaymentService(api);
      final result = await service.create(
        PaymentIntent('fixture-citizen', 'receipt', 'TEST'),
      );
      expect(result.amount, '0.31');
      await service.status(result.id);
      await service.refresh(result.id);
      expect(received, [
        'POST /api/v1/payments',
        'GET /api/v1/payments/payment-fixture',
        'POST /api/v1/payments/payment-fixture/refresh',
      ]);
    } finally {
      api.close();
      await sub.cancel();
      await server.close(force: true);
    }
  });
  for (final code in [401, 403, 409, 503]) {
    test(
      'Payment HTTP $code maps safely without exposing backend details',
      () async {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        final sub = server.listen((request) async {
          request.response.statusCode = code;
          request.response.write('PRIVATE_TOKEN_SQL');
          await request.response.close();
        });
        final api = ApiClient(
          baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        );
        try {
          await PaymentService(api)
              .create(PaymentIntent('fixture-citizen', 'receipt', 'TEST'));
          fail('Expected error');
        } catch (error) {
          expect(error, isA<AppFailure>());
          expect((error as AppFailure).kind, switch (code) {
            401 => FailureKind.unauthorized,
            403 => FailureKind.forbidden,
            409 => FailureKind.conflict,
            _ => FailureKind.unavailable,
          });
          expect(paymentError(error), isNot(contains('PRIVATE_TOKEN_SQL')));
        } finally {
          api.close();
          await sub.cancel();
          await server.close(force: true);
        }
      },
    );
  }
  test(
    '401 renewal retries once with same payment key/body and decimal decoder',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final keys = <String?>[];
      final bodies = <Object?>[];
      int renewals = 0;
      final sub = server.listen((r) async {
        keys.add(r.headers.value('Idempotency-Key'));
        bodies.add(jsonDecode(await utf8.decoder.bind(r).join()));
        if (keys.length == 1) {
          r.response.statusCode = 401;
        } else {
          r.response.statusCode = 201;
          r.response.write(
            jsonEncode(paymentData('PENDING')).replaceFirst('"0.31"', '0.10'),
          );
        }
        await r.response.close();
      });
      final api = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        headersProvider: () async => {
          'Authorization': 'Bearer fixture-session',
        },
        onUnauthorized: (_) async {
          renewals++;
          return true;
        },
      );
      try {
        final intent = PaymentIntent('fixture-citizen', 'receipt', 'TEST');
        final result = await PaymentService(api).create(intent);
        expect(result.amount, '0.10');
        expect(renewals, 1);
        expect(keys, [intent.key, intent.key]);
        expect(bodies, [intent.request, intent.request]);
      } finally {
        api.close();
        await sub.cancel();
        await server.close(force: true);
      }
    },
  );
}
