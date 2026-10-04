import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

void main() {
  late HttpServer server;
  late ApiClient api;
  late AuthController auth;
  setUp(() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      headersProvider: () => auth.headers(),
      onUnauthorized: (value) => auth.recoverUnauthorized(value),
      onSessionRejected: () => auth.logout(expired: true, remote: false),
    );
    auth = AuthController(AuthService(api), store: MemorySessionStore());
  });
  tearDown(() async {
    api.close();
    auth.dispose();
    await server.close(force: true);
  });
  test(
    'Login DTO is real and protected requests use Bearer and correlation',
    () async {
      final paths = <String>[];
      final authorization = <String?>[];
      server.listen((request) async {
        paths.add(request.uri.path);
        authorization.add(request.headers.value('Authorization'));
        expect(
          request.headers.value(correlationHeader),
          matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
        );
        if (request.uri.path.endsWith('/login')) {
          expect(request.method, 'POST');
          expect(jsonDecode(await utf8.decoder.bind(request).join()), {
            'username': 'fixture-citizen',
            'password': 'fixture-password',
          });
          request.response.write(jsonEncode(fixtureSession().toJson()));
        } else {
          request.response.write('[]');
        }
        await request.response.close();
      });
      expect(await auth.login('fixture-citizen', 'fixture-password'), true);
      await api.request(ApiMethod.get, 'notifications/preferences');
      expect(paths, [
        '/api/v1/auth/login',
        '/api/v1/notifications/preferences',
      ]);
      expect(authorization, [null, 'Bearer ${'A' * 43}']);
      await auth.logout();
      expect(auth.isAuthenticated, false);
    },
  );
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
      request.response.write(
        status == 200 ? jsonEncode(fixtureSession().toJson()) : '{}',
      );
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
        service.login('citizen', 'fixture-password'),
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
  test(
    'Concurrent HTTP 401 renew once and retry with the rotated Bearer',
    () async {
      var refreshes = 0;
      final seen = <String>[];
      server.listen((request) async {
        if (request.uri.path.endsWith('/login')) {
          request.response.write(jsonEncode(fixtureSession().toJson()));
        } else if (request.uri.path.endsWith('/refresh')) {
          refreshes++;
          expect(request.headers.value('Authorization'), isNull);
          expect(jsonDecode(await utf8.decoder.bind(request).join()), {
            'refreshToken': 'R' * 43,
          });
          request.response.write(
            jsonEncode(fixtureSession(access: 'C').toJson()),
          );
        } else {
          final header = request.headers.value('Authorization')!;
          seen.add(header);
          request.response.statusCode = header == 'Bearer ${'C' * 43}'
              ? 200
              : 401;
          request.response.write('[]');
        }
        await request.response.close();
      });
      await auth.login('citizen', 'fixture-password');
      await Future.wait([
        api.request(ApiMethod.get, 'notifications/preferences'),
        api.request(ApiMethod.get, 'notifications/preferences'),
      ]);
      expect(refreshes, 1);
      expect(seen.where((h) => h == 'Bearer ${'C' * 43}').length, 2);
      expect(auth.isAuthenticated, true);
    },
  );
  test(
    'A second authenticated 401 closes session without a refresh loop',
    () async {
      var refreshes = 0;
      server.listen((request) async {
        if (request.uri.path.endsWith('/login')) {
          request.response.write(jsonEncode(fixtureSession().toJson()));
        } else if (request.uri.path.endsWith('/refresh')) {
          refreshes++;
          request.response.write(
            jsonEncode(fixtureSession(access: 'C').toJson()),
          );
        } else {
          request.response.statusCode = 401;
        }
        await request.response.close();
      });
      await auth.login('citizen', 'fixture-password');
      await expectLater(
        api.request(ApiMethod.get, 'notifications/preferences'),
        throwsA(isA<AppFailure>()),
      );
      expect(refreshes, 1);
      expect(auth.isAuthenticated, false);
      expect(await auth.store.read(), isNull);
    },
  );
}
