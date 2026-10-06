import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/widgets/operational_ui.dart';
import 'package:simertpi_citizen_app/core/widgets/success_celebration.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/features/parking/presentation/duration_options.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';

import 'active_parking_test.dart' as a;
import 'payments_test.dart' as pay;
import 'parking_test.dart' as p;
import 'discovery_test.dart' as d;

void main() {
  testWidgets('Eight configured backend prices and human duration labels', (
    tester,
  ) async {
    const amounts = [
      '0.13',
      '0.25',
      '0.38',
      '0.50',
      '0.63',
      '0.75',
      '0.88',
      '1.00',
    ];
    final offers = [
      for (int i = 0; i < 8; i++)
        ParkingRules({
          ...p.ruleFixture((i + 1) * 30),
          'calculatedAmount': amounts[i],
          'billedDurationMinutes': (i + 1) * 30,
          'expiresAt': DateTime.utc(
            2026,
            10,
            4,
            15,
          ).add(Duration(minutes: (i + 1) * 30)).toIso8601String(),
          'minimumFractionMinutes': 30,
          'maximumContinuousMinutes': 240,
        }),
    ];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: DurationOptions(
              load: () async => offers,
              selected: 60,
              onSelected: (_) {},
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(OperationalCard), findsNWidgets(8));
    for (final amount in amounts) {
      expect(find.text('\$${amount.replaceAll(".", ",")}'), findsOneWidget);
    }
    expect(find.text('1 hora 30 min'), findsOneWidget);
    expect(find.text('3 horas 30 min'), findsOneWidget);
    expect(
      tester.widget<OperationalCard>(find.byType(OperationalCard).at(1)).border,
      AppColors.action,
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets('Live clock goes green yellow red without terminal actions', (
    tester,
  ) async {
    final end = pay.session('ACTIVE').expectedEndAt;
    var clock = end.subtract(const Duration(minutes: 20));
    final gateway = a.FakeActive();
    final c = a.controller(gateway, now: () => clock);
    await c.load();
    c.locations = ParkingCatalog([d.zone], [d.street], [
      d.space.withAvailability({
        'parkingSpaceId': 's',
        'spaceCode': 'SPACE-TEST',
        'active': true,
        'operationalStatus': 'OCCUPIED',
        'selectable': false,
        'endingSoonSeconds': 600,
      }),
    ]);
    await tester.pumpWidget(a.panel(c));
    await tester.pump();
    expect(find.text('Estacionamiento activo'), findsOneWidget);
    expect(find.text('Finalizar'), findsOneWidget);
    clock = end.subtract(const Duration(minutes: 8));
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Próximo a vencer'), findsOneWidget);
    clock = end;
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(find.text('Tu tiempo de estacionamiento terminó'), findsOneWidget);
    expect(find.text('Extender'), findsNothing);
    expect(find.text('Finalizar'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
  for (final reduced in [false, true]) {
    testWidgets('Success celebration respects reduced motion $reduced', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(disableAnimations: reduced),
            child: const Scaffold(body: SuccessMark()),
          ),
        ),
      );
      expect(find.byType(SuccessCelebration), findsOneWidget);
      expect(find.byIcon(Icons.check_rounded), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}
