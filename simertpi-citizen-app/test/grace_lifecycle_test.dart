import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/core/widgets/operational_ui.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';

import 'active_parking_test.dart' as a;
import 'parking_test.dart' as p;

final contractStart = DateTime.utc(2026, 10, 4, 15);
final contractEnd = contractStart.add(const Duration(hours: 1));

class GraceGateway extends a.FakeActive {
  GraceGateway(this.clock);
  final DateTime Function() clock;
  bool warned = false;
  bool max = false;
  int contracted = 60;
  @override
  ParkingReceipt receipt() {
    final now = clock();
    final end = contractStart.add(Duration(minutes: contracted));
    final graceEnd = end.add(const Duration(minutes: 10));
    final phase = now.isBefore(end)
        ? (end.difference(now).inSeconds <= 600 ? 'ENDING_SOON' : 'ACTIVE')
        : now.isBefore(graceEnd)
        ? 'EXPIRED_IN_GRACE'
        : warned
        ? (max ? 'MAX_TIME_REACHED' : 'REGULARIZATION_ALLOWED')
        : 'GRACE_EXCEEDED';
    final eligible = !max && phase != 'GRACE_EXCEEDED';
    return ParkingReceipt({
      'id': 'receipt',
      'userId': 'fixture-citizen',
      'vehicleId': 'v',
      'parkingSpaceId': 's',
      'tariffId': 'tariff-fixture',
      'status': state == 'COMPLETED'
          ? 'COMPLETED'
          : max
          ? 'MAX_TIME_REACHED'
          : now.isBefore(end)
          ? 'ACTIVE'
          : 'EXPIRED',
      'startedAt': contractStart.toIso8601String(),
      'expectedEndAt': end.toIso8601String(),
      'operational': {
        'state': phase,
        'evaluatedAt': now.toIso8601String(),
        'nextTransitionAt':
            (phase == 'EXPIRED_IN_GRACE'
                    ? graceEnd
                    : phase == 'ACTIVE'
                    ? end.subtract(const Duration(minutes: 10))
                    : phase == 'ENDING_SOON'
                    ? end
                    : null)
                ?.toIso8601String(),
        'extensionEligible': eligible,
        'closeAllowed': eligible || (max && warned),
      },
    });
  }

  @override
  Future<ParkingRules> quote(String id, int? minutes) async => ParkingRules({
    ...p.ruleFixture(minutes),
    'expiresAt': minutes == null
        ? null
        : contractEnd.add(Duration(minutes: minutes)).toIso8601String(),
  });
}

