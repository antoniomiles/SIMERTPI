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
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      expect(auth.phase, AuthPhase.initial);
      await auth.restore();
      expect(auth.phase, AuthPhase.signedOut);
      expect(await auth.headers(), isEmpty);
      await auth.restore();
      expect(auth.phase, AuthPhase.signedOut);
      auth.dispose();
    },
  );
  test('logout callback runs once for repeated logout requests', () async {
    var callbacks = 0;
    final auth = AuthController(
      FakeAuthGateway(),
      store: MemorySessionStore(),
      onBeforeLogout: ({required remote, required ownerId}) async {
        callbacks++;
        expect(ownerId, anyOf(isNull, 'fixture-citizen'));
      },
    );
    await auth.login('citizen', 'fixture-password');
    expect(callbacks, 1, reason: 'login first clears any stale native owner');
    await Future.wait([auth.logout(), auth.logout()]);
    expect(
      callbacks,
      2,
      reason: 'one additional callback for the coalesced logout',
    );
    auth.dispose();
  });
  test(
    'account switch closes prior push authorization before logging in again',
    () async {
      var callbacks = 0;
      final gateway = FakeAuthGateway();
      final auth = AuthController(
        gateway,
        store: MemorySessionStore(),
        onBeforeLogout: ({required remote, required ownerId}) async {
          callbacks++;
          expect(remote, false);
          expect(ownerId, anyOf(isNull, 'fixture-citizen'));
        },
      );
      expect(await auth.login('citizen-a', 'fixture-password'), true);
      expect(await auth.login('citizen-b', 'fixture-password'), true);
      expect(callbacks, 2);
      expect(gateway.calls, 2);
      expect(auth.isAuthenticated, true);
      await auth.logout();
      expect(callbacks, 3);
      auth.dispose();
    },
  );
  test(
    'failed native gate invalidation blocks logout and account switch',
    () async {
      var failClose = false;
      final gateway = FakeAuthGateway();
      final auth = AuthController(
        gateway,
        store: MemorySessionStore(),
        onBeforeLogout: ({required remote, required ownerId}) async {
          if (failClose) throw StateError('native gate persistence failed');
        },
      );
      expect(await auth.login('citizen-a', 'fixture-password'), true);
      failClose = true;
      await auth.logout(remote: false);
      expect(auth.isAuthenticated, true);
      expect(auth.userId, 'fixture-citizen');
      expect(await auth.login('citizen-b', 'fixture-password'), false);
      expect(gateway.calls, 1);
      expect(auth.isAuthenticated, true);
      failClose = false;
      expect(await auth.login('citizen-b', 'fixture-password'), true);
      expect(gateway.calls, 2);
      await auth.logout();
      auth.dispose();
    },
  );
  test('Blank input is rejected without HTTP', () async {
    final fake = FakeAuthGateway();
    final auth = AuthController(fake, store: MemorySessionStore());
    expect(await auth.login('', 'x'), false);
    expect(await auth.login('citizen', '   '), false);
    expect(await auth.login('', 'x'), false);
    expect(fake.calls, 0);
    auth.dispose();
  });
  test('Processing deduplicates login and success supplies Bearer without password', () async {
    final fake = FakeAuthGateway()..pending = Completer<void>();
    final auth = AuthController(fake, store: MemorySessionStore());
    final first = auth.login('citizen', 'fixture-password');
    expect(auth.phase, AuthPhase.processing);
    expect(await auth.login('citizen', 'fixture-password'), false);
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(fake.calls, 1);
    fake.pending!.complete();
    expect(await first, true);
    expect(auth.phase, AuthPhase.authenticated);
    expect(await auth.headers(), {'Authorization': 'Bearer ${'A' * 43}'});
    await auth.logout();
    expect(auth.isAuthenticated, false);
    expect(await auth.headers(), isEmpty);
    auth.dispose();
  });
  test('Logout/disposal during login cannot restore a late session', () async {
    for (final disposed in [false, true]) {
      final fake = FakeAuthGateway()..pending = Completer<void>();
      final auth = AuthController(fake, store: MemorySessionStore());
      final result = auth.login('citizen', 'fixture-password');
      if (disposed) {
        auth.dispose();
      } else {
        await auth.logout();
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
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await auth.login('citizen', 'first-password');
      final first = (await auth.headers())['Authorization'];
      await auth.logout();
      await auth.login('citizen', 'second-password');
      await auth.recoverUnauthorized(first);
      expect(auth.isAuthenticated, true);
      (auth.gateway as FakeAuthGateway).failure = const AppFailure(
        FailureKind.unauthorized,
      );
      await auth.recoverUnauthorized((await auth.headers())['Authorization']);
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
        final auth = AuthController(fake, store: MemorySessionStore());
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
