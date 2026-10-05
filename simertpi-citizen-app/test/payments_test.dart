import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';

import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/payments/data/payment_contract.dart';
import 'package:simertpi_citizen_app/features/payments/data/payment_intent_store.dart';
import 'package:simertpi_citizen_app/features/payments/state/payments_controller.dart';
import 'package:simertpi_citizen_app/features/payments/presentation/payments_page.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';

import 'parking_test.dart' as p;
import 'discovery_test.dart' as d;
import 'support/auth_test_support.dart';

final clock = DateTime.utc(2026, 10, 4, 15, 1);
ParkingReceipt session([String status = 'PENDING_PAYMENT']) => p.receipt(
  ParkingIntent('fixture-citizen', 's', 'qr', 'v', 'tariff', 15),
  status: status,
);
Map<String, Object?> paymentData([String status = 'APPROVED']) => {
  'id': 'payment-fixture',
  'parkingSessionId': 'receipt',
  'provider': 'SANDBOX_STUB',
  'providerTransactionId': status == 'APPROVED' ? 'fixture-reference' : null,
  'amount': '0.31',
  'currency': 'USD',
  'status': status,
  'paymentMethod': 'TEST',
  'paidAt': status == 'APPROVED' ? clock.toIso8601String() : null,
  'createdAt': clock.toIso8601String(),
  'updatedAt': clock.toIso8601String(),
};

class MemoryPaymentStore implements PaymentIntentStore {
  PaymentIntent? value;
  Object? error;
  @override
  Future<PaymentIntent?> read() async => value;
  @override
  Future<void> write(PaymentIntent intent) async {
    if (error != null) throw error!;
    value = intent;
  }
}

class FakePayments implements PaymentGateway {
  int creates = 0, gets = 0, refreshes = 0;
  final keys = <String>[];
  String outcome = 'APPROVED';
  Object? failure;
  Completer<void>? gate;
  void Function()? sent;
  @override
  Future<CitizenPayment> create(PaymentIntent intent) async {
    creates++;
    keys.add(intent.key);
    sent?.call();
    if (gate != null) await gate!.future;
    if (failure != null) throw failure!;
    return CitizenPayment(paymentData(outcome));
  }

  @override
  Future<CitizenPayment> status(String id) async {
    gets++;
    if (failure != null) throw failure!;
    return CitizenPayment(paymentData(outcome));
  }

  @override
  Future<CitizenPayment> refresh(String id) async {
    refreshes++;
    if (failure != null) throw failure!;
    return CitizenPayment(paymentData(outcome));
  }
}

PaymentsController controller({
  FakePayments? payments,
  p.FakeParking? parking,
  MemoryPaymentStore? store,
  bool dev = true,
  DateTime Function()? now,
}) => PaymentsController(
  owner: 'fixture-citizen',
  catalog: d.FakeCatalog(),
  parking: parking ?? (p.FakeParking()..existing = [session()]),
  vehicles: p.FakeVehicles(),
  payments: payments ?? FakePayments(),
  store: store ?? MemoryPaymentStore(),
  dev: dev,
  now: now ?? () => clock,
);
Future<Widget> page(PaymentsController c, {double scale = 1}) async {
  final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
  await auth.login('fixture-citizen', 'fixture-password');
  return AppScope(
    config: AppConfig.parse(environment: 'dev'),
    auth: auth,
    child: MaterialApp(
      theme: AppTheme.light,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: child!,
      ),
      home: PaymentsPage(controller: c, sessionId: 'receipt'),
    ),
  );
}

