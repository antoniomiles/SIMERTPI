import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/active_parking/data/active_parking_service.dart';

import 'parking_test.dart' as fixture;

void main() {
  test('Batch initial and extension quotes retain exact money, Bearer and correlation', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final paths = <String>[];
    server.listen((r) async {
      paths.add(r.uri.path);
      expect(r.method, 'GET');
      expect(r.headers.value('Authorization'), 'Bearer fixture-access');
      expect(r.headers.value(correlationHeader), isNotEmpty);
      if (r.uri.path.endsWith('/parking/rules/options')) {
        expect(r.uri.queryParameters['spaceId'], 's');
      }
      r.response.headers.contentType = ContentType.json;
      r.response.write(
        jsonEncode([fixture.ruleFixture(15)]).replaceAll('"0.31"', '0.10'),
      );
      await r.response.close();
    });
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () async => {'Authorization': 'Bearer fixture-access'},
    );
    try {
      expect((await ParkingService(api).options('s')).single.amount, '0.10');
      expect(
        (await ActiveParkingService(api).options('receipt')).single.amount,
        '0.10',
      );
      expect(paths, [
        '/api/v1/parking/rules/options',
        '/api/v1/parking-sessions/receipt/extensions/options',
      ]);
    } finally {
      api.close();
      await server.close(force: true);
    }
  });
}
