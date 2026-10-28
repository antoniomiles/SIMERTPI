import 'dart:convert';
import 'dart:io';
import 'dart:math';

import '../../../core/config/municipal_app_config.dart';
import '../../auth/data/municipal_auth_api.dart';
import '../../auth/data/municipal_token_store.dart';

class InspectorApiException implements Exception {
  const InspectorApiException(this.message, {this.statusCode});
  final String message;
  final int? statusCode;
  @override
  String toString() => message;
}

class InspectionSituation {
  const InspectionSituation({
    this.parkingSpaceId,
    this.parkingSpaceCode,
    this.parkingSpaceQrCode,
    this.vehicleId,
    this.plate,
    this.parkingSessionId,
    this.sessionStatus,
    this.startedAt,
    this.expectedEndAt,
    this.endedAt,
    this.controlEvents = const [],
  });

  final String? parkingSpaceId;
  final String? parkingSpaceCode;
  final String? parkingSpaceQrCode;
  final String? vehicleId;
  final String? plate;
  final String? parkingSessionId;
  final String? sessionStatus;
  final DateTime? startedAt;
  final DateTime? expectedEndAt;
  final DateTime? endedAt;
  final List<Map<String, Object?>> controlEvents;

  factory InspectionSituation.fromJson(Map<String, Object?> json) =>
      InspectionSituation(
        parkingSpaceId: json['parkingSpaceId'] as String?,
        parkingSpaceCode: json['parkingSpaceCode'] as String?,
        parkingSpaceQrCode: json['parkingSpaceQrCode'] as String?,
        vehicleId: json['vehicleId'] as String?,
        plate: json['plate'] as String?,
        parkingSessionId: json['parkingSessionId'] as String?,
        sessionStatus: json['sessionStatus'] as String?,
        startedAt: _date(json['startedAt']),
        expectedEndAt: _date(json['expectedEndAt']),
        endedAt: _date(json['endedAt']),
        controlEvents: (json['controlEvents'] as List<Object?>? ?? const [])
            .whereType<Map>()
            .map((value) => value.cast<String, Object?>())
            .toList(growable: false),
      );
}

class InspectionRecord {
  const InspectionRecord({
    required this.id,
    required this.parkingSpaceId,
    required this.result,
    required this.observedAt,
    this.parkingSessionId,
    this.vehicleId,
    this.observedPlate,
    this.notes,
  });

  final String id;
  final String parkingSpaceId;
  final String? parkingSessionId;
  final String? vehicleId;
  final String? observedPlate;
  final String result;
  final DateTime observedAt;
  final String? notes;

  factory InspectionRecord.fromJson(Map<String, Object?> json) =>
      InspectionRecord(
        id: _requiredString(json, 'id'),
        parkingSpaceId: _requiredString(json, 'parkingSpaceId'),
        parkingSessionId: json['parkingSessionId'] as String?,
        vehicleId: json['vehicleId'] as String?,
        observedPlate: json['observedPlate'] as String?,
        result: _requiredString(json, 'result'),
        observedAt:
            _date(json['observedAt']) ??
            (throw const FormatException('observedAt missing')),
        notes: json['notes'] as String?,
      );
}

class ViolationRecord {
  const ViolationRecord({
    required this.id,
    required this.inspectionId,
    required this.status,
    required this.fineAmount,
    this.plate,
    this.violationType,
  });

  final String id;
  final String inspectionId;
  final String status;
  final num fineAmount;
  final String? plate;
  final String? violationType;

  factory ViolationRecord.fromJson(Map<String, Object?> json) =>
      ViolationRecord(
        id: _requiredString(json, 'id'),
        inspectionId: _requiredString(json, 'inspectionId'),
        status: _requiredString(json, 'status'),
        fineAmount: json['fineAmount'] as num? ?? 0,
        plate: json['plate'] as String?,
        violationType: json['violationType'] as String?,
      );
}

class EvidenceRecord {
  const EvidenceRecord({required this.id, required this.violationId});

  final String id;
  final String violationId;

  factory EvidenceRecord.fromJson(Map<String, Object?> json) => EvidenceRecord(
    id: _requiredString(json, 'id'),
    violationId: _requiredString(json, 'violationId'),
  );
}

