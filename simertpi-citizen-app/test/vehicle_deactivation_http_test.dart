import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/vehicles/data/vehicle_service.dart';

void main() {
  test('Deactivation HTTP uses PUT, owned ID, Bearer and correlation; errors propagate', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () async => {'Authorization': 'Bearer fixture-token'},
    );
    int calls = 0;
    server.listen((request) async {
      expect(request.method, 'PUT');
      expect(request.uri.path, '/api/v1/vehicles/fixture-id/deactivation');
      expect(request.headers.value('Authorization'), 'Bearer fixture-token');
      expect(request.headers.value(correlationHeader), isNotEmpty);
      calls++;
      request.response.statusCode = calls == 1 ? 200 : 403;
      request.response.write(
        jsonEncode(
          calls == 1
              ? {
                  'id': 'fixture-id',
                  'userId': 'owner',
                  'plate': 'TBE1234',
                  'active': false,
                }
              : {'message': 'Forbidden'},
        ),
      );
      await request.response.close();
    });
    final service = VehicleService(api, 'owner');
    await service.deactivate('fixture-id');
    await expectLater(
      service.deactivate('fixture-id'),
      throwsA(isA<AppFailure>()),
    );
    api.close();
    await server.close(force: true);
  });
}
