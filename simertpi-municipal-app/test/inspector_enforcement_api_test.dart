import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/core/config/municipal_app_config.dart';
import 'package:simertpi_municipal_app/features/auth/data/municipal_auth_api.dart';
import 'package:simertpi_municipal_app/features/auth/data/municipal_token_store.dart';
import 'package:simertpi_municipal_app/features/enforcement/data/inspector_enforcement_api.dart';

void main() {
  group('InspectorEnforcementApi', () {
    late _Transport transport;
    late _MultipartTransport multipart;
    late _TokenStore tokens;
    late InspectorEnforcementApi api;

    setUp(() {
      transport = _Transport();
      multipart = _MultipartTransport();
      tokens = _TokenStore();
      api = InspectorEnforcementApi(
        config: const MunicipalAppConfig(
          environment: MunicipalEnvironment.development,
          apiBaseUrl: 'https://api.example.test/api/v1',
        ),
        tokenStore: tokens,
        transport: transport,
        multipartTransport: multipart,
      );
    });

    test(
      'lookup sends exactly one encoded selector with municipal bearer',
      () async {
        transport.response = const MunicipalHttpResponse(
          200,
          '{"parkingSpaceId":"space-id","plate":"ABC1234","controlEvents":[]}',
        );
        final result = await api.lookup(plate: ' abc 1234 ');
        expect(result.plate, 'ABC1234');
        expect(transport.method, 'GET');
        expect(transport.uri!.path, '/api/v1/inspections/lookup');
        expect(transport.uri!.queryParameters, {'plate': 'abc 1234'});
        expect(transport.headers['Authorization'], 'Bearer test-access-token');
        await expectLater(
          api.lookup(plate: 'A', qrCode: 'B'),
          throwsA(isA<InspectorApiException>()),
        );
      },
    );

    test(
      'inspection uses exact server contract and caller-stable idempotency key',
      () async {
        transport.response = const MunicipalHttpResponse(
          201,
          '{"id":"inspection-id","parkingSpaceId":"space-id","result":"EXPIRED","observedAt":"2026-10-28T14:00:00Z"}',
        );
        final date = DateTime.utc(2026, 10, 28, 14);
        final result = await api.createInspection(
          parkingSpaceId: 'space-id',
          observedPlate: 'abc1234',
          result: 'EXPIRED',
          observedAt: date,
          idempotencyKey: 'stable-retry-key',
        );
        expect(result.id, 'inspection-id');
        expect(transport.uri!.path, '/api/v1/inspections');
        expect(transport.headers['Idempotency-Key'], 'stable-retry-key');
        final body = jsonDecode(transport.body!) as Map<String, Object?>;
        expect(body['observedPlate'], 'ABC1234');
        expect(body['observedAt'], '2026-10-28T14:00:00.000Z');
        expect(body['result'], 'EXPIRED');
        expect(body.containsKey('fineAmount'), isFalse);
      },
    );

    test('possible violation is an explicit request, contains no fine or notification', () async {
      transport.response = const MunicipalHttpResponse(
        201,
        '{"id":"v1","inspectionId":"i1","status":"OPEN","fineAmount":0}',
      );
      final record = await api.createPotentialViolation(
        inspectionId: 'i1',
        observedType: 'Hecho observado',
        occurredAt: DateTime.utc(2026, 10, 28),
        idempotencyKey: 'violation-retry-key',
      );
      final body = jsonDecode(transport.body!) as Map<String, Object?>;
      expect(record.status, 'OPEN');
      expect(
        body.keys,
        containsAll(['inspectionId', 'violationType', 'occurredAt']),
      );
      expect(body.containsKey('fineAmount'), isFalse);
      expect(transport.headers['Idempotency-Key'], 'violation-retry-key');
    });

    test('HTTP failures have differentiated messages and expired token sends no request', () async {
      transport.response = const MunicipalHttpResponse(409, '{}');
      await expectLater(
        api.lookup(qrCode: 'QR-1'),
        throwsA(
          isA<InspectorApiException>().having(
            (e) => e.statusCode,
            'status',
            409,
          ),
        ),
      );
      expect(transport.headers['Authorization'], 'Bearer test-access-token');
      tokens.expired = true;
      await expectLater(
        api.lookup(qrCode: 'QR-1'),
        throwsA(
          isA<InspectorApiException>().having(
            (e) => e.statusCode,
            'status',
            401,
          ),
        ),
      );
      expect(transport.sendCount, 1);
    });

    test('photo preflight accepts JPEG/PNG magic and rejects spoofed or oversized files', () {
      final jpeg = PhotoEvidenceDraft.validate(
        filename: 'camera.jpg',
        bytes: [0xff, 0xd8, 0xff, 0x00],
      );
      expect(jpeg.contentType, 'image/jpeg');
      expect(
        () => PhotoEvidenceDraft.validate(
          filename: 'camera.png',
          bytes: [0xff, 0xd8, 0xff],
        ),
        throwsA(isA<InspectorApiException>()),
      );
      expect(
        () => PhotoEvidenceDraft.validate(
          filename: 'huge.jpg',
          bytes: List<int>.filled(10 * 1024 * 1024 + 1, 0),
        ),
        throwsA(isA<InspectorApiException>()),
      );
      expect(
        () => PhotoEvidenceDraft.validate(
          filename: 'bad"name.jpg',
          bytes: [0xff, 0xd8, 0xff],
        ),
        throwsA(isA<InspectorApiException>()),
      );
    });

    test(
      'evidence upload uses secure bearer, multipart contract and idempotency',
      () async {
        multipart.response = const MunicipalHttpResponse(
          201,
          '{"id":"evidence-1","violationId":"violation-1"}',
        );
        final draft = PhotoEvidenceDraft.validate(
          filename: 'capture.jpg',
          bytes: [0xff, 0xd8, 0xff, 0x10],
          capturedAt: DateTime.utc(2026, 10, 28, 14),
        );
        final result = await api.uploadPhotoEvidence(
          violationId: 'violation-1',
          draft: draft,
          idempotencyKey: 'evidence-retry-key',
        );
        expect(result.id, 'evidence-1');
        expect(multipart.uri!.path, '/api/v1/evidence');
        expect(multipart.accessToken, 'test-access-token');
        expect(multipart.idempotencyKey, 'evidence-retry-key');
        expect(multipart.filename, 'capture.jpg');
        expect(multipart.contentType, 'image/jpeg');
        expect(multipart.fields['violationId'], 'violation-1');
        expect(multipart.fields['capturedAt'], '2026-10-28T14:00:00.000Z');
        expect(multipart.bytes, draft.bytes);
        tokens.expired = true;
        await expectLater(
          api.uploadPhotoEvidence(violationId: 'violation-1', draft: draft),
          throwsA(
            isA<InspectorApiException>().having(
              (e) => e.statusCode,
              'status',
              401,
            ),
          ),
        );
        expect(multipart.sendCount, 1);
      },
    );
  });
}