abstract interface class InspectorEnforcementRepository {
  Future<InspectionSituation> lookup({
    String? plate,
    String? qrCode,
    String? parkingSpaceId,
  });
  Future<InspectionRecord> createInspection({
    required String parkingSpaceId,
    String? parkingSessionId,
    String? vehicleId,
    String? observedPlate,
    required String result,
    String? notes,
    required DateTime observedAt,
    String? idempotencyKey,
  });
  Future<ViolationRecord> createPotentialViolation({
    required String inspectionId,
    required String observedType,
    String? notes,
    required DateTime occurredAt,
    String? idempotencyKey,
  });
  Future<List<InspectionRecord>> inspectionsByInspector(String inspectorId);
  Future<List<ViolationRecord>> violationsByInspector(String inspectorId);
  Future<EvidenceRecord> uploadPhotoEvidence({
    required String violationId,
    required PhotoEvidenceDraft draft,
    String? idempotencyKey,
  });
}

abstract interface class InspectorMultipartTransport {
  Future<MunicipalHttpResponse> send({
    required Uri uri,
    required String accessToken,
    required String idempotencyKey,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required Map<String, String> fields,
  });
}

class InspectorEnforcementApi implements InspectorEnforcementRepository {
  InspectorEnforcementApi({
    required this.config,
    required this.tokenStore,
    required this.transport,
    InspectorMultipartTransport? multipartTransport,
  }) : _multipartTransport =
           multipartTransport ?? IoInspectorMultipartTransport();

  final MunicipalAppConfig config;
  final MunicipalTokenStore tokenStore;
  final MunicipalHttpTransport transport;
  final InspectorMultipartTransport _multipartTransport;

  @override
  Future<InspectionSituation> lookup({
    String? plate,
    String? qrCode,
    String? parkingSpaceId,
  }) async {
    final selectors = [
      plate,
      qrCode,
      parkingSpaceId,
    ].where((value) => value != null && value.trim().isNotEmpty).length;
    if (selectors != 1) {
      throw const InspectorApiException(
        'Seleccione una sola forma de consulta.',
      );
    }
    final query = <String, String>{
      if (plate != null && plate.trim().isNotEmpty) 'plate': plate.trim(),
      if (qrCode != null && qrCode.trim().isNotEmpty) 'qrCode': qrCode.trim(),
      if (parkingSpaceId != null && parkingSpaceId.trim().isNotEmpty)
        'parkingSpaceId': parkingSpaceId.trim(),
    };
    final response = await _send('GET', '/inspections/lookup', query: query);
    return InspectionSituation.fromJson(_decodeObject(response.body));
  }

  @override
  Future<InspectionRecord> createInspection({
    required String parkingSpaceId,
    String? parkingSessionId,
    String? vehicleId,
    String? observedPlate,
    required String result,
    String? notes,
    required DateTime observedAt,
    String? idempotencyKey,
  }) async {
    final key = idempotencyKey ?? _newIdempotencyKey();
    final payload = <String, Object?>{
      'parkingSpaceId': parkingSpaceId,
      'parkingSessionId': ?parkingSessionId,
      'vehicleId': ?vehicleId,
      if (observedPlate != null && observedPlate.trim().isNotEmpty)
        'observedPlate': observedPlate.trim().toUpperCase(),
      'observedAt': observedAt.toUtc().toIso8601String(),
      'result': result,
      if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
    };
    final response = await _send(
      'POST',
      '/inspections',
      body: jsonEncode(payload),
      idempotencyKey: key,
    );
    return InspectionRecord.fromJson(_decodeObject(response.body));
  }

  @override
  Future<ViolationRecord> createPotentialViolation({
    required String inspectionId,
    required String observedType,
    String? notes,
    required DateTime occurredAt,
    String? idempotencyKey,
  }) async {
    final response = await _send(
      'POST',
      '/violations',
      body: jsonEncode({
        'inspectionId': inspectionId,
        'violationType': observedType.trim(),
        if (notes != null && notes.trim().isNotEmpty)
          'description': notes.trim(),
        'occurredAt': occurredAt.toUtc().toIso8601String(),
      }),
      idempotencyKey: idempotencyKey ?? _newIdempotencyKey(),
    );
    return ViolationRecord.fromJson(_decodeObject(response.body));
  }

  @override
  Future<List<InspectionRecord>> inspectionsByInspector(
    String inspectorId,
  ) async {
    final response = await _send('GET', '/inspections/inspector/$inspectorId');
    return _decodeList(response.body)
        .map(InspectionRecord.fromJson)
        .toList(growable: false);
  }

