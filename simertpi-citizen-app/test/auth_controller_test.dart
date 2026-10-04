import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

void main() {
  test(
    'Initial/restart never restores fabricated credentials or expiry',
    () async {
      final auth = AuthController(FakeAuthGateway());
      expect(auth.phase, AuthPhase.initial);
      await auth.restore();
      expect(auth.phase, AuthPhase.signedOut);
      expect(await auth.headers(), isEmpty);
      await auth.restore();
      expect(auth.phase, AuthPhase.signedOut);
      auth.dispose();
    },
  );
  test('Blank input and Basic delimiter are rejected without HTTP', () async {
    final fake = FakeAuthGateway();
    final auth = AuthController(fake);
    expect(await auth.login('', 'x'), false);
    expect(await auth.login('citizen', '   '), false);
    expect(await auth.login('bad:name', 'x'), false);
    expect(fake.calls, 0);
    auth.dispose();
  });
  test(
    'Processing deduplicates login and success supplies only in-memory Basic',
    () async {
      final fake = FakeAuthGateway()..pending = Completer<void>();
      final auth = AuthController(fake);
      final first = auth.login('citizen', 'fixture-password');
      expect(auth.phase, AuthPhase.processing);
      expect(await auth.login('citizen', 'fixture-password'), false);
      expect(fake.calls, 1);
      fake.pending!.complete();
      expect(await first, true);
      expect(auth.phase, AuthPhase.authenticated);
      expect(await auth.headers(), {
        'Authorization': basicAuthorization('citizen', 'fixture-password'),
      });
      auth.logout();
      expect(auth.isAuthenticated, false);
      expect(await auth.headers(), isEmpty);
      auth.dispose();
    },
  );
  test('Logout/disposal during login cannot restore a late session', () async {
    for (final disposed in [false, true]) {
      final fake = FakeAuthGateway()..pending = Completer<void>();
      final auth = AuthController(fake);
      final result = auth.login('citizen', 'fixture-password');
      if (disposed) {
        auth.dispose();
      } else {
        auth.logout();
      }
      fake.pending!.complete();
      expect(await result, false);
      expect(await auth.headers(), isEmpty);
      if (!disposed) auth.dispose();
    }
  });
  test(
    'Revocation invalidates current session but not an older request session',
    () async {
      final auth = AuthController(FakeAuthGateway());
      await auth.login('citizen', 'first-password');
      final first = (await auth.headers())['Authorization'];
      auth.logout();
      await auth.login('citizen', 'second-password');
      auth.expireIfMatches(first);
      expect(auth.isAuthenticated, true);
      auth.expireIfMatches((await auth.headers())['Authorization']);
      expect(auth.phase, AuthPhase.signedOut);
      expect(auth.message, contains('Ingresa nuevamente'));
      expect(await auth.headers(), isEmpty);
      auth.dispose();
    },
  );
  test(
    'Authentication errors distinguish credentials, access and connectivity',
    () async {
      for (final kind in [
        FailureKind.unauthorized,
        FailureKind.forbidden,
        FailureKind.offline,
        FailureKind.timeout,
        FailureKind.unavailable,
      ]) {
        final fake = FakeAuthGateway()..failure = AppFailure(kind);
        final auth = AuthController(fake);
        expect(await auth.login('citizen', 'fixture-password'), false);
        expect(auth.phase, AuthPhase.error);
        expect(await auth.headers(), isEmpty);
        if (kind == FailureKind.unauthorized) {
          expect(auth.message, contains('usuario y contraseña'));
        } else {
          expect(auth.message, isNot(contains('usuario y contraseña')));
        }
        auth.dispose();
      }
      expect(
        authErrorMessage(Exception('FIXTURE_SECRET')),
        isNot(contains('FIXTURE_SECRET')),
      );
      expect(
        authErrorMessage(
          const AppFailure(FailureKind.unknown, outcomeUnknown: true),
          registration: true,
        ),
        contains('Intenta ingresar antes'),
      );
      expect(
        const RegistrationRequest(
          username: 'x',
          email: 'x@y.z',
          password: 'FIXTURE_SECRET',
          firstName: 'X',
          lastName: 'Y',
        ).toString(),
        isNot(contains('FIXTURE_SECRET')),
      );
    },
  );
}
