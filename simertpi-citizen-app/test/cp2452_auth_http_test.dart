import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

void main() {
  for (final status in [401, 403, 500, 503]) {
    test(
      'CP24.5.2 real HTTP $status sanitized login error and successful retry',
      () async {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        final api = ApiClient(
          baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
        );
        final auth = AuthController(
          AuthService(api),
          store: MemorySessionStore(),
        );
        var attempts = 0;
        server.listen((request) async {
          attempts++;
          await request.drain<void>();
          if (attempts == 1) {
            request.response.statusCode = status;
            request.response.write('INTERNAL_DETAILS');
          } else {
            request.response.write(jsonEncode(fixtureSession().toJson()));
          }
          await request.response.close();
        });
        try {
          expect(await auth.login('citizen', 'password'), false);
          expect(auth.message, switch (status) {
            401 => 'Usuario o contraseña incorrectos. Verifica tus datos',
            403 => 'No tienes autorización para realizar esta acción',
            _ => 'SIMERTPI no está disponible en este momento. Inténtalo nuevamente en unos minutos',
          });
          expect(auth.message, isNot(contains('INTERNAL_DETAILS')));
          expect(await auth.login('citizen', 'password'), true);
          expect(attempts, 2);
        } finally {
          auth.dispose();
          api.close();
          await server.close(force: true);
        }
      },
    );
  }
  test(
    'CP24.5.2 network failure has connection message and no fabricated session',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final port = server.port;
      await server.close(force: true);
      final api = ApiClient(
        baseUrl: Uri.parse('http://127.0.0.1:$port/api/v1'),
      );
      final auth = AuthController(
        AuthService(api),
        store: MemorySessionStore(),
      );
      try {
        expect(await auth.login('citizen', 'password'), false);
        expect(
          auth.message,
          'No tienes conexión a internet. Verifica tu conexión e inténtalo nuevamente',
        );
        expect(auth.isAuthenticated, false);
      } finally {
        auth.dispose();
        api.close();
      }
    },
  );
  test('CP24.5.2 lost refresh HTTP response is journaled and never replayed after restart', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final api = ApiClient(
      baseUrl: Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      readTimeout: const Duration(milliseconds: 100),
    );
    final store = MemorySessionStore();
    final auth = AuthController(AuthService(api), store: store);
    var refreshes = 0;
    server.listen((request) async {
      await request.drain<void>();
      if (request.uri.path.endsWith('/refresh')) {
        refreshes++;
        // Represents rotation having committed but its response being lost.
        await Future<void>.delayed(const Duration(milliseconds: 300));
      }
      try {
        request.response.write(
          jsonEncode(fixtureSession(access: 'C').toJson()),
        );
        await request.response.close();
      } catch (_) {
        /* Client aborted the timeout; no retry sent. */
      }
    });
    try {
      expect(await auth.login('citizen', 'password'), true);
      expect(await auth.renew(), false);
      expect(auth.isAuthenticated, true);
      expect(
        auth.message,
        contains('La conexión está tardando más de lo esperado'),
      );
      expect(store.session!.refreshBlocked, true);
      expect(await auth.renew(), false);
      final restored = AuthController(AuthService(api), store: store);
      await restored.restore();
      expect(await restored.renew(), false);
      expect(refreshes, 1);
      restored.dispose();
    } finally {
      auth.dispose();
      api.close();
      await server.close(force: true);
    }
  });
}
