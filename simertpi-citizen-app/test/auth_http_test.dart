import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

void main() {
  late HttpServer server;
  late ApiClient api;
  late AuthController auth;
  setUp(() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () => auth.headers(),
      onUnauthorized: (value) => auth.expireIfMatches(value),
    );
    auth = AuthController(AuthService(api));
  });
  tearDown(() async {
    api.close();
    auth.dispose();
    await server.close(force: true);
  });
  test('Basic login uses real citizen-only read with correlation; later requests use same client', () async {
    final paths = <String>[];
    final headers = <String?>[];
    server.listen((request) async {
      paths.add(request.uri.path);
      headers.add(request.headers.value('Authorization'));
      expect(request.method, 'GET');
      expect(
        request.headers.value(correlationHeader),
        matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
      );
      request.response.write('[{"channel":"PUSH","enabled":false}]');
      await request.response.close();
    });
    expect(await auth.login('fixture-citizen', 'fixture-password'), true);
    await api.request(ApiMethod.get, 'notifications/preferences');
    expect(paths, [
      '/api/v1/notifications/preferences',
      '/api/v1/notifications/preferences',
    ]);
    expect(headers.toSet(), {
      basicAuthorization('fixture-citizen', 'fixture-password'),
    });
    auth.logout();
    await api.request(ApiMethod.get, 'notifications/preferences');
    expect(headers.last, isNull);
  });
  test('Registration matches CreateUserRequest, sends no auth and does not sign in', () async {
    Map<String, dynamic>? received;
    server.listen((request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/api/v1/users');
      expect(request.headers.value('Authorization'), isNull);
      expect(request.headers.contentType?.mimeType, 'application/json');
      expect(request.headers.value(correlationHeader), isNotNull);
      received = jsonDecode(
        await utf8.decoder.bind(request).join(),
      ) as Map<String, dynamic>;
      request.response.statusCode = 201;
      request.response.write(
        '{"id":"fixture-id","username":"citizen","enabled":true}',
      );
      await request.response.close();
    });
    await auth.gateway.register(
      const RegistrationRequest(
        username: 'citizen',
        email: 'test@example.invalid',
        password: 'fixture-password',
        firstName: 'Fixture',
        lastName: 'Citizen',
      ),
    );
    expect(received, {
      'username': 'citizen',
      'email': 'test@example.invalid',
      'password': 'fixture-password',
      'firstName': 'Fixture',
      'lastName': 'Citizen',
      'phone': null,
    });
    expect(auth.isAuthenticated, false);
  });
  test(
    'HTTP 401, 403, 400 and 409 are sanitized and mapped distinctly',
    () async {
      var status = 401;
      server.listen((request) async {
        request.response.statusCode = status;
        request.response.write('FIXTURE_SECRET SQL raw payload');
        await request.response.close();
      });
      for (final entry in {
        401: FailureKind.unauthorized,
        403: FailureKind.forbidden,
        400: FailureKind.invalidRequest,
        409: FailureKind.conflict,
      }.entries) {
        status = entry.key;
        try {
          await api.request(ApiMethod.get, 'notifications/preferences');
          fail('Expected error');
        } on AppFailure catch (error) {
          expect(error.kind, entry.value);
          expect(error.message, isNot(contains('FIXTURE_SECRET')));
        }
      }
    },
  );
  test('Malformed successful verification cannot authenticate', () async {
    server.listen((request) async {
      request.response.write('{"token":"not-a-token"}');
      await request.response.close();
    });
    expect(await auth.login('citizen', 'fixture-password'), false);
    expect(await auth.headers(), isEmpty);
  });
  test('Authenticated 401 clears credentials; a rejected login never expires another session', () async {
    var status = 200;
    server.listen((request) async {
      request.response.statusCode = status;
      request.response.write('[]');
      await request.response.close();
    });
    expect(await auth.login('citizen', 'fixture-password'), true);
    status = 401;
    await expectLater(
      api.request(ApiMethod.get, 'notifications/preferences'),
      throwsA(isA<AppFailure>()),
    );
    expect(auth.isAuthenticated, false);
    expect(auth.message, contains('Ingresa nuevamente'));
  });
  test(
    'Unconfigured backend fails closed, never inventing a login response',
    () async {
      final service = AuthService(null);
      await expectLater(
        service.verifyCitizen('citizen', 'fixture-password'),
        throwsA(
          isA<AppFailure>().having(
            (e) => e.kind,
            'kind',
            FailureKind.unavailable,
          ),
        ),
      );
    },
  );
}
