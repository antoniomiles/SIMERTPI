import 'dart:io';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';

void main() {
  test(
    'PATCH shares Bearer refresh, headers and one retry after 401',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      var token = 'test-old', calls = 0, refreshes = 0;
      final client = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        headersProvider: () async => {'Authorization': 'Bearer $token'},
        onUnauthorized: (_) async {
          refreshes++;
          token = 'test-new';
          return true;
        },
      );
      addTearDown(() => server.close(force: true));
      addTearDown(client.close);
      server.listen((request) async {
        calls++;
        expect(request.method, 'PATCH');
        expect(request.headers.value(correlationHeader), isNotNull);
        expect(
          request.headers.value('Authorization'),
          'Bearer ${calls == 1 ? 'test-old' : 'test-new'}',
        );
        request.response.statusCode = calls == 1 ? 401 : 200;
        request.response.headers.contentType = ContentType.json;
        request.response.write(jsonEncode({'readAt': '2026-10-06T10:00:00Z'}));
        await request.response.close();
      });
      final result = await client.request(
        ApiMethod.patch,
        'notifications/inbox/test-id/read',
      );
      expect(result.statusCode, 200);
      expect(calls, 2);
      expect(refreshes, 1);
    },
  );
}
