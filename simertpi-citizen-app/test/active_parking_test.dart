import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/features/active_parking/data/active_parking_service.dart';
import 'package:simertpi_citizen_app/features/active_parking/state/active_parking_controller.dart';
import 'package:simertpi_citizen_app/features/active_parking/presentation/active_parking_panel.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';

import 'parking_test.dart' as p;
import 'payments_test.dart' as pay;
import 'discovery_test.dart' as d;

class MemoryExtension implements ExtensionStore {
  Map<String, dynamic>? value;
  Object? failure;
  @override
  Future<Map<String, dynamic>?> read() async => value;
  @override
  Future<void> write(Map<String, Object> intent) async {
    if (failure != null) throw failure!;
    value = Map.from(intent);
  }

  @override
  Future<void> clear() async {
    value = null;
  }
}

class FakeActive implements ActiveParkingGateway {
  String state = 'ACTIVE';
  int loads = 0, closes = 0, extensions = 0, gets = 0;
  bool empty = false, approve = false;
  Object? error;
  Completer<void>? gate;
  Map<String, Object>? sent;
  ParkingReceipt receipt() => state == 'EXTENDED'
      ? ParkingReceipt({
          'id': 'receipt',
          'userId': 'fixture-citizen',
          'vehicleId': 'v',
          'parkingSpaceId': 's',
          'status': state,
          'startedAt': '2026-10-04T15:00:00Z',
          'expectedEndAt': '2026-10-04T15:30:00Z',
          'tariffId': 'tariff-fixture',
        })
      : pay.session(state);
  @override
  Future<List<ParkingReceipt>> mine(String owner) async {
    loads++;
    if (error != null) throw error!;
    return empty ? [] : [receipt()];
  }

  @override
  Future<ParkingReceipt> session(String id) async {
    gets++;
    return receipt();
  }

  @override
  Future<ParkingReceipt> close(String id) async {
    closes++;
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    state = 'COMPLETED';
    return receipt();
  }

  @override
  Future<ParkingRules> quote(String id, int? minutes) async => ParkingRules({
    ...p.ruleFixture(minutes),
    'expiresAt': minutes == null ? null : '2026-10-04T15:30:00Z',
  });
  @override
  Future<Map> extend(String id, Map<String, Object> request) async {
    extensions++;
    sent = request;
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    if (approve) state = 'EXTENDED';
    return {
      'parkingSessionId': id,
      'paymentId': 'payment-fixture',
      'additionalMinutes': request['additionalMinutes'],
    };
  }
}

