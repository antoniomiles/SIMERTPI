import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/features/home/home_greeting_controller.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';

import 'cp192_home_support.dart';

class OwnerGateway extends FakeAuthGateway {
  String owner = 'fixture-citizen';
  @override
  Future<MobileSession> login(String username, String password) async {
    final session = await super.login(username, password);
    return MobileSession(
      userId: owner,
      accessToken: session.accessToken,
      refreshToken: session.refreshToken,
      accessExpiresAt: session.accessExpiresAt,
      refreshExpiresAt: session.refreshExpiresAt,
    );
  }
}

void main() {
  test('delayed name publishes once and successful reads are cached', () async {
    final gate = Completer<Map<String, dynamic>>();
    int reads = 0;
    final c = HomeGreetingController(
      read: () {
        reads++;
        return gate.future;
      },
      isCurrentSession: () => true,
    );
    final loading = c.load();
    await c.load();
    expect(c.state, GreetingState.loading);
    expect(reads, 1);
    gate.complete({'firstName': ' Antonio ', 'username': 'antonio'});
    await loading;
    await c.load();
    expect(c.name, 'Antonio');
    expect(reads, 1);
    c.dispose();
  });
  test('empty name uses trimmed username', () async {
    final c = HomeGreetingController(
      read: () async => {'firstName': ' ', 'username': ' citizen '},
      isCurrentSession: () => true,
    );
    await c.load();
    expect(c.name, 'citizen');
    c.dispose();
  });
  test('read failure and retry recover without inventing a name', () async {
    int reads = 0;
    final c = HomeGreetingController(
      read: () async {
        if (++reads == 1) throw StateError('offline');
        return {'firstName': 'Antonio'};
      },
      isCurrentSession: () => true,
    );
    await c.load();
    expect(c.state, GreetingState.failed);
    expect(c.name, isNull);
    await c.load();
    expect(c.name, 'Antonio');
    c.dispose();
  });
  test('old session response cannot replace newer session profile', () async {
    final old = Completer<Map<String, dynamic>>();
    int reads = 0;
    final c = HomeGreetingController(
      read: () =>
          ++reads == 1 ? old.future : Future.value({'firstName': 'Beatriz'}),
      isCurrentSession: () => true,
    );
    final first = c.load();
    c.invalidate();
    await c.load();
    old.complete({'firstName': 'Antonio'});
    await first;
    expect(c.name, 'Beatriz');
    c.invalidate();
    expect(c.name, isNull);
    c.dispose();
  });
  test('logout and disposal discard pending profile', () async {
    bool current = true;
    final old = Completer<Map<String, dynamic>>();
    final c = HomeGreetingController(
      read: () => old.future,
      isCurrentSession: () => current,
    );
    final first = c.load();
    current = false;
    c.invalidate();
    c.dispose();
    old.complete({'firstName': 'Old'});
    await first;
    expect(c.name, isNull);
  });
  testWidgets(
    'Home shows pending greeting then real name; logout hides old content',
    (t) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await auth.login('citizen', 'fixture-password');
      final gate = Completer<Map<String, dynamic>>();
      final api = GreetingApi(() => gate.future);
      final gateway = OrderedGateway();
      await t.pumpWidget(homeFixture(auth, api, gateway));
      await t.pump();
      expect(find.text('Cargando tu perfil...'), findsOneWidget);
      gate.complete({'firstName': 'Antonio', 'username': 'citizen'});
      await t.pumpAndSettle();
      expect(find.text('¡Hola, Antonio!'), findsOneWidget);
      await auth.logout();
      await t.pump();
      expect(find.text('¡Hola, Antonio!'), findsNothing);
      await t.pumpWidget(const SizedBox());
      api.close();
      auth.dispose();
    },
  );
  testWidgets(
    'Home retry displays recovered profile and skips duplicate requests',
    (t) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await auth.login('citizen', 'fixture-password');
      int reads = 0;
      final api = GreetingApi(() async {
        if (++reads == 1) throw StateError('offline');
        return {'firstName': 'Antonio'};
      });
      await t.pumpWidget(homeFixture(auth, api, OrderedGateway()));
      await t.pumpAndSettle();
      await t.tap(find.text('Reintentar nombre'));
      await t.pumpAndSettle();
      expect(find.text('¡Hola, Antonio!'), findsOneWidget);
      expect(reads, 2);
      await t.pumpWidget(const SizedBox());
      api.close();
      auth.dispose();
    },
  );
  testWidgets(
    'pending old account greeting is ignored after login into another account',
    (t) async {
      final gateway = OwnerGateway();
      final current = AuthController(gateway, store: MemorySessionStore());
      await current.login('a', 'fixture-password');
      final old = Completer<Map<String, dynamic>>();
      int reads = 0;
      final api = GreetingApi(
        () =>
            ++reads == 1 ? old.future : Future.value({'firstName': 'Beatriz'}),
      );
      await t.pumpWidget(homeFixture(current, api, OrderedGateway()));
      await t.pump();
      await current.logout();
      gateway.owner = 'other-citizen';
      await current.login('b', 'fixture-password');
      old.complete({'firstName': 'Antonio'});
      await t.pumpAndSettle();
      expect(find.text('¡Hola, Antonio!'), findsNothing);
      await t.pumpWidget(
        homeFixture(
          current,
          api,
          OrderedGateway(),
          appKey: const ValueKey('new-session'),
        ),
      );
      await t.pumpAndSettle();
      expect(find.text('¡Hola, Beatriz!'), findsOneWidget);
      expect(reads, 2);
      await t.pumpWidget(const SizedBox());
      api.close();
      current.dispose();
    },
  );
}
