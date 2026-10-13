import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'push_lifecycle_test.dart' as push;
import 'support/auth_test_support.dart';
import 'cp192_home_support.dart';
import 'citizen_activity_test.dart' show FakeActivity;

class InitialRuntime extends push.FakeRuntime {
  Completer<PushNavigationTarget?>? pendingTap;
  bool failInitial = false;
  @override
  Future<PushNavigationTarget?> initialTap() async {
    if (failInitial) throw StateError('unavailable');
    return pendingTap != null ? await pendingTap!.future : initial;
  }
}

class DelayedHistory extends FakeActivity {
  final result = Completer<Map<String, dynamic>>();
  int detailRequests = 0;
  @override
  Future<Map<String, dynamic>> detail(String resource, String id) async {
    detailRequests++;
    return result.future;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('navigation resets on authentication, logout and owner change; preserves same session', () {
    final tab = ValueNotifier(2), target = ValueNotifier<String?>('old');
    final session = CitizenNavigationSession(tab, target);
    session.update(authenticated: true, owner: 'a');
    expect(tab.value, 0);
    expect(target.value, isNull);
    tab.value = 2;
    session.update(authenticated: true, owner: 'a');
    expect(tab.value, 2);
    session.update(authenticated: false, owner: null);
    expect(tab.value, 0);
    tab.value = 2;
    session.update(authenticated: true, owner: 'b');
    expect(tab.value, 0);
  });
  for (final scenario in [
    'absent',
    'failed',
    'real',
    'changedOwner',
    'logoutSameOwner',
  ]) {
    test('initial notification navigation: $scenario', () async {
      final runtime = InitialRuntime(),
          api = push.FakeApi(),
          store = push.FakeStore()..consents.add(push.ownerA);
      var owner = push.ownerA;
      final tab = ValueNotifier(0), target = ValueNotifier<String?>(null);
      final lifecycle = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => owner,
        unreadNotifications: ValueNotifier(0),
        citizenTab: tab,
        parkingSessionTarget: target,
      );
      final real = push.target('PARKING_ENDING_SOON');
      if (scenario == 'real') runtime.initial = real;
      if (scenario == 'failed') runtime.failInitial = true;
      if (['changedOwner', 'logoutSameOwner'].contains(scenario)) {
        runtime.pendingTap = Completer<PushNavigationTarget?>();
      }
      final authenticating = lifecycle.onAuthenticated();
      if (runtime.pendingTap != null) {
        await Future<void>.delayed(const Duration(milliseconds: 20));
        if (scenario == 'changedOwner') {
          owner = push.ownerB;
        } else {
          await lifecycle.logout(remote: false, ownerId: owner);
        }
        runtime.pendingTap!.complete(real);
      }
      await authenticating;
      expect(tab.value, 0);
      expect(target.value, scenario == 'real' ? push.sessionId : null);
      await lifecycle.dispose();
      await runtime.dispose();
    });
  }
  test(
    'explicit foreground tap stays supported and null does not open inbox',
    () async {
      final runtime = InitialRuntime(),
          api = push.FakeApi(),
          store = push.FakeStore()..consents.add(push.ownerA);
      final tab = ValueNotifier(0), target = ValueNotifier<String?>(null);
      final lifecycle = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => push.ownerA,
        unreadNotifications: ValueNotifier(0),
        citizenTab: tab,
        parkingSessionTarget: target,
      )..start();
      await lifecycle.onAuthenticated();
      runtime.opened.add(null);
      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(tab.value, 0);
      runtime.opened.add(push.target('PARKING_EXTENSION_CONFIRMED'));
      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(target.value, push.sessionId);
      await lifecycle.dispose();
      await runtime.dispose();
    },
  );
  for (final restored in [false, true]) {
    testWidgets(
      'normal authentication enters Home with stale tab discarded: restored=$restored',
      (t) async {
        final store = MemorySessionStore();
        if (restored) store.session = fixtureSession();
        final auth = AuthController(FakeAuthGateway(), store: store);
        if (restored) {
          await auth.restore();
        } else {
          await auth.login('citizen', 'fixture-password');
        }
        final api = GreetingApi(() async => {'firstName': 'Antonio'}),
            tab = ValueNotifier(2);
        await t.pumpWidget(homeFixture(auth, api, OrderedGateway(), tab: tab));
        await t.pumpAndSettle();
        expect(tab.value, 0);
        expect(find.text('¡Hola, Antonio!').hitTestable(), findsOneWidget);
        tab.value = 2;
        await t.pumpAndSettle();
        expect(tab.value, 2); // Explicit user navigation is retained.
        await t.pumpWidget(const SizedBox());
        api.close();
        auth.dispose();
      },
    );
  }
  testWidgets(
    'history callback from previous session cannot navigate after logout',
    (t) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await auth.login('a', 'fixture-password');
      final api = GreetingApi(() async => {'firstName': 'Antonio'}),
          history = DelayedHistory(),
          target = ValueNotifier<String?>(null);
      await t.pumpWidget(
        homeFixture(
          auth,
          api,
          OrderedGateway(),
          target: target,
          activity: history,
        ),
      );
      await t.pumpAndSettle();
      target.value = 'owned-session';
      await t.pumpAndSettle();
      expect(history.detailRequests, 1);
      await auth.logout();
      history.result.complete({});
      await t.pumpAndSettle();
      expect(t.state<NavigatorState>(find.byType(Navigator)).canPop(), isFalse);
      await t.pumpWidget(const SizedBox());
      api.close();
      auth.dispose();
    },
  );
}