void main() {
  test('Summary uses real pending session, owner vehicle and backend quote; DEV method must be selected', () async {
    final c = controller();
    await c.load('receipt');
    expect(c.phase, PaymentPhase.ready);
    expect(c.quote!.amount, '0.31');
    expect(c.vehicle!.plate, 'TEST-123');
    expect(c.canConfirm, false);
    c.chooseMethod();
    expect(c.canConfirm, true);
    c.dispose();
  });
  test('Non DEV never offers a fictitious production payment method', () async {
    final c = controller(dev: false);
    await c.load('receipt');
    c.chooseMethod();
    await c.confirm();
    expect(c.canConfirm, false);
    expect(c.intent, isNull);
    c.dispose();
  });
  test('Persist before POST, one concurrent confirmation, no optimistic activation', () async {
    final gateway = FakePayments()..gate = Completer<void>();
    final store = MemoryPaymentStore();
    final parking = p.FakeParking()..existing = [session()];
    final c = controller(payments: gateway, store: store, parking: parking);
    await c.load('receipt');
    c.chooseMethod();
    gateway.sent = () => expect(store.value?.key, gateway.keys.single);
    final first = c.confirm();
    await Future<void>.delayed(Duration.zero);
    expect(c.busy, true);
    expect(c.phase, PaymentPhase.processing);
    await c.confirm();
    expect(gateway.creates, 1);
    parking.existing = [session('ACTIVE')];
    gateway.gate!.complete();
    await first;
    expect(c.resultTitle, 'Pago confirmado');
    expect(c.session!.status, 'ACTIVE');
    expect(store.value!.paymentId, 'payment-fixture');
    c.dispose();
  });
  test(
    'Storage failure prevents POST and permits only explicit review',
    () async {
      final gateway = FakePayments();
      final c = controller(
        payments: gateway,
        store: MemoryPaymentStore()..error = StateError('fixture'),
      );
      await c.load('receipt');
      c.chooseMethod();
      await c.confirm();
      expect(gateway.creates, 0);
      expect(c.phase, PaymentPhase.ready);
      c.dispose();
    },
  );
  for (final outcome in [
    'DECLINED',
    'FAILED',
    'PENDING',
    'PROCESSING',
    'CANCELLED',
    'REFUNDED',
  ]) {
    test(
      'Backend $outcome is never rendered as success and recovery cannot mint a second payment',
      () async {
        final gateway = FakePayments()..outcome = outcome;
        final c = controller(payments: gateway);
        await c.load('receipt');
        c.chooseMethod();
        await c.confirm();
        expect(c.phase, PaymentPhase.result);
        expect(c.resultTitle, isNot('Pago confirmado'));
        if (outcome == 'PENDING' || outcome == 'PROCESSING') {
          await c.check();
          expect(gateway.gets, 1);
          expect(gateway.refreshes, 1);
          expect(gateway.creates, 1);
        }
        expect(c.canRetry, outcome == 'DECLINED' || outcome == 'FAILED');
        c.dispose();
      },
    );
  }
  test('Timeout keeps key; explicit recovery and restart replay same body/key within TTL', () async {
    final gateway = FakePayments()
      ..failure = const AppFailure(FailureKind.timeout, outcomeUnknown: true);
    final store = MemoryPaymentStore();
    final c = controller(payments: gateway, store: store);
    await c.load('receipt');
    c.chooseMethod();
    await c.confirm();
    expect(c.phase, PaymentPhase.uncertain);
    final key = store.value!.key;
    expect(c.canConfirm, false);
    c.dispose();
    gateway.failure = null;
    final restored = controller(payments: gateway, store: store);
    await restored.load();
    expect(gateway.creates, 1);
    expect(restored.phase, PaymentPhase.uncertain);
    await restored.check();
    expect(gateway.keys, [key, key]);
    expect(restored.phase, PaymentPhase.result);
    restored.dispose();
  });
  test(
    'Expired unknown key never replays POST or silently generates another key',
    () async {
      final store = MemoryPaymentStore()
        ..value = PaymentIntent(
          'fixture-citizen',
          'receipt',
          'TEST',
          createdAt: clock.subtract(const Duration(hours: 24)),
        );
      final gateway = FakePayments();
      final c = controller(store: store, payments: gateway);
      await c.load();
      await c.check();
      expect(gateway.creates, 0);
      expect(c.phase, PaymentPhase.uncertain);
      expect(c.message, contains('revisión'));
      c.dispose();
    },
  );
  test('Known payment ID restores using GET only; no automatic provider query or POST', () async {
    final store = MemoryPaymentStore()
      ..value = PaymentIntent(
        'fixture-citizen',
        'receipt',
        'TEST',
        createdAt: clock,
        paymentId: 'payment-fixture',
      );
    final gateway = FakePayments()..outcome = 'PENDING';
    final c = controller(store: store, payments: gateway);
    await c.load();
    expect(gateway.gets, 1);
    expect(gateway.creates, 0);
    expect(gateway.refreshes, 0);
    c.dispose();
  });
  test('Explicit retry after confirmed rejection revalidates and creates a new attempt only after confirmation', () async {
    final gateway = FakePayments()..outcome = 'DECLINED';
    final c = controller(payments: gateway);
    await c.load('receipt');
    c.chooseMethod();
    await c.confirm();
    final key = gateway.keys.single;
    await c.reviewRetry();
    expect(gateway.creates, 1);
    expect(c.phase, PaymentPhase.ready);
    gateway.outcome = 'APPROVED';
    c.chooseMethod();
    await c.confirm();
    expect(gateway.creates, 2);
    expect(gateway.keys.last, isNot(key));
    c.dispose();
  });
  test('Quote change forces review and prevents a POST', () async {
    final parking = p.FakeParking()..existing = [session()];
    final gateway = FakePayments();
    final c = controller(parking: parking, payments: gateway);
    await c.load('receipt');
    c.chooseMethod();
    parking.amount = '0.62';
    await c.confirm();
    expect(gateway.creates, 0);
    expect(c.canConfirm, false);
    expect(c.message, contains('condiciones cambiaron'));
    c.dispose();
  });
  test('Foreign ownership and expired session fail closed', () async {
    final parking = p.FakeParking()..existing = [session()];
    final c = controller(
      parking: parking,
      now: () => clock.add(const Duration(hours: 1)),
    );
    await c.load('receipt');
    expect(c.phase, PaymentPhase.error);
    c.dispose();
    final store = MemoryPaymentStore()
      ..value = PaymentIntent('another-user', 'receipt', 'TEST');
    final other = controller(store: store);
    await other.load();
    expect(other.canConfirm, false);
    other.dispose();
  });
  test('Confirmed terminal payment can open another pending request without losing its durable record', () async {
    final gateway = FakePayments();
    final store = MemoryPaymentStore();
    final parking = p.FakeParking()..existing = [session()];
    final c = controller(payments: gateway, parking: parking, store: store);
    await c.load('receipt');
    c.chooseMethod();
    await c.confirm();
    final oldKey = store.value!.key;
    parking.existing = [
      ParkingReceipt({
        'id': 'another-pending',
        'userId': 'fixture-citizen',
        'parkingSpaceId': 's',
        'vehicleId': 'v',
        'tariffId': 'tariff',
        'status': 'PENDING_PAYMENT',
        'startedAt': '2026-10-04T15:00:00Z',
        'expectedEndAt': '2026-10-04T15:15:00Z',
      }),
    ];
    await c.pendingRequests();
    expect(c.phase, PaymentPhase.selection);
    expect(c.choices.single.id, 'another-pending');
    await c.select('another-pending');
    expect(c.phase, PaymentPhase.ready);
    expect(store.value!.key, oldKey);
    expect(gateway.creates, 1);
    c.dispose();
  });
  test('Parser rejects unknown statuses and preserves decimal lexemes', () {
    expect(
      () => CitizenPayment(paymentData('UNKNOWN')),
      throwsA(isA<AppFailure>()),
    );
    final data = parkingJson(
      jsonEncode(paymentData()).replaceFirst('"0.31"', '123456789.1234567'),
    ) as Map;
    expect(CitizenPayment(data).amount, '123456789.1234567');
    expect(
      () => CitizenPayment({...paymentData(), 'paidAt': null}),
      throwsA(isA<AppFailure>()),
    );
  });
  test('Secure payment storage persists scoped intent only; no password/card/token', () async {
    FlutterSecureStorage.setMockInitialValues({});
    final store = SecurePaymentIntentStore('dev:fixture-api:fixture-citizen');
    final value = PaymentIntent(
      'fixture-citizen',
      'receipt',
      'TEST',
      createdAt: clock,
    );
    await store.write(value);
    expect((await store.read())!.key, value.key);
    final different = SecurePaymentIntentStore(
      'prod:fixture-api:fixture-citizen',
    );
    expect(await different.read(), isNull);
    final payload = await store.storage.read(key: store.key);
    expect(jsonDecode(payload!).keys.toSet(), {
      'parkingSessionId',
      'paymentMethod',
      'owner',
      'key',
      'createdAt',
    });
  });
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets(
      'Payment summary and confirmation fit $size, text 200% and keyboard',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.viewInsets = const FakeViewPadding(bottom: 160);
        addTearDown(tester.view.resetViewInsets);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final c = controller();
        await tester.pumpWidget(await page(c, scale: 2));
        await tester.pumpAndSettle();
        expect(
          find.textContaining('Importe estimado: USD 0.31'),
          findsOneWidget,
        );
        await tester.ensureVisible(find.text('Seleccionar método DEV'));
        await tester.tap(find.text('Seleccionar método DEV'));
        await tester.pump();
        await tester.ensureVisible(find.text('Continuar'));
        await tester.tap(find.text('Continuar'));
        await tester.pumpAndSettle();
        expect(find.text('Confirmar pago DEV'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        c.dispose();
      },
    );
  }
  testWidgets(
    'Payment submit shows processing and disables a double tap until backend responds',
    (tester) async {
      final gateway = FakePayments()..gate = Completer<void>();
      final c = controller(payments: gateway);
      await tester.pumpWidget(await page(c));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Seleccionar método DEV'));
      await tester.tap(find.text('Seleccionar método DEV'));
      await tester.pump();
      await tester.ensureVisible(find.text('Continuar'));
      await tester.tap(find.text('Continuar'));
      await tester.pump();
      await tester.ensureVisible(find.text('Confirmar pago DEV'));
      await tester.tap(find.text('Confirmar pago DEV'));
      await tester.pump();
      expect(find.text('Procesando pago...'), findsOneWidget);
      expect(gateway.creates, 1);
      expect(
        tester
            .widget<FilledButton>(
              find.ancestor(
                of: find.text('Procesando pago...'),
                matching: find.byType(FilledButton),
              ),
            )
            .onPressed,
        isNull,
      );
      gateway.gate!.complete();
      await tester.pumpAndSettle();
      expect(find.text('Pago confirmado'), findsOneWidget);
      expect(find.text('Referencia SIMERTPI: payment-fixture'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
  testWidgets(
    'Payment recovery route is protected and cannot bypass session guards',
    (tester) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await tester.pumpWidget(authTestApp(auth, route: AppRoute.payments));
      await tester.pumpAndSettle();
      expect(find.text('Inicia sesión'), findsOneWidget);
      expect(find.byType(PaymentsPage), findsNothing);
    },
  );
}