class _Transport implements MunicipalHttpTransport {
  MunicipalHttpResponse response = const MunicipalHttpResponse(200, '{}');
  String? method;
  Uri? uri;
  Map<String, String> headers = const {};
  String? body;
  int sendCount = 0;

  @override
  Future<MunicipalHttpResponse> send(
    String method,
    Uri uri, {
    Map<String, String> headers = const {},
    String? body,
  }) async {
    this.method = method;
    this.uri = uri;
    this.headers = headers;
    this.body = body;
    sendCount++;
    return response;
  }
}

class _TokenStore implements MunicipalTokenStore {
  bool expired = false;
  @override
  Future<void> clear() async {}
  @override
  Future<MunicipalStoredTokens?> read() async => expired
      ? MunicipalStoredTokens(
          accessToken: 'test-access-token',
          refreshToken: 'refresh',
          accessExpiresAt: DateTime.utc(2020),
          refreshExpiresAt: DateTime.utc(2030),
        )
      : MunicipalStoredTokens(
          accessToken: 'test-access-token',
          refreshToken: 'refresh',
          accessExpiresAt: DateTime.utc(2030),
          refreshExpiresAt: DateTime.utc(2040),
        );
  @override
  Future<void> write(MunicipalStoredTokens tokens) async {}
}

class _MultipartTransport implements InspectorMultipartTransport {
  MunicipalHttpResponse response = const MunicipalHttpResponse(201, '{}');
  Uri? uri;
  String? accessToken;
  String? idempotencyKey;
  String? filename;
  String? contentType;
  List<int> bytes = const [];
  Map<String, String> fields = const {};
  int sendCount = 0;

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
    this.uri = uri;
    this.accessToken = accessToken;
    this.idempotencyKey = idempotencyKey;
    this.filename = filename;
    this.contentType = contentType;
    this.bytes = bytes;
    this.fields = fields;
    sendCount++;
    return response;
  }
}
