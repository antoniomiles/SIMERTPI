import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/vehicles/data/vehicle_service.dart';

void main() {
  test(
    'HTTP contracts use authenticated owner and exact fields with correlation',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final api = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        headersProvider: () async => {'Authorization': 'Bearer fixture-token'},
      );
      final dto = {
        'id': 'fixture-id',
        'userId': 'fixture-owner',
        'plate': 'Test-123',
        'brand': null,
        'model': null,
        'color': null,
        'active': true,
        'createdAt': '2026-01-01T00:00:00Z',
        'updatedAt': '2026-01-01T00:00:00Z',
      };
      server.listen((request) async {
        expect(request.headers.value('Authorization'), 'Bearer fixture-token');
        expect(
          request.headers.value(correlationHeader),
          matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
        );
        if (request.method == 'GET') {
          expect(request.uri.path, '/api/v1/vehicles/user/fixture-owner');
          request.response.write(jsonEncode([dto]));
        } else {
          expect(request.uri.path, '/api/v1/vehicles');
          expect(jsonDecode(await utf8.decoder.bind(request).join()), {
            'userId': 'fixture-owner',
            'plate': 'Test-123',
            'brand': null,
            'model': null,
            'color': null,
          });
          request.response.statusCode = 201;
          request.response.write(jsonEncode(dto));
        }
        await request.response.close();
      });
      final service = VehicleService(api, 'fixture-owner');
      expect((await service.list()).single.plate, 'Test-123');
      expect(
        (await service.create(const VehicleInput('Test-123'))).active,
        true,
      );
      api.close();
      await server.close(force: true);
    },
  );
}
