import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';

void main() {
  test('Real catalog and encoded QR/code paths use Bearer/correlation; refreshed hierarchy wins', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final paths = <String>[];
    var inactive = false;
    final dto = {
      'id': 's',
      'streetId': 't',
      'code': 'CODE+TEST',
      'qrCode': 'QR+TEST',
      'spaceNumber': '1',
      'active': true,
      'latitude': -3.0,
      'longitude': -79.0,
    };
    server.listen((request) async {
      expect(request.method, 'GET');
      expect(request.headers.value('Authorization'), 'Bearer fixture-token');
      expect(
        request.headers.value(correlationHeader),
        matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
      );
      final decodedPath = '/${request.uri.pathSegments.join('/')}';
      paths.add(decodedPath);
      final body = switch (decodedPath) {
        '/api/v1/zones' => [
          {'id': 'z', 'code': 'Z', 'name': 'Zone fixture', 'active': true},
        ],
        '/api/v1/streets' => [
          {'id': 't', 'zoneId': 'z', 'name': 'Street fixture', 'active': true},
        ],
        '/api/v1/parking-spaces' => [
          {...dto, 'active': !inactive},
        ],
        '/api/v1/parking-spaces/qr/QR+TEST' ||
        '/api/v1/parking-spaces/code/CODE+TEST' => dto,
        _ => null,
      };
      if (body == null) request.response.statusCode = 404;
      request.response.write(jsonEncode(body));
      await request.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () async => {'Authorization': 'Bearer fixture-token'},
    );
    final service = ParkingCatalogService(api);
    try {
      expect((await service.load()).spaces.single.hasCoordinates, true);
      expect((await service.identify('QR+TEST', qr: true)).selectable, true);
      expect(paths, contains('/api/v1/parking-spaces/qr/QR+TEST'));
      inactive = true;
      expect(
        (await service.identify('CODE+TEST', qr: false)).selectable,
        false,
      );
      final before = paths.length;
      await expectLater(
        service.identify('https://malicious.invalid/', qr: true),
        throwsA(isA<AppFailure>()),
      );
      expect(paths.length, before);
      await expectLater(
        service.identify('MISSING', qr: true),
        throwsA(
          isA<AppFailure>().having((e) => e.kind, 'kind', FailureKind.rejected),
        ),
      );
    } finally {
      api.close();
      await server.close(force: true);
    }
  });
  test(
    'Catalog HTTP 401 retries once with renewed Bearer; errors stay sanitized',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      var token = 'old', renewals = 0, zoneRequests = 0, status = 200;
      server.listen((request) async {
        if (status != 200) {
          request.response.statusCode = status;
          request.response.write('FIXTURE_SECRET');
        } else if (request.uri.path.endsWith('/zones') && zoneRequests++ == 0) {
          request.response.statusCode = 401;
        } else {
          request.response.write('[]');
        }
        await request.response.close();
      });
      final api = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        headersProvider: () async => {'Authorization': 'Bearer $token'},
        onUnauthorized: (h) async {
          renewals++;
          token = 'new';
          return true;
        },
      );
      final service = ParkingCatalogService(api);
      try {
        expect((await service.load()).spaces, isEmpty);
        expect(renewals, 1);
        for (final entry in {
          403: FailureKind.forbidden,
          500: FailureKind.unavailable,
        }.entries) {
          status = entry.key;
          await expectLater(
            service.identify('QR-TEST', qr: true),
            throwsA(
              isA<AppFailure>().having((e) => e.kind, 'kind', entry.value),
            ),
          );
        }
      } finally {
        api.close();
        await server.close(force: true);
      }
    },
  );
  test('Invalid successful DTO cannot create a trusted space', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      request.response.write('{"id":"untrusted"}');
      await request.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
    );
    try {
      await expectLater(
        ParkingCatalogService(api).identify('QR-TEST', qr: true),
        throwsA(isA<AppFailure>()),
      );
    } finally {
      api.close();
      await server.close(force: true);
    }
  });
}
