import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';

import 'active_parking_test.dart' as active;
import 'support/auth_test_support.dart';
import 'cp192_home_support.dart';

void main() {
  test(
    'state groups, descending start and equal-date IDs are deterministic',
    () async {
      final g = OrderedGateway()
        ..values = [
          receipt('z', 'EXPIRED', 50),
          receipt('b', 'ACTIVE', 5),
          receipt('a', 'EXTENDED', 5),
          receipt('new', 'ACTIVE', 10),
          receipt('max', 'MAX_TIME_REACHED', 55),
        ];
      final c = active.controller(g);
      await c.load();
      expect(c.sessions.map((s) => s.id), ['new', 'a', 'b', 'max', 'z']);
      g.values = g.values.reversed.toList();
      await c.load();
      expect(c.sessions.map((s) => s.id), ['new', 'a', 'b', 'max', 'z']);
      c.dispose();
    },
  );
  test(
    'focus and new confirmed session never override chronological order',
    () async {
      final g = OrderedGateway()
        ..values = [receipt('old', 'ACTIVE', 1), receipt('new', 'ACTIVE', 2)];
      final c = active.controller(g);
      await c.load();
      c.prioritizeSession('old');
      expect(c.focusedSessionId, 'old');
      expect(c.sessions.first.id, 'new');
      g.values.add(receipt('latest', 'ACTIVE', 3));
      await c.load();
      expect(c.sessions.map((s) => s.id), ['latest', 'new', 'old']);
      c.dispose();
    },
  );
  test(
    'requests during a read coalesce and obsolete response never publishes',
    () async {
      final gate = Completer<List<ParkingReceipt>>();
      final g = OrderedGateway()
        ..pending = gate
        ..values = [receipt('new', 'ACTIVE', 3)];
      final c = active.controller(g);
      final published = <String>[];
      c.addListener(() => published.addAll(c.sessions.map((s) => s.id)));
      final first = c.load();
      final second = c.load();
      final third = c.load();
      gate.complete([receipt('obsolete', 'ACTIVE', 1)]);
      await Future.wait([first, second, third]);
      expect(g.loads, 2);
      expect(c.sessions.single.id, 'new');
      expect(published, isNot(contains('obsolete')));
      c.dispose();
    },
  );
  test('reload during mutation waits for busy to finish', () async {
    final g = OrderedGateway()..values = [receipt('new', 'ACTIVE', 3)];
    final c = active.controller(g);
    c.busy = true;
    final load = c.load();
    expect(g.loads, 0);
    c.busy = false;
    c.emit();
    await load;
    expect(g.loads, 1);
    c.dispose();
  });
  test(
    'read interrupted by mutation cannot publish its older snapshot',
    () async {
      final gate = Completer<List<ParkingReceipt>>();
      final g = OrderedGateway()
        ..pending = gate
        ..values = [];
      final c = active.controller(g);
      final loading = c.load();
      c.busy = true;
      c.emit();
      c.busy = false;
      c.emit();
      gate.complete([receipt('closed-old', 'ACTIVE', 1)]);
      await loading;
      expect(c.sessions, isEmpty);
      expect(g.loads, 2);
      c.dispose();
    },
  );
  test('offline preserves last confirmed sessions as stale', () async {
    final g = OrderedGateway()..values = [receipt('new', 'ACTIVE', 2)];
    final c = active.controller(g);
    await c.load();
    g.error = const AppFailure(FailureKind.offline);
    await c.load();
    expect(c.sessions.single.id, 'new');
    expect(c.stale, isTrue);
    c.dispose();
  });
  test('disposal invalidates pending read without notifications', () async {
    final gate = Completer<List<ParkingReceipt>>();
    final g = OrderedGateway()..pending = gate;
    final c = active.controller(g);
    final loading = c.load();
    c.dispose();
    gate.complete([receipt('old', 'ACTIVE', 1)]);
    await loading;
    expect(c.sessions, isEmpty);
  });
  testWidgets('Home tab changes retain stable displayed order', (t) async {
    final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
    await auth.login('citizen', 'fixture-password');
    final api = GreetingApi(() async => {'firstName': 'Antonio'});
    final tab = ValueNotifier(0);
    final g = OrderedGateway()
      ..values = [receipt('old', 'ACTIVE', 1), receipt('new', 'ACTIVE', 2)];
    await t.pumpWidget(homeFixture(auth, api, g, tab: tab));
    await t.pumpAndSettle();
    expect(
      t.getTopLeft(find.byKey(const ValueKey('parking-session-new'))).dy,
      lessThan(
        t.getTopLeft(find.byKey(const ValueKey('parking-session-old'))).dy,
      ),
    );
    tab.value = 3;
    await t.pumpAndSettle();
    g.values = g.values.reversed.toList();
    tab.value = 0;
    await t.pumpAndSettle();
    expect(
      t.getTopLeft(find.byKey(const ValueKey('parking-session-new'))).dy,
      lessThan(
        t.getTopLeft(find.byKey(const ValueKey('parking-session-old'))).dy,
      ),
    );
    await t.pumpWidget(const SizedBox());
    api.close();
    auth.dispose();
  });
}
