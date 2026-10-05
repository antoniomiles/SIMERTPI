import 'dart:convert';
import 'dart:math';

import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../discovery/data/parking_catalog.dart';

// Preserve decimal JSON lexemes before jsonDecode can convert them to double.
Object? parkingJson(String text) => jsonDecode(
  text.replaceAllMapped(
    RegExp(r'"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?'),
    (m) {
      final token = m[0]!;
      return token.startsWith('"') || !token.contains(RegExp(r'[.eE]'))
          ? token
          : '"$token"';
    },
  ),
);
String money(Object? value) {
  final text = value is int ? '$value' : value;
  if (text is! String || !RegExp(r'^\d+(?:\.\d+)?$').hasMatch(text)) {
    throw invalidParking;
  }
  return text.contains('.') ? text : '$text.00';
}

const invalidParking = AppFailure(FailureKind.unavailable);
String requiredText(Map data, String key) {
  final value = data[key];
  if (value is! String || value.isEmpty) throw invalidParking;
  return value;
}

int? optionalMinutes(Map data, String key) {
  final value = data[key];
  if (value == null) return null;
  if (value is! int || value < 0) throw invalidParking;
  return value;
}

class ParkingRules {
  ParkingRules(Map data)
    : spaceId = requiredText(data, 'spaceId'),
      reason = requiredText(data, 'reasonCode'),
      evaluatedAt = requiredText(data, 'evaluatedAt'),
      operational = data['operational'] == true,
      holiday = data['holiday'] == true,
      minimum = optionalMinutes(data, 'minimumFractionMinutes'),
      maximum = optionalMinutes(data, 'maximumContinuousMinutes'),
      grace = optionalMinutes(data, 'gracePeriodMinutes'),
      minutes = optionalMinutes(data, 'requestedDurationMinutes'),
      billed = optionalMinutes(data, 'billedDurationMinutes'),
      unitMinutes = optionalMinutes(data, 'unitDurationMinutes'),
      currency = data['currency'] as String?,
      code = data['applicableTariff'] as String?,
      amount = data['calculatedAmount'] == null
          ? null
          : money(data['calculatedAmount']),
      unitPrice = data['unitPrice'] == null ? null : money(data['unitPrice']),
      expiresAt = data['expiresAt'] as String?,
      schedule = data['applicableSchedule'] as Map? {
    if (data['operational'] is! bool ||
        data['holiday'] is! bool ||
        !RegExp(r'(Z|[+-]\d{2}:\d{2})(?:\[[^\]]+\])?$').hasMatch(evaluatedAt)) {
      throw invalidParking;
    }
    if (operational &&
        (minimum == null ||
            minimum! <= 0 ||
            maximum == null ||
            maximum! < minimum! ||
            currency == null ||
            !RegExp(r'^[A-Z]{3}$').hasMatch(currency!) ||
            code == null ||
            unitPrice == null ||
            unitMinutes == null ||
            unitMinutes! <= 0 ||
            grace == null ||
            schedule?['startTime'] is! String ||
            schedule?['endTime'] is! String)) {
      throw invalidParking;
    }
    if (expiresAt != null && DateTime.tryParse(expiresAt!) == null) {
      throw invalidParking;
    }
    if (expiresAt != null &&
        !RegExp(r'(Z|[+-]\d{2}:\d{2})$').hasMatch(expiresAt!)) {
      throw invalidParking;
    }
  }
  final String spaceId, reason, evaluatedAt;
  final bool operational, holiday;
  final int? minimum, maximum, grace, minutes, billed, unitMinutes;
  final String? currency, code, amount, unitPrice, expiresAt;
  final Map? schedule;
  bool get quoted =>
      operational &&
      reason == 'RULES_RESOLVED' &&
      amount != null &&
      minutes != null &&
      billed != null &&
      billed! >= minutes! &&
      expiresAt != null;
  String get conditions =>
      '$code|$currency|$unitPrice|$unitMinutes|$minimum|$maximum|$grace|$amount|$billed|$minutes|${schedule?['startTime']}|${schedule?['endTime']}|$holiday';
  String get window => '${schedule?['startTime']} – ${schedule?['endTime']}';
}

class ParkingIntent {
  ParkingIntent(
    this.owner,
    this.spaceId,
    this.qr,
    this.vehicleId,
    this.tariffId,
    this.minutes, {
    String? key,
  }) : key =
           key ??
           List.generate(
             16,
             (_) =>
                 Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'),
           ).join();
  final String owner, spaceId, qr, vehicleId, tariffId, key;
  final int minutes;
  Map<String, Object> get request => {
    'parkingSpaceQrCode': qr,
    'vehicleId': vehicleId,
    'tariffId': tariffId,
    'durationMinutes': minutes,
  };
  Map<String, Object> toJson() => {
    ...request,
    'owner': owner,
    'spaceId': spaceId,
    'key': key,
  };
  factory ParkingIntent.fromJson(Map data) {
    final minutes = optionalMinutes(data, 'durationMinutes');
    final key = requiredText(data, 'key');
    if (minutes == null ||
        minutes <= 0 ||
        !RegExp(r'^[a-f0-9]{32}$').hasMatch(key)) {
      throw invalidParking;
    }
    return ParkingIntent(
      requiredText(data, 'owner'),
      requiredText(data, 'spaceId'),
      requiredText(data, 'parkingSpaceQrCode'),
      requiredText(data, 'vehicleId'),
      requiredText(data, 'tariffId'),
      minutes,
      key: key,
    );
  }
}