  @override
  Future<List<ViolationRecord>> violationsByInspector(
    String inspectorId,
  ) async {
    final response = await _send('GET', '/violations/inspector/$inspectorId');
    return _decodeList(response.body)
        .map(ViolationRecord.fromJson)
        .toList(growable: false);
  }

  @override
  Future<EvidenceRecord> uploadPhotoEvidence({
    required String violationId,
    required PhotoEvidenceDraft draft,
    String? idempotencyKey,
  }) async {
    final base = config.apiBaseUri;
    if (base == null) {
      throw const InspectorApiException(
        'El servicio municipal no está configurado.',
      );
    }
    final tokens = await tokenStore.read();
    if (tokens == null ||
        !tokens.accessExpiresAt.isAfter(DateTime.now().toUtc())) {
      throw const InspectorApiException(
        'La sesión venció. Inicie sesión nuevamente.',
        statusCode: 401,
      );
    }
    final basePath = base.path.replaceFirst(RegExp(r'/+$'), '');
    final uri = base.replace(
      path: '$basePath/evidence',
      query: null,
      fragment: null,
    );
    try {
      final response = await _multipartTransport.send(
        uri: uri,
        accessToken: tokens.accessToken,
        idempotencyKey: idempotencyKey ?? _newIdempotencyKey(),
        filename: draft.filename,
        contentType: draft.contentType,
        bytes: draft.bytes,
        fields: {
          'violationId': violationId,
          'capturedAt': draft.capturedAt.toUtc().toIso8601String(),
        },
      );
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw InspectorApiException(
          _messageForStatus(response.statusCode),
          statusCode: response.statusCode,
        );
      }
      return EvidenceRecord.fromJson(_decodeObject(response.body));
    } on InspectorApiException {
      rethrow;
    } catch (_) {
      throw const InspectorApiException(
        'No se pudo cargar la evidencia. Conserve la foto e inténtelo nuevamente.',
      );
    }
  }

  Future<MunicipalHttpResponse> _send(
    String method,
    String path, {
    Map<String, String> query = const {},
    String? body,
    String? idempotencyKey,
  }) async {
    final base = config.apiBaseUri;
    if (base == null) {
      throw const InspectorApiException(
        'El servicio municipal no está configurado.',
      );
    }
    final basePath = base.path.replaceFirst(RegExp(r'/+$'), '');
    final uri = base.replace(
      path: '$basePath$path',
      queryParameters: query.isEmpty ? null : query,
      fragment: null,
    );
    try {
      final tokens = await tokenStore.read();
      if (tokens == null ||
          !tokens.accessExpiresAt.isAfter(DateTime.now().toUtc())) {
        throw const InspectorApiException(
          'La sesión venció. Inicie sesión nuevamente.',
          statusCode: 401,
        );
      }
      final response = await transport.send(
        method,
        uri,
        headers: {
          'Authorization': 'Bearer ${tokens.accessToken}',
          'Idempotency-Key': ?idempotencyKey,
        },
        body: body,
      );
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw InspectorApiException(
          _messageForStatus(response.statusCode),
          statusCode: response.statusCode,
        );
      }
      return response;
    } on InspectorApiException {
      rethrow;
    } catch (_) {
      throw const InspectorApiException(
        'No se pudo conectar con el servicio municipal. Verifique la conexión e inténtelo nuevamente.',
      );
    }
  }

  static String _messageForStatus(int status) => switch (status) {
    400 => 'El servidor rechazó los datos. Revise la información ingresada.',
    401 => 'La sesión venció. Inicie sesión nuevamente.',
    403 => 'No tiene autorización para esta operación.',
    404 => 'No se encontró el vehículo, espacio o registro solicitado.',
    409 => 'La operación ya fue registrada o existe un conflicto. Actualice la consulta.',
    413 => 'La evidencia excede el tamaño permitido.',
    415 => 'El formato del archivo no está permitido.',
    >= 500 => 'El servicio municipal presenta un problema temporal. Inténtelo nuevamente.',
    _ => 'No se pudo completar la operación (HTTP $status).',
  };
}

/// Client-side preflight matching the backend-supported photographic formats.
/// Image resizing requires a native image plugin and is intentionally not done here.
class PhotoEvidenceDraft {
  PhotoEvidenceDraft._({
    required this.filename,
    required this.contentType,
    required this.bytes,
    required this.capturedAt,
  });
  final String filename;
  final String contentType;
  final List<int> bytes;
  final DateTime capturedAt;