void main() {
  testWidgets(
    '180 purchased minutes plus warning retains capacity; 240 permits only departure',
    (tester) async {
      for (final minutes in [180, 240]) {
        final clock = contractStart.add(Duration(minutes: minutes + 11));
        final g = GraceGateway(() => clock)
          ..contracted = minutes
          ..warned = true
          ..max = minutes == 240;
        final c = a.controller(g, now: () => clock);
        await c.load();
        await tester.pumpWidget(a.panel(c));
        await tester.pump();
        expect(find.text('Finalizar'), findsOneWidget);
        expect(
          find.text('Extender'),
          minutes == 180 ? findsOneWidget : findsNothing,
        );
        expect(
          find.text(
            minutes == 180
                ? 'Regulariza tu estacionamiento'
                : 'Tiempo máximo alcanzado',
          ),
          findsOneWidget,
        );
        if (minutes == 240) {
          expect(
            find.textContaining('Debes mover tu vehículo'),
            findsOneWidget,
          );
        }
        expect(
          find.textContaining(
            RegExp(r'10 minutos|minutos de gracia|hasta las'),
          ),
          findsNothing,
        );
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        c.dispose();
      }
    },
  );
  test('backend regularization below cap is not converted to max by elapsed device time', () {
    final now = contractStart.add(const Duration(hours: 5));
    final g = GraceGateway(() => now)
      ..contracted = 180
      ..warned = true;
    final receipt = g.receipt()..anchor(now);
    expect(
      receipt.temporalState(now.add(const Duration(hours: 1))),
      'REGULARIZATION_ALLOWED',
    );
    expect(receipt.extensionAllowed(now), isTrue);
  });

  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    for (final scale in [1.0, 2.0]) {
      for (final phase in [
        'ACTIVE',
        'ENDING_SOON',
        'EXPIRED_IN_GRACE',
        'GRACE_EXCEEDED',
        'REGULARIZATION_ALLOWED',
        'MAX_TIME_REACHED',
      ]) {
        testWidgets(
          '$phase $size text $scale shows only server-authorized actions',
          (tester) async {
            await tester.binding.setSurfaceSize(size);
            addTearDown(() => tester.binding.setSurfaceSize(null));
            var clock = phase == 'ACTIVE'
                ? contractEnd.subtract(const Duration(minutes: 20))
                : phase == 'ENDING_SOON'
                ? contractEnd.subtract(const Duration(minutes: 5))
                : phase == 'EXPIRED_IN_GRACE'
                ? contractEnd
                : contractEnd.add(const Duration(minutes: 11));
            final g = GraceGateway(() => clock)
              ..warned = {
                'REGULARIZATION_ALLOWED',
                'MAX_TIME_REACHED',
              }.contains(phase)
              ..max = phase == 'MAX_TIME_REACHED';
            final c = a.controller(g, now: () => clock);
            await c.load();
            await tester.pumpWidget(a.panel(c, scale: scale));
            await tester.pump();
            final label = switch (phase) {
              'ACTIVE' => 'Estacionamiento activo',
              'ENDING_SOON' => 'Próximo a vencer',
              'EXPIRED_IN_GRACE' => 'Tu tiempo de estacionamiento terminó',
              'GRACE_EXCEEDED' => 'Período de gracia finalizado',
              'REGULARIZATION_ALLOWED' => 'Regulariza tu estacionamiento',
              _ => 'Tiempo máximo alcanzado',
            };
            expect(find.text(label), findsOneWidget);
            final actions = !{
              'GRACE_EXCEEDED',
              'MAX_TIME_REACHED',
            }.contains(phase);
            expect(
              find.text('Extender'),
              actions ? findsOneWidget : findsNothing,
            );
            expect(
              find.text('Finalizar'),
              (actions || phase == 'MAX_TIME_REACHED')
                  ? findsOneWidget
                  : findsNothing,
            );
            final card = tester.widget<OperationalCard>(
              find.byType(OperationalCard).first,
            );
            expect(
              card.color,
              phase == 'ACTIVE'
                  ? AppColors.successSurface
                  : phase == 'ENDING_SOON'
                  ? AppColors.warningSurface
                  : phase == 'GRACE_EXCEEDED'
                  ? AppColors.criticalSurface
                  : AppColors.dangerSurface,
            );
            expect(
              find.textContaining(
                RegExp(r'10 minutos|minutos de gracia|hasta las'),
              ),
              findsNothing,
            );
            expect(tester.takeException(), isNull);
            await tester.pumpWidget(const SizedBox());
            c.dispose();
          },
        );
      }
    }
  }
  testWidgets(
    'server clock transitions and warning refresh without restarting',
    (tester) async {
      var clock = contractEnd.subtract(const Duration(minutes: 20));
      final g = GraceGateway(() => clock);
      final c = a.controller(g, now: () => clock);
      await c.load();
      await tester.pumpWidget(a.panel(c));
      clock = contractEnd.subtract(const Duration(minutes: 5));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(find.text('Próximo a vencer'), findsOneWidget);
      clock = contractEnd;
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(find.text('Tu tiempo de estacionamiento terminó'), findsOneWidget);
      expect(find.text('Extender'), findsOneWidget);
      clock = contractEnd.add(const Duration(minutes: 10));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(find.text('Período de gracia finalizado'), findsOneWidget);
      expect(find.text('Finalizar'), findsNothing);
      g.warned = true;
      await c.load();
      await tester.pump();
      expect(find.text('Regulariza tu estacionamiento'), findsOneWidget);
      expect(find.text('Finalizar'), findsOneWidget);
      expect(c.sessions.single.spaceId, 's');
      expect(c.sessions.single.vehicleId, 'v');
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
  test(
    'grace and warning extension and close remain server authorized',
    () async {
      var clock = contractEnd;
      final g = GraceGateway(() => clock);
      final c = a.controller(g, now: () => clock);
      await c.load();
      await c.prepare('receipt');
      expect(c.canExtend, isTrue);
      clock = contractEnd.add(const Duration(minutes: 10));
      await c.prepare('receipt');
      expect(c.canExtend, isFalse);
      g.warned = true;
      await c.prepare('receipt');
      expect(c.canExtend, isTrue);
      expect(await c.closeSession('receipt'), isTrue);
      expect(g.closes, 1);
      c.dispose();
    },
  );
  test(
    'server evaluation anchors presentation despite device clock offset',
    () {
      final g = GraceGateway(() => contractEnd);
      final s = g.receipt();
      final device = DateTime.utc(2040);
      s.anchor(device);
      expect(s.temporalState(device), 'EXPIRED_IN_GRACE');
      expect(
        s.temporalState(device.add(const Duration(minutes: 10))),
        'GRACE_EXCEEDED',
      );
      expect(s.status, 'EXPIRED');
      expect(s.expectedEndAt, contractEnd);
    },
  );
  testWidgets('background pauses warning sync; resume discovers real action', (
    tester,
  ) async {
    final clock = contractEnd.add(const Duration(minutes: 11));
    final g = GraceGateway(() => clock);
    final c = a.controller(g, now: () => clock);
    await c.load();
    await tester.pumpWidget(a.panel(c));
    await tester.pump();
    final before = g.loads;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    g.warned = true;
    await tester.pump(const Duration(seconds: 65));
    expect(g.loads, before);
    expect(find.text('Período de gracia finalizado'), findsOneWidget);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    await tester.pump();
    expect(g.loads, before + 1);
    expect(find.text('Regulariza tu estacionamiento'), findsOneWidget);
    expect(find.text('Extender'), findsOneWidget);
    expect(find.text('Finalizar'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
}
