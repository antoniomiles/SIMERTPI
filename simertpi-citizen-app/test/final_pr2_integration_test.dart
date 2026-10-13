import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/payments/presentation/payments_page.dart';
import 'package:simertpi_citizen_app/features/payments/state/payments_controller.dart';
import 'parking_test.dart' as p;
import 'payments_test.dart' as pay;
import 'cp2453_auth_test.dart' as bio;

class IntegratedParking extends p.FakeParking {
  @override
  Future<ParkingReceipt> create(ParkingIntent intent) async {
    final result = await super.create(intent);
    existing = [result];
    return result;
  }
}

void main() {
  testWidgets('PR2 password, biometric, inactivity, selections and final Pay integrate', (tester) async {
    final f = bio.Fixture();
    addTearDown(f.dispose);
    await f.login();
    expect(await f.auth.enableBiometrics(), true);
    f.lock.lock();
    expect(f.auth.isLocked, true);
    expect(await f.auth.unlockBiometrics(), true);
    expect(f.credentials.unlocks, 1);
    await f.auth.logout();
    expect(f.auth.isAuthenticated, false);
    expect(await f.auth.loginBiometrics(f.signing.saved.single), true);
    final parking = IntegratedParking();
    final selection = p.controller(parking);
    addTearDown(selection.dispose);
    await selection.load();
    selection.choose('v');
    selection.changedDuration('30');
    await selection.estimate();
    expect(selection.canSubmit, true);
    final gateway = pay.FakePayments();
    final store = pay.MemoryPaymentStore();
    final payment = pay.controller(parking: parking, payments: gateway, store: store);
    addTearDown(payment.dispose);
    gateway.sent = () {
      expect(store.value!.key, gateway.keys.single);
      parking.existing = [p.receipt(parking.intent!, status: 'ACTIVE')];
    };
    await tester.pumpWidget(AppScope(
      config: AppConfig.parse(environment: 'dev'), auth: f.auth,
      child: MaterialApp(home: PaymentsPage(controller: payment, parkingController: selection)),
    ));
    await tester.pumpAndSettle();
    expect(parking.creates, 0);
    expect(gateway.creates, 0);
    await tester.ensureVisible(find.textContaining('Pagar '));
    await tester.tap(find.textContaining('Pagar '));
    await tester.pumpAndSettle();
    expect(parking.creates, 1);
    expect(parking.intent!.minutes, 30);
    expect(parking.intent!.vehicleId, 'v');
    expect(gateway.creates, 1);
    expect(payment.activated, true);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    f.lock.detach();
  });

  test('PR2 repeated staging and concurrent Pay create only one checkout and payment', () async {
    final parking = IntegratedParking()..gate = Completer<void>();
    final selection = p.controller(parking);
    final gateway = pay.FakePayments()..outcome = 'PENDING';
    final payment = pay.controller(parking: parking, payments: gateway);
    addTearDown(selection.dispose);
    addTearDown(payment.dispose);
    await selection.load();
    for (var i = 0; i < 3; i++) {
      payment.stageDraft(selection);
    }
    expect(parking.creates, 0);
    expect(gateway.creates, 0);
    final first = payment.startParking();
    await Future<void>.delayed(Duration.zero);
    await payment.startParking();
    expect(parking.creates, 1);
    parking.gate!.complete();
    await first;
    expect(gateway.creates, 1);
    expect(payment.activated, false);
    await payment.startParking();
    expect(parking.creates, 1);
    expect(gateway.creates, 1);
  });

  test('PR2 checkout timeout is recovered by GET before a payment can start', () async {
    final parking = IntegratedParking()..createFailure = const AppFailure(FailureKind.timeout, outcomeUnknown: true);
    final parkingStore = p.MemoryIntent();
    final selection = p.controller(parking, store: parkingStore);
    final gateway = pay.FakePayments();
    final payment = pay.controller(parking: parking, payments: gateway);
    addTearDown(selection.dispose);
    addTearDown(payment.dispose);
    await selection.load();
    payment.stageDraft(selection);
    await payment.startParking();
    expect(payment.phase, PaymentPhase.uncertain);
    expect(gateway.creates, 0);
    await payment.startParking();
    expect(parking.creates, 1);
    parking.existing = [p.receipt(parkingStore.value!)];
    await payment.reviewStartedCheckout();
    expect(parking.creates, 1);
    expect(gateway.creates, 0);
    payment.chooseMethod();
    await payment.startParking();
    expect(parking.creates, 1);
    expect(gateway.creates, 1);
  });

  test('PR2 persisted payment timeout blocks a new draft until explicit same-key recovery', () async {
    final parking = IntegratedParking();
    final selection = p.controller(parking);
    final gateway = pay.FakePayments()..failure = const AppFailure(FailureKind.timeout, outcomeUnknown: true);
    final store = pay.MemoryPaymentStore();
    final payment = pay.controller(parking: parking, payments: gateway, store: store);
    addTearDown(selection.dispose);
    addTearDown(payment.dispose);
    await selection.load();
    payment.stageDraft(selection);
    await payment.startParking();
    final key = store.value!.key;
    expect(payment.phase, PaymentPhase.uncertain);
    final restored = pay.controller(parking: parking, payments: gateway, store: store);
    addTearDown(restored.dispose);
    restored.stageDraft(selection);
    await restored.startParking();
    expect(restored.phase, PaymentPhase.uncertain);
    expect(parking.creates, 1);
    expect(gateway.creates, 1);
    gateway.failure = null;
    await restored.check();
    expect(gateway.keys, [key, key]);
    expect(parking.creates, 1);
    expect(restored.activated, false);
  });

  test('PR2 storage failure after checkout never sends payment and retains checkout recovery', () async {
    final parking = IntegratedParking();
    final parkingStore = p.MemoryIntent();
    final selection = p.controller(parking, store: parkingStore);
    final gateway = pay.FakePayments();
    final store = pay.MemoryPaymentStore()..error = StateError('unavailable');
    final payment = pay.controller(parking: parking, payments: gateway, store: store);
    addTearDown(selection.dispose);
    addTearDown(payment.dispose);
    await selection.load();
    payment.stageDraft(selection);
    await payment.startParking();
    expect(parking.creates, 1);
    expect(gateway.creates, 0);
    expect(parkingStore.value, isNotNull);
    expect(payment.activated, false);
  });
}
