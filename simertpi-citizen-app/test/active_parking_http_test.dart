import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/active_parking/data/active_parking_service.dart';

import 'parking_test.dart' show ruleFixture;

void main() {
  test('CP23 exact HTTP endpoints, Bearer/correlation, decimal quote and forbidden ownership', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final seen = <String>[];
    final data = {
      'id': 's1',
      'userId': 'owner',
      'parkingSpaceId': 'space',
      'vehicleId': 'v',
      'tariffId': 't',
      'status': 'ACTIVE',
      'startedAt': '2026-10-05T15:00:00Z',
      'expectedEndAt': '2026-10-05T16:00:00Z',
    };
    final subscription = server.listen((r) async {
      seen.add('${r.method} ${r.uri.path}');
      expect(r.headers.value('Authorization'), 'Bearer fixture-access');
      expect(
        r.headers.value(correlationHeader),
        matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
      );
      r.response.headers.contentType = ContentType.json;
      if (r.uri.path.contains('forbidden')) {
        r.response.statusCode = 403;
        r.response.write('{}');
      } else if (r.uri.path.endsWith('/quote')) {
        expect(r.uri.queryParameters['additionalMinutes'], '15');
        r.response.write(jsonEncode(ruleFixture(15)));
      } else if (r.uri.path.endsWith('/mobile')) {
        final body = jsonDecode(await utf8.decoder.bind(r).join()) as Map;
        expect(body['expectedAmount'], '0.31');
        expect(body['idempotencyKey'], 'fixture-key');
        expect(body.containsKey('provider'), isFalse);
        r.response.statusCode = 201;
        r.response.write(
          jsonEncode({
            'parkingSessionId': 's1',
            'additionalMinutes': 15,
            'paymentId': 'payment-fixture',
          }),
        );
      } else if (r.uri.path.endsWith('/close')) {
        expect(r.method, 'POST');
        r.response.write(jsonEncode({...data, 'status': 'COMPLETED'}));
      } else if (r.uri.path.endsWith('/user/owner')) {
        r.response.write(jsonEncode([data]));
      } else {
        r.response.write(jsonEncode(data));
      }
      await r.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () async => {'Authorization': 'Bearer fixture-access'},
    );
    try {
      final service = ActiveParkingService(api);
      expect((await service.mine('owner')).single.owner, 'owner');
      expect((await service.session('s1')).status, 'ACTIVE');
      expect((await service.quote('s1', 15)).amount, '0.31');
      await service.extend('s1', {
        'additionalMinutes': 15,
        'paymentMethod': 'TEST',
        'idempotencyKey': 'fixture-key',
        'expectedAmount': '0.31',
        'expectedEndAt': '2026-10-05T16:15:00Z',
      });
      expect((await service.close('s1')).status, 'COMPLETED');
      await expectLater(
        service.session('forbidden'),
        throwsA(
          isA<AppFailure>().having(
            (e) => e.kind,
            'ownership',
            FailureKind.forbidden,
          ),
        ),
      );
      expect(
        seen,
        containsAll([
          'GET /api/v1/parking-sessions/s1',
          'POST /api/v1/parking-sessions/s1/close',
          'POST /api/v1/parking-sessions/s1/extensions/mobile',
        ]),
      );
    } finally {
      api.close();
      await subscription.cancel();
      await server.close(force: true);
    }
  });
}
