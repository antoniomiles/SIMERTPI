import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

void main() {
  test(
    'Only tokens persist; valid session restores without password',
    () async {
      final store = MemorySessionStore();
      final fake = FakeAuthGateway();
      final first = AuthController(fake, store: store);
      await first.login('citizen', 'password-not-persisted');
      expect(store.session!.toJson().keys, isNot(contains('password')));
      first.dispose();
      final next = AuthController(fake, store: store);
      await next.restore();
      expect(next.isAuthenticated, true);
      expect(fake.calls, 1);
      expect(fake.refreshes, 0);
      next.dispose();
    },
  );
  test('Expired access restores with rotating refresh', () async {
    final store = MemorySessionStore()
      ..session = fixtureSession(
        accessExpiry: DateTime.now().subtract(const Duration(seconds: 1)),
      );
    final fake = FakeAuthGateway();
    final auth = AuthController(fake, store: store);
    await auth.restore();
    expect(fake.refreshes, 1);
    expect(store.session!.accessToken, 'C' * 43);
    expect(auth.isAuthenticated, true);
    auth.dispose();
  });
  test('Expired refresh clears storage without network', () async {
    final store = MemorySessionStore()
      ..session = fixtureSession(
        accessExpiry: DateTime.now().subtract(const Duration(days: 2)),
        refreshExpiry: DateTime.now().subtract(const Duration(days: 1)),
      );
    final fake = FakeAuthGateway();
    final auth = AuthController(fake, store: store);
    await auth.restore();
    expect(auth.isAuthenticated, false);
    expect(store.session, isNull);
    expect(fake.refreshes, 0);
    auth.dispose();
  });
  test(
    'Concurrent 401 shares refresh; stale 401 does not rotate again',
    () async {
      final fake = FakeAuthGateway();
      final auth = AuthController(fake, store: MemorySessionStore());
      await auth.login('citizen', 'fixture');
      final header = (await auth.headers())['Authorization'];
      fake.pending = Completer<void>();
      final a = auth.recoverUnauthorized(header);
      final b = auth.recoverUnauthorized(header);
      expect(fake.refreshes, 1);
      fake.pending!.complete();
      expect(await a, true);
      expect(await b, true);
      await auth.recoverUnauthorized(header);
      expect(fake.refreshes, 1);
      auth.dispose();
    },
  );
  test(
    'Uncertain refresh clears session instead of replaying consumed token',
    () async {
      final fake = FakeAuthGateway();
      final store = MemorySessionStore();
      final auth = AuthController(fake, store: store);
      await auth.login('citizen', 'fixture');
      fake.failure = const AppFailure(
        FailureKind.timeout,
        outcomeUnknown: true,
      );
      expect(await auth.renew(), false);
      expect(store.session, isNull);
      expect(auth.isAuthenticated, false);
      auth.dispose();
    },
  );
  test('Logout revokes remote session and clears persisted tokens', () async {
    final fake = FakeAuthGateway();
    final store = MemorySessionStore();
    final auth = AuthController(fake, store: store);
    await auth.login('citizen', 'fixture');
    await auth.logout();
    expect(fake.logouts, 1);
    expect(store.session, isNull);
    expect(await auth.headers(), isEmpty);
    auth.dispose();
  });
}