class ParkingReceipt {
  ParkingReceipt(Map data)
    : id = requiredText(data, 'id'),
      owner = requiredText(data, 'userId'),
      spaceId = requiredText(data, 'parkingSpaceId'),
      vehicleId = requiredText(data, 'vehicleId'),
      tariffId = data['tariffId'] as String?,
      status = requiredText(data, 'status'),
      startedAt = DateTime.parse(requiredText(data, 'startedAt')),
      expectedEndAt = DateTime.parse(requiredText(data, 'expectedEndAt')) {
    if (!RegExp(r'(Z|[+-]\d{2}:\d{2})$')
            .hasMatch(requiredText(data, 'startedAt')) ||
        !RegExp(r'(Z|[+-]\d{2}:\d{2})$')
            .hasMatch(requiredText(data, 'expectedEndAt'))) {
      throw invalidParking;
    }
    if (!{
          'PENDING_PAYMENT',
          'ACTIVE',
          'EXTENDED',
          'EXPIRED',
          'MAX_TIME_REACHED',
          'COMPLETED',
          'CANCELLED',
        }.contains(status) ||
        !expectedEndAt.isAfter(startedAt)) {
      throw invalidParking;
    }
  }
  final String id, owner, spaceId, vehicleId, status;
  final String? tariffId;
  final DateTime startedAt, expectedEndAt;
  bool matches(ParkingIntent i) =>
      owner == i.owner &&
      spaceId == i.spaceId &&
      vehicleId == i.vehicleId &&
      tariffId == i.tariffId &&
      expectedEndAt.difference(startedAt).inSeconds == i.minutes * 60;
}

abstract interface class ParkingGateway {
  Future<ParkingRules> rules(String spaceId, [int? minutes]);
  Future<String> tariffId(String code);
  Future<ParkingReceipt> create(ParkingIntent intent);
  Future<List<ParkingReceipt>> mine(String owner);
}

class ParkingService implements ParkingGateway {
  ParkingService(this.api);
  final ApiClient? api;
  ApiClient get client => api ?? (throw invalidParking);
  @override
  Future<ParkingRules> rules(String spaceId, [int? minutes]) async {
    final r = await client.request(
      ApiMethod.get,
      'parking/rules',
      query: {
        'spaceId': spaceId,
        if (minutes != null) 'durationMinutes': '$minutes',
      },
      responseDecoder: parkingJson,
    );
    if (r.body is! Map) throw invalidParking;
    final result = ParkingRules(r.body as Map);
    if (result.spaceId != spaceId ||
        result.minutes != minutes ||
        (minutes != null && result.operational && !result.quoted)) {
      throw invalidParking;
    }
    return result;
  }

  @override
  Future<String> tariffId(String code) async {
    if (!safeIdentifier(code, maximum: 50)) throw invalidParking;
    final r = await client.request(
      ApiMethod.get,
      'tariffs/code/${Uri.encodeComponent(code)}',
    );
    if (r.body is! Map ||
        (r.body as Map)['code'] != code ||
        (r.body as Map)['active'] != true) {
      throw invalidParking;
    }
    return requiredText(r.body as Map, 'id');
  }

  @override
  Future<ParkingReceipt> create(ParkingIntent intent) async {
    final r = await client.request(
      ApiMethod.post,
      'parking/sessions',
      body: intent.request,
      headers: {'Idempotency-Key': intent.key},
      responseDecoder: parkingJson,
    );
    if (r.statusCode != 201 || r.body is! Map) throw invalidParking;
    final receipt = ParkingReceipt(r.body as Map);
    if (!receipt.matches(intent) || receipt.status != 'PENDING_PAYMENT') {
      throw invalidParking;
    }
    return receipt;
  }

  @override
  Future<List<ParkingReceipt>> mine(String owner) async {
    final r = await client.request(
      ApiMethod.get,
      'parking-sessions/user/${Uri.encodeComponent(owner)}',
      responseDecoder: parkingJson,
    );
    if (r.body is! List) throw invalidParking;
    final list = (r.body as List).map((v) => ParkingReceipt(v as Map)).toList();
    if (list.any((s) => s.owner != owner)) throw invalidParking;
    return list;
  }
}

String ruleMessage(String reason) => switch (reason) {
  'OUTSIDE_OPERATION_HOURS' || 'DURATION_OUTSIDE_OPERATION_HOURS' => 'La duración no cabe en el horario habilitado. Consulta un tiempo menor o vuelve en otro horario.',
  'HOLIDAY_NON_CHARGEABLE' =>
    'Hoy no está habilitado el estacionamiento tarifado según el calendario.',
  'INACTIVE_PARKING_LOCATION' => 'Esta ubicación ya no está habilitada.',
  'INVALID_DURATION' => 'Revisa la duración solicitada.',
  'MAX_CONTINUOUS_EXCEEDED' => 'La duración supera el máximo permitido.',
  _ =>
    'No es posible preparar el estacionamiento con la configuración vigente.',
};
String parkingError(Object error) =>
    error is AppFailure && error.kind == FailureKind.conflict
    ? 'El espacio o las condiciones cambiaron. Consulta nuevamente o elige otro espacio.'
    : error is AppFailure
    ? error.message
    : 'No pudimos completar la consulta. Inténtalo nuevamente.';
