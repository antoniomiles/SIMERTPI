import 'package:simertpi_citizen_app/features/discovery/presentation/discovery_page.dart';
import 'package:simertpi_citizen_app/features/discovery/state/discovery_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/map_config.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/space_selection_page.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/space_status.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/parking_map.dart';
import 'package:simertpi_citizen_app/features/home/home_page.dart';
import 'package:simertpi_citizen_app/features/vehicles/data/vehicle_service.dart';

import 'discovery_test.dart' as d;
import 'parking_test.dart' as p;
import 'payments_test.dart' as pay;
import 'active_parking_test.dart' as active;

CatalogSpace projected(String status) => d.space.withAvailability({
  'parkingSpaceId': 's',
  'spaceCode': 'SPACE-TEST',
  'active': true,
  'operationalStatus': status,
  'selectable': status == 'AVAILABLE',
  'latitude': -3,
  'longitude': -79,
  'remainingSeconds': status == 'ENDING_SOON' ? 480 : null,
});
void main() {
  testWidgets('Map detail action opens detail rather than vehicle selection', (
    tester,
  ) async {
    final controller = DiscoveryController(d.FakeCatalog());
    await tester.pumpWidget(
      await d.screen(
        DiscoveryPage(
          controller: controller,
          initialMap: true,
          mapConfig: const MapConfig(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'SPACE-TEST');
    await tester.tap(find.text('Buscar por código'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    // A refresh while the sheet is open must not invalidate its captured result.
    await controller.load();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.text('Ver detalle'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('Detalle del espacio'), findsOneWidget);
    expect(find.text('Selecciona un vehículo'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
  for (final status in [
    'AVAILABLE',
    'ENDING_SOON',
    'OCCUPIED',
    'DISABLED',
    'UNKNOWN',
  ]) {
    testWidgets(
      'Operational $status shares list/detail/marker semantics and selection',
      (tester) async {
        final s = projected(status);
        final cat = ParkingCatalog([d.zone], [d.street], [s]);
        expect(cat.selectable(s), status == 'AVAILABLE');
        expect(
          spaceColor(s),
          {
                'AVAILABLE': AppColors.available,
                'ENDING_SOON': AppColors.endingSoon,
                'OCCUPIED': AppColors.occupied,
              }[status] ??
              AppColors.muted,
        );
        await tester.pumpWidget(
          await d.screen(SpaceSelectionPage(result: IdentifiedSpace(s, cat))),
        );
        await tester.pumpAndSettle();
        expect(find.text(spaceStatus(s)), findsWidgets);
        expect(find.textContaining('Disponible en'), findsNothing);
        if (status != 'AVAILABLE') {
          expect(
            tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
            isNull,
          );
        }
        if (status == 'ENDING_SOON') {
          expect(find.textContaining('8 min'), findsOneWidget);
        }
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: ParkingMap(
                spaces: [s],
                catalog: cat,
                config: MapConfig.parse('', '', source: 'osm-dev'),
                tileProvider: d.FixtureTiles(),
                enabled: true,
                onSelect: (_) {},
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byTooltip('Espacio 1'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
  test('Malformed or contradictory availability fails closed', () {
    expect(
      () => d.space.withAvailability({
        'parkingSpaceId': 's',
        'spaceCode': 'SPACE-TEST',
        'active': true,
        'operationalStatus': 'OCCUPIED',
        'selectable': true,
      }),
      throwsA(anything),
    );
    expect(
      CatalogSpace('x', 't', 'x', 'x', 'x', true).backendSelectable,
      false,
    );
  });
  testWidgets(
    'Android Back keeps vehicle and duration while navigating preparation steps',
    (tester) async {
      final c = p.controller(p.FakeParking());
      await tester.pumpWidget(await p.page(c));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Continuar'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Revisar resumen'));
      await tester.tap(find.text('Revisar resumen'));
      await tester.pumpAndSettle();
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('Tiempo de estacionamiento'), findsOneWidget);
      expect(c.vehicleId, 'v');
      expect(c.minutes, 15);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('Selecciona un vehículo'), findsOneWidget);
      expect(c.vehicleId, 'v');
      c.dispose();
    },
  );
  test('Vehicle occupied after selection prevents POST and offers explicit conflict', () async {
    final gateway = p.FakeParking();
    final c = p.controller(gateway);
    await c.load();
    gateway.existing = [pay.session('ACTIVE')];
    await c.create();
    expect(gateway.creates, 0);
    expect(c.vehicleId, isNull);
    expect(c.message, 'Este vehículo ya tiene un estacionamiento en curso.');
    c.dispose();
  });
  test(
    'Space occupied after selection prevents POST and refreshes availability',
    () async {
      final catalog = ChangingCatalog();
      final gateway = p.FakeParking();
      final c = p.controller(gateway, catalog: catalog);
      await c.load();
      catalog.occupied = true;
      await c.create();
      expect(gateway.creates, 0);
      expect(c.space.space.operationalStatus, 'OCCUPIED');
      expect(
        c.message,
        'Este espacio ya no está disponible. Selecciona otro estacionamiento.',
      );
      c.dispose();
    },
  );
  for (final state in [
    'PENDING_PAYMENT',
    'ACTIVE',
    'EXTENDED',
    'EXPIRED',
    'MAX_TIME_REACHED',
  ]) {
    test('Vehicle $state blocked while another vehicle can continue', () async {
      final gateway = p.FakeParking()..existing = [pay.session(state)];
      final vehicles = p.FakeVehicles()
        ..items = [
          p.vehicle,
          const CitizenVehicle(
            id: 'v2',
            userId: 'fixture-citizen',
            plate: 'FREE123',
            active: true,
          ),
        ];
      final c = p.controller(gateway, vehicles: vehicles);
      await c.load();
      c.choose('v');
      expect(c.vehicleId, isNot('v'));
      c.choose('v2');
      expect(c.vehicleId, 'v2');
      expect(c.canSubmit, true);
      c.dispose();
    });
  }
  for (final state in [
    'PENDING_PAYMENT',
    'ACTIVE',
    'EXTENDED',
    'EXPIRED',
    'MAX_TIME_REACHED',
  ]) {
    testWidgets(
      'Vehicle $state presents a locked card and cannot be selected',
      (tester) async {
        final c = p.controller(
          p.FakeParking()..existing = [pay.session(state)],
        );
        await tester.pumpWidget(await p.page(c));
        await tester.pumpAndSettle();
        expect(find.text('Estacionado actualmente'), findsOneWidget);
        expect(find.byIcon(Icons.lock), findsOneWidget);
        final tile = find.byType(ListTile);
        expect(tester.widget<ListTile>(tile).onTap, isNull);
        final next = find.widgetWithText(FilledButton, 'Continuar');
        expect(tester.widget<FilledButton>(next).onPressed, isNull);
        c.dispose();
      },
    );
  }
  testWidgets(
    'Confirmed start returns from Map to retained Home and reloads operational parking',
    (tester) async {
      final signal = ValueNotifier<int>(0);
      final gateway = active.FakeActive()..empty = true;
      final c = active.controller(gateway);
      await tester.pumpWidget(
        await d.screen(
          Builder(
            builder: (context) {
              final scope = AppScope.of(context);
              return AppScope(
                config: scope.config,
                auth: scope.auth,
                parkingConfirmed: signal,
                child: HomePage(activeController: c),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      final nav = find.byType(NavigationBar);
      await tester.tap(find.descendant(of: nav, matching: find.text('Mapa')));
      await tester.pumpAndSettle();
      gateway.empty = false;
      signal.value++;
      await tester.pumpAndSettle();
      expect(find.text('¿Qué deseas hacer?'), findsOneWidget);
      expect(find.text('Estacionamiento activo'), findsOneWidget);
      expect(gateway.loads, 2);
      expect(find.byType(HomePage), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
      signal.dispose();
    },
  );
  for (final state in ['COMPLETED', 'CANCELLED']) {
    test('Terminal $state releases vehicle selection', () async {
      final c = p.controller(p.FakeParking()..existing = [pay.session(state)]);
      await c.load();
      expect(c.vehicleId, 'v');
      expect(c.canSubmit, true);
      c.dispose();
    });
  }
  testWidgets(
    'Approved payment with ACTIVE alone renders approved success composition',
    (tester) async {
      final parking = p.FakeParking()..existing = [pay.session()];
      final payment = pay.FakePayments()
        ..sent = () {
          parking.existing = [pay.session('ACTIVE')];
        };
      final c = pay.controller(parking: parking, payments: payment);
      await c.load('receipt');
      c.chooseMethod();
      await c.confirm();
      expect(c.activated, true);
      await tester.pumpWidget(await pay.page(c));
      await tester.pumpAndSettle();
      expect(find.text('Vehículo estacionado correctamente'), findsOneWidget);
      expect(find.text('Aceptar'), findsOneWidget);
      c.dispose();
    },
  );
  for (final outcome in ['PENDING', 'DECLINED', 'FAILED', 'UNKNOWN']) {
    test(
      'Payment $outcome cannot activate UI even with session ACTIVE',
      () async {
        final parking = p.FakeParking()..existing = [pay.session()];
        final payment = pay.FakePayments()
          ..outcome = outcome
          ..sent = () {
            parking.existing = [pay.session('ACTIVE')];
          };
        final c = pay.controller(parking: parking, payments: payment);
        await c.load('receipt');
        c.chooseMethod();
        await c.confirm();
        expect(c.activated, false);
        c.dispose();
      },
    );
  }
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets(
        'Home tabs and retained active controller $size text $scale',
        (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1;
          tester.platformDispatcher.textScaleFactorTestValue = scale;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
          final gateway = active.FakeActive()..empty = true;
          final c = active.controller(gateway);
          await tester.pumpWidget(
            await d.screen(HomePage(activeController: c)),
          );
          await tester.pumpAndSettle();
          expect(find.text('Estacionamiento activo'), findsNothing);
          final nav = find.byType(NavigationBar);
          expect(nav, findsOneWidget);
          await tester.tap(
            find.descendant(of: nav, matching: find.text('Notificaciones')),
          );
          await tester.pumpAndSettle();
          expect(
            find.text('Notificaciones estará disponible próximamente.'),
            findsOneWidget,
          );
          await tester.tap(
            find.descendant(of: nav, matching: find.text('Perfil')),
          );
          await tester.pumpAndSettle();
          expect(
            find.text('Perfil estará disponible próximamente.'),
            findsOneWidget,
          );
          await tester.binding.handlePopRoute();
          await tester.pumpAndSettle();
          expect(find.text('¿Qué deseas hacer?'), findsOneWidget);
          expect(tester.takeException(), isNull);
          c.dispose();
        },
      );
    }
  }
}

class ChangingCatalog extends d.FakeCatalog {
  bool occupied = false;
  @override
  Future<IdentifiedSpace> identify(String value, {required bool qr}) async {
    final space = projected(occupied ? 'OCCUPIED' : 'AVAILABLE');
    return IdentifiedSpace(
      space,
      ParkingCatalog([d.zone], [d.street], [space]),
    );
  }
}