  static PhotoEvidenceDraft validate({
    required String filename,
    required List<int> bytes,
    DateTime? capturedAt,
  }) {
    final safeName = filename.replaceAll('\\', '/').split('/').last.trim();
    if (safeName.isEmpty ||
        safeName.length > 255 ||
        safeName.contains('"') ||
        safeName.contains(RegExp(r'[\r\n\x00-\x1f]'))) {
      throw const InspectorApiException('El nombre de la foto no es válido.');
    }
    if (bytes.isEmpty) {
      throw const InspectorApiException('La foto está vacía.');
    }
    if (bytes.length > 10 * 1024 * 1024) {
      throw const InspectorApiException('La foto supera el límite de 10 MB.');
    }
    final lower = safeName.toLowerCase();
    final jpeg =
        bytes.length >= 3 &&
        bytes[0] == 0xff &&
        bytes[1] == 0xd8 &&
        bytes[2] == 0xff;
    const pngMagic = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
    final png =
        bytes.length >= pngMagic.length &&
        List<int>.generate(pngMagic.length, (i) => bytes[i]).toString() ==
            pngMagic.toString();
    final contentType =
        jpeg && (lower.endsWith('.jpg') || lower.endsWith('.jpeg'))
        ? 'image/jpeg'
        : png && lower.endsWith('.png')
        ? 'image/png'
        : null;
    if (contentType == null) {
      throw const InspectorApiException(
        'Use una foto JPEG o PNG válida; el contenido debe coincidir con la extensión.',
      );
    }
    return PhotoEvidenceDraft._(
      filename: safeName,
      contentType: contentType,
      bytes: List.unmodifiable(bytes),
      capturedAt: capturedAt ?? DateTime.now().toUtc(),
    );
  }
}

class IoInspectorMultipartTransport implements InspectorMultipartTransport {
  IoInspectorMultipartTransport({this.timeout = const Duration(seconds: 30)});

  final Duration timeout;

  @override
  Future<MunicipalHttpResponse> send({
    required Uri uri,
    required String accessToken,
    required String idempotencyKey,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required Map<String, String> fields,
  }) async {
    final boundary = 'simertpi-${_newIdempotencyKey()}';
    final client = HttpClient()..connectionTimeout = timeout;
    try {
      final request = await client.postUrl(uri).timeout(timeout);
      request.headers
        ..set(HttpHeaders.authorizationHeader, 'Bearer $accessToken')
        ..set(HttpHeaders.acceptHeader, 'application/json')
        ..set(
          HttpHeaders.contentTypeHeader,
          'multipart/form-data; boundary=$boundary',
        )
        ..set('Idempotency-Key', idempotencyKey);
      for (final entry in fields.entries) {
        request.add(
          utf8.encode(
            '--$boundary\r\nContent-Disposition: form-data; name="${entry.key}"\r\n\r\n${entry.value}\r\n',
          ),
        );
      }
      request.add(
        utf8.encode(
          '--$boundary\r\nContent-Disposition: form-data; name="file"; filename="$filename"\r\nContent-Type: $contentType\r\n\r\n',
        ),
      );
      request.add(bytes);
      request.add(utf8.encode('\r\n--$boundary--\r\n'));
      final response = await request.close().timeout(timeout);
      final responseBody = await utf8.decoder
          .bind(response)
          .join()
          .timeout(timeout);
      return MunicipalHttpResponse(response.statusCode, responseBody);
    } finally {
      client.close(force: true);
    }
  }
}

Map<String, Object?> _decodeObject(String body) {
  final value = jsonDecode(body);
  if (value is Map<String, Object?>) return value;
  if (value is Map) return value.cast<String, Object?>();
  throw const FormatException('Se esperaba un objeto JSON.');
}

List<Map<String, Object?>> _decodeList(String body) {
  final value = jsonDecode(body);
  if (value is! List) {
    throw const FormatException('Se esperaba una lista JSON.');
  }
  return value
      .whereType<Map>()
      .map((item) => item.cast<String, Object?>())
      .toList(growable: false);
}

String _requiredString(Map<String, Object?> json, String key) {
  final value = json[key];
  if (value is! String || value.isEmpty) throw FormatException('Falta $key.');
  return value;
}

DateTime? _date(Object? value) =>
    value is String ? DateTime.tryParse(value)?.toUtc() : null;

String newInspectorIdempotencyKey() => _newIdempotencyKey();

String _newIdempotencyKey() {
  final random = Random.secure();
  return List<int>.generate(
    32,
    (_) => random.nextInt(16),
  ).map((digit) => digit.toRadixString(16)).join();
}
