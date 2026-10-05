import 'dart:convert';
import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/parking/state/parking_controller.dart';

import 'parking_test.dart' as fixture;

void main() {
  test(
    '401 refresh retains the exact-money decoder on the single retry',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      var calls = 0;
      var refreshes = 0;
      server.listen((r) async {
        calls++;
        r.response.headers.contentType = ContentType.json;
        if (calls == 1) {
          r.response.statusCode = 401;
        } else {
          r.response.write(
            jsonEncode(fixture.ruleFixture(15)).replaceAll('"0.31"', '0.10'),
          );
        }
        await r.response.close();
      });
      final api = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        onUnauthorized: (_) async {
          refreshes++;
          return true;
        },
      );
      try {
        expect((await ParkingService(api).rules('s', 15)).amount, '0.10');
        expect(calls, 2);
        expect(refreshes, 1);
      } finally {
        api.close();
        await server.close(force: true);
      }
    },
  );
  test('Lost POST response is not retried; own-session GET recovers the durable intention', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final gate = Completer<void>();
    var posts = 0;
    Map<String, Object>? saved;
    server.listen((r) async {
      r.response.headers.contentType = ContentType.json;
      final path = r.uri.path;
      if (path.endsWith('/parking/rules')) {
        final minutes = int.tryParse(
          r.uri.queryParameters['durationMinutes'] ?? '',
        );
        r.response.write(jsonEncode(fixture.ruleFixture(minutes)));
      } else if (path.endsWith('/tariffs/code/TEST-T')) {
        r.response.write('{"id":"tariff","code":"TEST-T","active":true}');
      } else if (path.endsWith('/parking/sessions')) {
        posts++;
        final body = jsonDecode(await utf8.decoder.bind(r).join()) as Map;
        saved = {
          'id': 'receipt',
          'userId': 'fixture-citizen',
          'vehicleId': body['vehicleId'],
          'parkingSpaceId': 's',
          'tariffId': body['tariffId'],
          'status': 'PENDING_PAYMENT',
          'startedAt': '2026-10-04T15:00:00Z',
          'expectedEndAt': '2026-10-04T15:15:00Z',
        };
        await gate.future;
      } else if (path.endsWith('/parking-sessions/user/fixture-citizen')) {
        r.response.write(jsonEncode([saved]));
      } else {
        r.response.statusCode = 404;
      }
      try {
        await r.response.close();
      } catch (_) {
        /* Client deliberately timed out. */
      }
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      readTimeout: const Duration(milliseconds: 200),
    );
    final store = fixture.MemoryIntent();
    final c = fixture.controller(FakeHttpParking(api), store: store);
    try {
      await c.load();
      await c.create();
      expect(c.phase, ParkingPhase.uncertain);
      expect(store.value, isNotNull);
      await c.create();
      expect(posts, 1);
      await c.recover();
      expect(c.phase, ParkingPhase.created);
      expect(posts, 1);
      expect(store.value, isNull);
    } finally {
      if (!gate.isCompleted) gate.complete();
      c.dispose();
      api.close();
      await server.close(force: true);
    }
  });
  test('Real rules/code lookup/create contracts carry Bearer, correlation and backend tariff identity', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    int posts = 0;
    Map? body;
    String? idempotency;
    server.listen((r) async {
      expect(r.headers.value('Authorization'), 'Bearer fixture-access');
      expect(
        r.headers.value(correlationHeader),
        matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
      );
      r.response.headers.contentType = ContentType.json;
      final path = r.uri.path;
      if (path == '/api/v1/parking/rules') {
        expect(r.method, 'GET');
        expect(r.uri.queryParameters['spaceId'], 's');
        expect(r.uri.queryParameters.containsKey('at'), false);
        final minutes = int.tryParse(
          r.uri.queryParameters['durationMinutes'] ?? '',
        );
        r.response.write(
          jsonEncode(fixture.ruleFixture(minutes))
              .replaceAll('"0.31"', '0.31')
              .replaceAll('"1.23"', '1.23'),
        );
      } else if (path == '/api/v1/tariffs/code/TEST-T') {
        r.response.write(
          jsonEncode({'id': 'tariff', 'code': 'TEST-T', 'active': true}),
        );
      } else if (path == '/api/v1/parking/sessions') {
        posts++;
        body = jsonDecode(await utf8.decoder.bind(r).join()) as Map;
        idempotency = r.headers.value('Idempotency-Key');
        final intent = ParkingIntent(
          'fixture-citizen',
          's',
          body!['parkingSpaceQrCode'],
          body!['vehicleId'],
          body!['tariffId'],
          body!['durationMinutes'],
          key: idempotency,
        );
        r.response.statusCode = 201;
        r.response.write(
          jsonEncode({
            'id': 'receipt',
            'userId': intent.owner,
            'vehicleId': intent.vehicleId,
            'parkingSpaceId': intent.spaceId,
            'tariffId': intent.tariffId,
            'status': 'PENDING_PAYMENT',
            'startedAt': '2026-10-04T15:00:00Z',
            'expectedEndAt': '2026-10-04T15:15:00Z',
            'totalAmount': 0,
            'durationMinutes': 15,
          }),
        );
      } else {
        r.response.statusCode = 404;
      }
      await r.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () async => {'Authorization': 'Bearer fixture-access'},
    );
    final c = fixture.controller(FakeHttpParking(api));
    try {
      await c.load();
      await c.create();
      expect(c.phase, ParkingPhase.created);
      expect(posts, 1);
      expect(
        body!.keys,
        unorderedEquals([
          'parkingSpaceQrCode',
          'vehicleId',
          'tariffId',
          'durationMinutes',
        ]),
      );
      expect(body!['tariffId'], 'tariff');
      expect(idempotency, matches(RegExp(r'^[a-f0-9]{32}$')));
      expect(c.receipt!.status, 'PENDING_PAYMENT');
    } finally {
      c.dispose();
      api.close();
      await server.close(force: true);
    }
  });
  test('Rules preserve exact decimal lexemes; unsafe/invalid responses cannot become a quote', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var malformed = false;
    server.listen((r) async {
      r.response.headers.contentType = ContentType.json;
      final map = fixture.ruleFixture(15);
      if (malformed) map['spaceId'] = 'another';
      r.response.write(
        jsonEncode(map)
            .replaceAll('"0.31"', '9999999999.99')
            .replaceAll('"1.23"', '0.10'),
      );
      await r.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
    );
    try {
      final p = ParkingService(api);
      final quote = await p.rules('s', 15);
      expect(quote.amount, '9999999999.99');
      expect(quote.unitPrice, '0.10');
      malformed = true;
      await expectLater(p.rules('s', 15), throwsA(isA<AppFailure>()));
    } finally {
      api.close();
      await server.close(force: true);
    }
  });
  test('HTTP 409/403/5xx are sanitized without payment requests', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var status = 409;
    server.listen((r) async {
      r.response.statusCode = status;
      r.response.write('fixture-secret SQL');
      await r.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
    );
    try {
      for (final entry in {
        409: FailureKind.conflict,
        403: FailureKind.forbidden,
        500: FailureKind.unavailable,
      }.entries) {
        status = entry.key;
        await expectLater(
          ParkingService(api).rules('s'),
          throwsA(
            isA<AppFailure>()
                .having((e) => e.kind, 'kind', entry.value)
                .having(
                  (e) => e.message,
                  'safe message',
                  isNot(contains('fixture-secret')),
                ),
          ),
        );
      }
    } finally {
      api.close();
      await server.close(force: true);
    }
  });
}

class FakeHttpParking extends fixture.FakeParking {
  FakeHttpParking(ApiClient api) : real = ParkingService(api);
  final ParkingService real;
  @override
  Future<List<ParkingReceipt>> mine(String owner) => real.mine(owner);
  @override
  Future<ParkingRules> rules(String id, [int? minutes]) =>
      real.rules(id, minutes);
  @override
  Future<String> tariffId(String code) => real.tariffId(code);
  @override
  Future<ParkingReceipt> create(ParkingIntent intent) => real.create(intent);
}
