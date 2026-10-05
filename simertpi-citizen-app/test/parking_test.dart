import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';
import 'package:simertpi_citizen_app/features/vehicles/data/vehicle_service.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_intent_store.dart';
import 'package:simertpi_citizen_app/features/parking/state/parking_controller.dart';
import 'package:simertpi_citizen_app/features/parking/presentation/parking_page.dart';

import 'discovery_test.dart' as d;
import 'support/auth_test_support.dart';

const vehicle = CitizenVehicle(
  id: 'v',
  userId: 'fixture-citizen',
  plate: 'TEST-123',
  active: true,
);
Map<String, Object?> ruleFixture([int? minutes]) => {
  'spaceId': 's',
  'zoneId': 'z',
  'evaluatedAt': '2026-10-04T10:00:00-05:00',
  'operational': true,
  'chargeable': true,
  'holiday': false,
  'reasonCode': 'RULES_RESOLVED',
  'minimumFractionMinutes': 15,
  'maximumContinuousMinutes': 180,
  'gracePeriodMinutes': 7,
  'currency': 'USD',
  'applicableTariff': 'TEST-T',
  'unitPrice': '1.23',
  'unitDurationMinutes': 60,
  'requestedDurationMinutes': minutes,
  'billedDurationMinutes': minutes == null ? null : (minutes == 16 ? 30 : 15),
  'calculatedAmount': minutes == null ? null : '0.31',
  'expiresAt': minutes == null ? null : '2026-10-04T15:15:00Z',
  'applicableSchedule': {
    'startTime': '09:00:00',
    'endTime': '18:00:00',
    'source': 'ZONE',
  },
};
ParkingReceipt receipt(
  ParkingIntent i, {
  String status = 'PENDING_PAYMENT',
  String? owner,
}) => ParkingReceipt({
  'id': 'receipt',
  'userId': owner ?? i.owner,
  'parkingSpaceId': i.spaceId,
  'vehicleId': i.vehicleId,
  'tariffId': i.tariffId,
  'status': status,
  'startedAt': '2026-10-04T15:00:00Z',
  'expectedEndAt': DateTime.utc(
    2026,
    10,
    4,
    15,
  ).add(Duration(minutes: i.minutes)).toIso8601String(),
});

class MemoryIntent implements ParkingIntentStore {
  ParkingIntent? value;
  Object? failure;
  @override
  Future<ParkingIntent?> read() async => value;
  @override
  Future<void> write(ParkingIntent i) async {
    if (failure != null) throw failure!;
    value = i;
  }

  @override
  Future<void> clear() async {
    value = null;
  }
}

class FakeVehicles implements VehicleGateway {
  List<CitizenVehicle> items = [vehicle];
  @override
  Future<List<CitizenVehicle>> list() async => items;
  @override
  Future<CitizenVehicle> create(VehicleInput input) async => vehicle;
}

class FakeParking implements ParkingGateway {
  int creates = 0, queries = 0;
  String amount = '0.31';
  bool open = true;
  Object? failure, createFailure;
  Completer<void>? gate;
  ParkingIntent? intent;
  List<ParkingReceipt> existing = [];
  @override
  Future<ParkingRules> rules(String id, [int? minutes]) async {
    queries++;
    if (failure != null) throw failure!;
    return ParkingRules({
      ...ruleFixture(minutes),
      'operational': open,
      if (!open) 'reasonCode': 'OUTSIDE_OPERATION_HOURS',
      if (minutes != null) 'calculatedAmount': amount,
    });
  }

  @override
  Future<String> tariffId(String code) async => 'tariff';
  @override
  Future<ParkingReceipt> create(ParkingIntent i) async {
    creates++;
    intent = i;
    if (gate != null) await gate!.future;
    if (createFailure != null) throw createFailure!;
    return receipt(i);
  }

  @override
  Future<List<ParkingReceipt>> mine(String owner) async => existing;
}

ParkingController controller(
  FakeParking p, {
  d.FakeCatalog? catalog,
  FakeVehicles? vehicles,
  MemoryIntent? store,
}) => ParkingController(
  space: IdentifiedSpace(d.space, d.fixture()),
  owner: 'fixture-citizen',
  catalog: catalog ?? d.FakeCatalog(),
  vehicles: vehicles ?? FakeVehicles(),
  parking: p,
  store: store ?? MemoryIntent(),
);
Future<Widget> page(ParkingController c) async {
  final scope = await citizenApp() as AppScope;
  return AppScope(
    config: scope.config,
    auth: scope.auth,
    child: MaterialApp(
      theme: AppTheme.light,
      home: ParkingPage(selected: c.space, controller: c),
      onGenerateRoute: (s) => AppRouter.generate(s, scope.auth),
    ),
  );
}

