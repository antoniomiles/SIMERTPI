import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';

void main() {
  late HttpServer server;
  late ApiClient client;
  setUp(() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    client = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
    );
  });
  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  test(
    'HTTP boundary generates correlation and preserves backend correlation',
    () async {
      String? incoming;
      String? path;
      server.listen((request) async {
        incoming = request.headers.value(correlationHeader);
        path = request.uri.path;
        request.response.headers.set(
          correlationHeader,
          'test-backend-correlation',
        );
        request.response.headers.contentType = ContentType.json;
        request.response.write(jsonEncode({'example': true}));
        await request.response.close();
      });
      final response = await client.request(ApiMethod.get, 'fixture');
      expect(incoming, matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')));
      expect(path, '/api/v1/fixture');
      expect(response.correlationId, 'test-backend-correlation');
      expect(response.body, {'example': true});
    },
  );

  test(
    'Valid caller correlation is retained and invalid input replaced',
    () async {
      final seen = <String?>[];
      server.listen((request) async {
        seen.add(request.headers.value(correlationHeader));
        request.response.write('{}');
        await request.response.close();
      });
      await client.request(
        ApiMethod.get,
        'fixture',
        correlationId: 'cp17.test:1',
      );
      await client.request(
        ApiMethod.get,
        'fixture',
        correlationId: 'unsafe\nvalue',
      );
      expect(seen.first, 'cp17.test:1');
      expect(seen.last, isNot('unsafe\nvalue'));
      expect(seen.last, matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')));
    },
  );

  test(
    'Backend error is classified without exposing payload secrets',
    () async {
      server.listen((request) async {
        request.response.statusCode = 503;
        request.response.write('FIXTURE_SECRET raw SQL stacktrace');
        await request.response.close();
      });
      try {
        await client.request(ApiMethod.get, 'fixture');
        fail('Expected sanitized failure');
      } on AppFailure catch (failure) {
        expect(failure.kind, FailureKind.unavailable);
        expect(failure.message, isNot(contains('FIXTURE_SECRET')));
        expect(failure.toString(), isNot(contains('SQL')));
      }
    },
  );

  test(
    'Timeout on a write preserves uncertain outcome without automatic retry',
    () async {
      var requests = 0;
      server.listen((request) {
        requests++;
      });
      client.close();
      client = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:${server.port}'),
        readTimeout: const Duration(milliseconds: 30),
      );
      try {
        await client.request(
          ApiMethod.post,
          'fixture',
          body: {'example': true},
        );
        fail('Expected timeout');
      } on AppFailure catch (failure) {
        expect(failure.kind, FailureKind.timeout);
        expect(failure.outcomeUnknown, true);
        expect(requests, 1);
      }
    },
  );

  test('Absolute URLs, traversal and redirects cannot send future authentication elsewhere', () async {
    await expectLater(
      client.request(ApiMethod.get, 'https://other.invalid'),
      throwsArgumentError,
    );
    await expectLater(
      client.request(ApiMethod.get, '../fixture'),
      throwsArgumentError,
    );
    var requests = 0;
    server.listen((request) async {
      requests++;
      request.response.statusCode = 302;
      request.response.headers.set('location', '/redirected');
      await request.response.close();
    });
    await expectLater(
      client.request(ApiMethod.get, 'fixture'),
      throwsA(isA<AppFailure>()),
    );
    expect(requests, 1);
  });
}
