import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'Deployment origins resolve the real API prefix in every environment',
    () {
      for (final environment in AppEnvironment.values) {
        for (final origin in [
          'https://backend.invalid',
          'https://backend.invalid/',
        ]) {
          final config = AppConfig.parse(
            environment: environment.name,
            apiBaseUrl: origin,
          );
          expect(
            config.apiBaseUrl.toString(),
            'https://backend.invalid/api/v1',
          );
        }
      }
    },
  );

  test('Existing API bases and reverse-proxy paths are preserved; TLS stays required', () {
    for (final path in ['/api/v1', '/api/v1/', '/proxy/api/v1']) {
      final url = 'https://backend.invalid$path';
      expect(
        AppConfig.parse(
          environment: 'dev',
          apiBaseUrl: url,
        ).apiBaseUrl.toString(),
        url,
      );
    }
    expect(
      () => AppConfig.parse(
        environment: 'prod',
        apiBaseUrl: 'http://backend.invalid',
      ),
      throwsFormatException,
    );
    expect(
      () => AppConfig.parse(
        environment: 'dev',
        apiBaseUrl: 'https://backend.invalid?token=secret',
      ),
      throwsFormatException,
    );
  });

  test('The old origin-only client calls /users, gets 401, and cannot create a session', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}'),
      client: _LocalHttpOverrides().createHttpClient(null),
    );
    addTearDown(() async {
      api.close();
      await server.close(force: true);
    });
    final paths = <String>[];
    server.listen((request) async {
      paths.add(request.uri.path);
      await request.drain<void>();
      request.response.statusCode = 401;
      await request.response.close();
    });
    try {
      await AuthService(api).register(
        const RegistrationRequest(
          username: 'fixture-citizen',
          email: 'test@example.invalid',
          password: 'fixture-password',
          firstName: 'Fixture',
          lastName: 'Citizen',
        ),
      );
      fail('The old origin path must be rejected');
    } on AppFailure catch (error) {
      expect(error.kind, FailureKind.unauthorized);
      expect(
        authErrorMessage(error),
        'No pudimos verificar tu usuario y contraseña.',
      );
      expect(
        authErrorMessage(error, registration: true),
        isNot(contains('usuario y contraseña')),
      );
    }
    expect(paths, ['/users']);
  });

  test('Origin configuration registers then manually logs in, stores tokens and restores session', () async {
    const channel = MethodChannel(
      'plugins.it_nomads.com/flutter_secure_storage',
    );
    final saved = <String, String>{};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          final key = args['key'] as String;
          switch (call.method) {
            case 'write':
              saved[key] = args['value'] as String;
              return null;
            case 'read':
              return saved[key];
            case 'delete':
              saved.remove(key);
              return null;
            default:
              throw PlatformException(code: 'unsupported');
          }
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final config = AppConfig.parse(
      environment: 'dev',
      apiBaseUrl: 'http://127.0.0.1:${server.port}',
    );
    late AuthController auth;
    final api = ApiClient(
      baseUrl: config.apiBaseUrl!,
      client: _LocalHttpOverrides().createHttpClient(null),
      headersProvider: () => auth.headers(),
    );
    final store = SecureSessionStore('dev:cp21-5-local-http');
    auth = AuthController(AuthService(api), store: store);
    addTearDown(() async {
      auth.dispose();
      api.close();
      await server.close(force: true);
    });
    final paths = <String>[];
    server.listen((request) async {
      paths.add(request.uri.path);
      expect(
        request.headers.value(correlationHeader),
        matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
      );
      request.response.headers.contentType = ContentType.json;
      if (request.uri.path == '/api/v1/users') {
        expect(request.method, 'POST');
        expect(request.headers.value('Authorization'), isNull);
        expect(request.headers.contentType?.mimeType, 'application/json');
        expect(jsonDecode(await utf8.decoder.bind(request).join()), {
          'username': 'fixture-citizen',
          'email': 'test@example.invalid',
          'password': 'fixture-password',
          'firstName': 'Fixture',
          'lastName': 'Citizen',
          'phone': null,
        });
        request.response.statusCode = 201;
        request.response.write(
          '{"id":"fixture-citizen","username":"fixture-citizen","enabled":true}',
        );
      } else if (request.uri.path == '/api/v1/auth/login') {
        expect(request.headers.value('Authorization'), isNull);
        expect(jsonDecode(await utf8.decoder.bind(request).join()), {
          'username': 'fixture-citizen',
          'password': 'fixture-password',
        });
        request.response.write(jsonEncode(fixtureSession().toJson()));
      } else if (request.uri.path == '/api/v1/notifications/preferences') {
        expect(request.headers.value('Authorization'), 'Bearer ${'A' * 43}');
        request.response.write('[]');
      } else {
        request.response.statusCode = 404;
      }
      await request.response.close();
    });
    await auth.gateway.register(
      const RegistrationRequest(
        username: 'fixture-citizen',
        email: 'test@example.invalid',
        password: 'fixture-password',
        firstName: 'Fixture',
        lastName: 'Citizen',
      ),
    );
    expect(paths, ['/api/v1/users']);
    expect(auth.isAuthenticated, false);
    expect(saved, isEmpty); // Registration never silently creates a session.
    expect(await auth.login('fixture-citizen', 'fixture-password'), true);
    expect(saved.values.single, isNot(contains('fixture-password')));
    expect((await store.read())!.refreshToken, 'R' * 43);
    auth.dispose();
    auth = AuthController(AuthService(api), store: store);
    await auth.restore();
    expect(auth.isAuthenticated, true);
    await api.request(ApiMethod.get, 'notifications/preferences');
    expect(paths, [
      '/api/v1/users',
      '/api/v1/auth/login',
      '/api/v1/notifications/preferences',
    ]);
  });
}

// Use actual loopback HTTP alongside the secure-storage platform test binding.
class _LocalHttpOverrides extends HttpOverrides {}