void main() {
  test('Exact decimal parsing never converts backend money through double', () {
    final data = parkingJson(
      '{"amount":9999999999.99,"unitPrice":0.10,"minutes":31,"label":"0.1"}',
    ) as Map;
    expect(money(data['amount']), '9999999999.99');
    expect(money(data['unitPrice']), '0.10');
    expect(data['minutes'], 31);
    expect(data['label'], '0.1');
    expect(() => money(-1), throwsA(isA<AppFailure>()));
    expect(() => money(0.1), throwsA(isA<AppFailure>()));
  });
  test('Rules reject malformed limits and unknown non-operational reasons fail closed', () {
    expect(
      () => ParkingRules({...ruleFixture(), 'minimumFractionMinutes': 0}),
      throwsA(isA<AppFailure>()),
    );
    final q = ParkingRules({
      ...ruleFixture(),
      'operational': false,
      'reasonCode': 'UNKNOWN',
    });
    expect(q.quoted, false);
    expect(ruleMessage(q.reason), isNot(contains('UNKNOWN')));
  });
  test('Load revalidates space, selects sole real vehicle and uses backend minimum/quote', () async {
    final p = FakeParking(), cat = d.FakeCatalog();
    final c = controller(p, catalog: cat);
    expect(c.phase, ParkingPhase.initial);
    final f = c.load();
    expect(c.phase, ParkingPhase.loading);
    await f;
    expect(cat.resolutions, 1);
    expect(c.minutes, 15);
    expect(c.vehicleId, 'v');
    expect(c.canSubmit, true);
    expect(c.quote!.amount, '0.31');
    expect(c.quote!.grace, 7);
    c.dispose();
  });
  test('No vehicles, multiple vehicles and foreign ownership never invent a principal', () async {
    final v = FakeVehicles()..items = [];
    final c = controller(FakeParking(), vehicles: v);
    await c.load();
    expect(c.canSubmit, false);
    v.items = [
      vehicle,
      const CitizenVehicle(
        id: 'v2',
        userId: 'fixture-citizen',
        plate: 'OTHER',
        active: true,
      ),
    ];
    await c.load();
    expect(c.vehicleId, isNull);
    c.choose('v2');
    expect(c.canSubmit, true);
    v.items = [
      const CitizenVehicle(
        id: 'foreign',
        userId: 'another',
        plate: 'OTHER',
        active: true,
      ),
    ];
    await c.load();
    expect(c.phase, ParkingPhase.error);
    expect(c.canSubmit, false);
    c.dispose();
  });
  test('Inactive location and unavailable rules block continuation', () async {
    final cat = d.FakeCatalog()
      ..data = ParkingCatalog(
        [const ParkingZone('z', 'Z', 'Zone', false)],
        [d.street],
        [d.space],
      );
    final c = controller(FakeParking(), catalog: cat);
    await c.load();
    expect(c.canSubmit, false);
    expect(c.message, contains('habilitada'));
    c.dispose();
    final p = FakeParking()..open = false;
    final closed = controller(p);
    await closed.load();
    expect(closed.canSubmit, false);
    closed.dispose();
  });
  test('Invalid duration is local validation; valid non-multiple is quoted by backend', () async {
    final p = FakeParking();
    final c = controller(p);
    await c.load();
    final calls = p.queries;
    for (final value in ['0', '181', 'invalid']) {
      c.changedDuration(value);
      await c.estimate();
      expect(p.queries, calls);
      expect(c.canSubmit, false);
    }
    c.changedDuration('16');
    await c.estimate();
    expect(c.minutes, 16);
    expect(c.canSubmit, true);
    expect(p.queries, calls + 1);
    c.dispose();
  });
  test(
    'Changed rules require another review and never create with old estimate',
    () async {
      final p = FakeParking();
      final c = controller(p);
      await c.load();
      p.amount = '2.34';
      await c.create();
      expect(p.creates, 0);
      expect(c.quote!.amount, '2.34');
      expect(c.message, contains('cambiaron'));
      c.dispose();
    },
  );
  test(
    'Vehicle disabled before confirmation and space deactivated do not POST',
    () async {
      final v = FakeVehicles();
      final p = FakeParking();
      final c = controller(p, vehicles: v);
      await c.load();
      v.items = [
        const CitizenVehicle(
          id: 'v',
          userId: 'fixture-citizen',
          plate: 'TEST-123',
          active: false,
        ),
      ];
      await c.create();
      expect(p.creates, 0);
      c.dispose();
      final cat = d.FakeCatalog();
      final other = controller(p, catalog: cat);
      await other.load();
      cat.data = ParkingCatalog(
        [const ParkingZone('z', 'Z', 'Zone', false)],
        [d.street],
        [d.space],
      );
      await other.create();
      expect(other.phase, ParkingPhase.conflict);
      expect(p.creates, 0);
      other.dispose();
    },
  );
  test('Double action produces one persisted intent and PENDING_PAYMENT, never ACTIVE', () async {
    final p = FakeParking()..gate = Completer<void>();
    final store = MemoryIntent();
    final c = controller(p, store: store);
    await c.load();
    final first = c.create();
    await Future<void>.delayed(Duration.zero);
    await c.create();
    expect(p.creates, 1);
    expect(store.value!.key, p.intent!.key);
    expect(
      p.intent!.request.keys,
      unorderedEquals([
        'parkingSpaceQrCode',
        'vehicleId',
        'tariffId',
        'durationMinutes',
      ]),
    );
    p.gate!.complete();
    await first;
    expect(c.phase, ParkingPhase.created);
    expect(c.receipt!.status, 'PENDING_PAYMENT');
    expect(store.value, isNull);
    c.dispose();
  });
  test('409 is explicit conflict and safe retry must revalidate', () async {
    final p = FakeParking()
      ..createFailure = const AppFailure(FailureKind.conflict);
    final c = controller(p);
    await c.load();
    await c.create();
    expect(c.phase, ParkingPhase.conflict);
    expect(c.canSubmit, false);
    expect(c.message, isNot(contains('409')));
    c.dispose();
  });
  test(
    'Unknown POST result survives restart and recovery is GET only',
    () async {
      final p = FakeParking()
        ..createFailure = const AppFailure(
          FailureKind.timeout,
          outcomeUnknown: true,
        );
      final store = MemoryIntent();
      final c = controller(p, store: store);
      await c.load();
      await c.create();
      expect(c.phase, ParkingPhase.uncertain);
      expect(store.value, isNotNull);
      await c.create();
      expect(p.creates, 1);
      c.dispose();
      final restored = controller(p, store: store);
      await restored.load();
      expect(restored.phase, ParkingPhase.uncertain);
      await restored.recover();
      expect(restored.phase, ParkingPhase.uncertain);
      expect(p.creates, 1);
      p.existing = [receipt(store.value!)];
      await restored.recover();
      expect(restored.phase, ParkingPhase.created);
      expect(p.creates, 1);
      expect(store.value, isNull);
      restored.dispose();
    },
  );
  test('Storage failure prevents POST; query failure cannot enable offline creation', () async {
    final p = FakeParking();
    final store = MemoryIntent()..failure = Exception('fixture-secret');
    final c = controller(p, store: store);
    await c.load();
    await c.create();
    expect(p.creates, 0);
    expect(c.message, isNot(contains('fixture-secret')));
    c.dispose();
    p.failure = const AppFailure(FailureKind.offline);
    final offline = controller(p);
    await offline.load();
    expect(offline.phase, ParkingPhase.error);
    expect(offline.canSubmit, false);
    offline.dispose();
  });
  testWidgets('Vehicle/duration summary and contextual submission are honest', (
    tester,
  ) async {
    final c = controller(FakeParking());
    await tester.pumpWidget(await page(c));
    await tester.pumpAndSettle();
    expect(find.text('TEST-123'), findsOneWidget);
    expect(find.textContaining('USD 0.31'), findsOneWidget);
    await tester.ensureVisible(find.text('Revisar resumen'));
    await tester.tap(find.text('Revisar resumen'));
    await tester.pumpAndSettle();
    expect(find.text('Resumen de la solicitud'), findsOneWidget);
    await tester.ensureVisible(find.text('Preparar solicitud'));
    await tester.tap(find.text('Preparar solicitud'));
    await tester.pumpAndSettle();
    expect(find.text('Solicitud pendiente de pago'), findsOneWidget);
    expect(find.textContaining('aprobado'), findsOneWidget);
    c.dispose();
  });
  testWidgets(
    'Empty vehicles provides registration; rules error provides retry',
    (tester) async {
      final c = controller(FakeParking(), vehicles: FakeVehicles()..items = []);
      await tester.pumpWidget(await page(c));
      await tester.pumpAndSettle();
      expect(find.text('No tienes vehículos habilitados.'), findsOneWidget);
      expect(find.text('Registrar vehículo'), findsOneWidget);
      c.dispose();
      await tester.pumpWidget(const SizedBox());
      final e = controller(
        FakeParking()..failure = const AppFailure(FailureKind.unavailable),
      );
      await tester.pumpWidget(await page(e));
      await tester.pumpAndSettle();
      expect(find.text('Reintentar'), findsOneWidget);
      e.dispose();
    },
  );
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets(
      'Parking form and summary fit $size with text 200% and keyboard',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = 2;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        final c = controller(FakeParking());
        await tester.pumpWidget(await page(c));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.ensureVisible(find.byType(TextFormField));
        await tester.tap(find.byType(TextFormField));
        await tester.enterText(find.byType(TextFormField), '16');
        await tester.pumpAndSettle();
        expect(c.canSubmit, false);
        await tester.ensureVisible(find.text('Consultar importe'));
        await tester.tap(find.text('Consultar importe'));
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('Revisar resumen'));
        await tester.tap(find.text('Revisar resumen'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        c.dispose();
      },
    );
  }
}