ActiveParkingController controller(
  FakeActive gateway, {
  MemoryExtension? store,
  pay.FakePayments? payments,
  bool dev = true,
  DateTime Function()? now,
}) => ActiveParkingController(
  owner: 'fixture-citizen',
  gateway: gateway,
  payments: payments ?? pay.FakePayments(),
  store: store ?? MemoryExtension(),
  catalog: d.FakeCatalog(),
  vehicles: p.FakeVehicles(),
  dev: dev,
  now: now ?? () => pay.clock,
);
Widget panel(ActiveParkingController c, {double scale = 1}) => MaterialApp(
  theme: AppTheme.light,
  home: MediaQuery(
    data: MediaQueryData(textScaler: TextScaler.linear(scale)),
    child: Scaffold(
      body: SingleChildScrollView(child: ActiveParkingPanel(controller: c)),
    ),
  ),
);
void main() {
  test('Countdown derives only from backend timestamp, clamps zero, never mutates status', () {
    final s = pay.session('ACTIVE');
    expect(remainingTime(s, pay.clock), '00:14:00');
    expect(
      remainingTime(s, pay.clock.add(const Duration(hours: 1))),
      '00:00:00',
    );
    expect(s.status, 'ACTIVE');
  });
  test('Empty and multiple operational states filter terminal and pending sessions', () async {
    final g = FakeActive()..empty = true;
    final c = controller(g);
    await c.load();
    expect(c.sessions, isEmpty);
    g.empty = false;
    for (final state in [
      'ACTIVE',
      'EXTENDED',
      'EXPIRED',
      'MAX_TIME_REACHED',
      'COMPLETED',
      'CANCELLED',
      'PENDING_PAYMENT',
    ]) {
      g.state = state;
      await c.load();
      expect(c.sessions.length, openParkingStates.contains(state) ? 1 : 0);
    }
    c.dispose();
  });
  test(
    'Offline retains last state as unconfirmed; refresh restores authority',
    () async {
      final g = FakeActive();
      final c = controller(g);
      await c.load();
      g.error = const AppFailure(FailureKind.offline);
      await c.load();
      expect(c.stale, isTrue);
      expect(c.sessions.single.status, 'ACTIVE');
      g.error = null;
      await c.load();
      expect(c.stale, isFalse);
      c.dispose();
    },
  );
  test('Extension uses server quote, stores key before POST, prevents double request and accepts real session', () async {
    final g = FakeActive()
      ..gate = Completer<void>()
      ..approve = true;
    final store = MemoryExtension();
    final c = controller(g, store: store);
    await c.prepare('receipt');
    expect(c.quote!.amount, '0.31');
    final sent = c.confirmExtension();
    await Future<void>.delayed(Duration.zero);
    expect(store.value!['expectedAmount'], '0.31');
    await c.confirmExtension();
    expect(g.extensions, 1);
    g.gate!.complete();
    await sent;
    expect(g.sent!['paymentMethod'], 'TEST');
    expect(g.sent!.containsKey('provider'), isFalse);
    expect(c.selected!.status, 'EXTENDED');
    expect(store.value, isNull);
    c.dispose();
  });
  test(
    'Unknown extension restores persisted same key; no optimistic extension',
    () async {
      final g = FakeActive()..error = const AppFailure(FailureKind.timeout);
      final store = MemoryExtension();
      final payments = pay.FakePayments()..outcome = 'PENDING';
      var c = controller(g, store: store, payments: payments);
      await c.prepare('receipt');
      await c.confirmExtension();
      final key = store.value!['idempotencyKey'];
      expect(c.selected!.status, 'ACTIVE');
      c.dispose();
      g.error = null;
      c = controller(g, store: store, payments: payments);
      await c.prepare('receipt');
      await c.checkExtension();
      expect(g.sent!['idempotencyKey'], key);
      expect(c.intent, isNotNull);
      expect(c.payment!.waiting, isTrue);
      expect(payments.creates, 0);
      c.dispose();
    },
  );
  test('Secure persistence failure never sends extension', () async {
    final g = FakeActive();
    final c = controller(g, store: MemoryExtension()..failure = Exception());
    await c.prepare('receipt');
    await c.confirmExtension();
    expect(g.extensions, 0);
    c.dispose();
  });
  test('Close rejects duplicate tap; reconciles COMPLETED after uncertain result before another POST', () async {
    final g = FakeActive()..gate = Completer<void>();
    final c = controller(g);
    await c.load();
    final first = c.closeSession('receipt');
    await Future<void>.delayed(Duration.zero);
    expect(await c.closeSession('receipt'), isFalse);
    expect(g.closes, 1);
    g.gate!.complete();
    expect(await first, isTrue);
    expect(c.sessions, isEmpty);
    g.state = 'ACTIVE';
    g.error = const AppFailure(FailureKind.timeout);
    expect(await c.closeSession('receipt'), isFalse);
    expect(c.closeUncertain, isTrue);
    final posts = g.closes;
    g.state = 'COMPLETED';
    g.error = null;
    expect(await c.closeSession('receipt'), isTrue);
    expect(g.closes, posts);
    c.dispose();
  });
  test('Non DEV has no invented production payment method', () async {
    final c = controller(FakeActive(), dev: false);
    await c.prepare('receipt');
    expect(c.canExtend, isFalse);
    c.dispose();
  });
  for (final state in ['ACTIVE', 'EXTENDED', 'EXPIRED', 'MAX_TIME_REACHED']) {
    testWidgets('Panel state $state and accessible time', (tester) async {
      final g = FakeActive()..state = state;
      final c = controller(g);
      await c.load();
      await tester.pumpWidget(panel(c));
      await tester.pump();
      expect(
        find.text(
          state == 'EXPIRED'
              ? 'Tu tiempo de estacionamiento terminó'
              : state == 'MAX_TIME_REACHED'
              ? 'Tiempo máximo alcanzado'
              : 'Estacionamiento activo',
        ),
        findsOneWidget,
      );
      expect(
        find.text('Extender'),
        {'MAX_TIME_REACHED', 'EXPIRED'}.contains(state)
            ? findsNothing
            : findsOneWidget,
      );
      expect(
        find.text('Finalizar'),
        {'MAX_TIME_REACHED', 'EXPIRED'}.contains(state)
            ? findsNothing
            : findsOneWidget,
      );
      expect(find.byType(Semantics), findsWidgets);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    });
  }
  testWidgets(
    'Cancel close has no side effects; confirmed close removes card',
    (tester) async {
      final g = FakeActive();
      final c = controller(g);
      await c.load();
      await tester.pumpWidget(panel(c));
      await tester.tap(find.text('Finalizar'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();
      expect(g.closes, 0);
      await tester.tap(find.text('Finalizar'));
      await tester.pumpAndSettle();
      await tester.tap(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.text('Finalizar'),
        ),
      );
      await tester.pumpAndSettle();
      expect(g.closes, 1);
      expect(find.text('Estacionamiento activo'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
  testWidgets('Resume refreshes; background suspends timer', (tester) async {
    final g = FakeActive()..state = 'EXPIRED';
    final c = controller(g);
    await c.load();
    await tester.pumpWidget(panel(c));
    final before = g.loads;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 2));
    expect(g.loads, before);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(g.loads, before + 1);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
  testWidgets(
    'Extension confirmation cancels safely and dispatches only after confirmation',
    (tester) async {
      final g = FakeActive()..gate = Completer<void>();
      final c = controller(
        g,
        payments: pay.FakePayments()..outcome = 'PENDING',
      );
      await c.prepare('receipt');
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.light,
          home: ExtensionPage(controller: c),
        ),
      );
      await tester.ensureVisible(find.text('Continuar'));
      await tester.tap(find.text('Continuar'));
      await tester.pumpAndSettle();
      expect(g.extensions, 0);
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();
      expect(g.extensions, 0);
      await tester.ensureVisible(find.text('Continuar'));
      await tester.tap(find.text('Continuar'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirmar'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(g.extensions, 1);
      expect(c.busy, isTrue);
      g.gate!.complete();
      await tester.pumpAndSettle();
      expect(g.extensions, 1);
      expect(c.payment!.waiting, isTrue);
      expect(find.text('Tiempo extendido correctamente'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
  testWidgets('Zero countdown refreshes once without changing status locally', (
    tester,
  ) async {
    final g = FakeActive();
    var clock = g.receipt().expectedEndAt.subtract(const Duration(seconds: 1));
    final c = controller(g, now: () => clock);
    await c.load();
    await tester.pumpWidget(panel(c));
    final before = g.loads;
    clock = clock.add(const Duration(seconds: 2));
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(g.loads, before + 1);
    await tester.pump(const Duration(seconds: 3));
    expect(g.loads, before + 1);
    expect(c.sessions.single.status, 'ACTIVE');
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
  for (final size in [
    const Size(320, 640),
    const Size(640, 320),
    const Size(390, 844),
  ]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets('Panel and extension $size scale $scale no overflow', (
        tester,
      ) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final c = controller(FakeActive());
        await c.load();
        await tester.pumpWidget(panel(c, scale: scale));
        await tester.pump();
        expect(tester.takeException(), isNull);
        await c.prepare('receipt');
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.light,
            home: MediaQuery(
              data: MediaQueryData(textScaler: TextScaler.linear(scale)),
              child: ExtensionPage(controller: c),
            ),
          ),
        );
        await tester.pump();
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        c.dispose();
      });
    }
  }
}
