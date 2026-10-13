import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/parking/presentation/parking_page.dart';
import 'package:simertpi_citizen_app/features/payments/data/payment_contract.dart';
import 'package:simertpi_citizen_app/features/payments/presentation/payments_page.dart';
import 'package:simertpi_citizen_app/features/payments/state/payments_controller.dart';

import 'parking_test.dart' as p;
import 'payments_test.dart' as pay;
import 'support/auth_test_support.dart';

class ObservedParking extends p.FakeParking {
  int sessionReads = 0;
  @override
  Future<List<ParkingReceipt>> mine(String owner) async {
    sessionReads++;
    return super.mine(owner);
  }

  @override
  Future<ParkingReceipt> create(ParkingIntent intent) async {
    final result = await super.create(intent);
    existing = [result];
    return result;
  }
}

class ObservedPaymentStore extends pay.MemoryPaymentStore {
  int reads = 0, writes = 0;
  @override
  Future<PaymentIntent?> read() async {
    reads++;
    return super.read();
  }

  @override
  Future<void> write(PaymentIntent intent) async {
    writes++;
    await super.write(intent);
  }
}

class Flow {
  final parking = ObservedParking();
  final parkingStore = p.MemoryIntent();
  final paymentStore = ObservedPaymentStore();
  final gateway = pay.FakePayments();
  final confirmed = ValueNotifier<int>(0);
  late final selection = p.controller(parking, store: parkingStore);
  late final payment = pay.controller(
    parking: parking,
    payments: gateway,
    store: paymentStore,
  );

  Future<Widget> app({bool night = false}) async {
    final scope = await citizenApp() as AppScope;
    Widget home(BuildContext context) => Scaffold(
      body: TextButton(
        onPressed: () => Navigator.pushNamed(context, AppRoute.parking.path),
        child: const Text('Elegir espacio'),
      ),
    );
    return AppScope(
      config: scope.config,
      auth: scope.auth,
      parkingConfirmed: confirmed,
      child: MaterialApp(
        theme: AppTheme.forNight(night),
        home: Builder(builder: home),
        onGenerateRoute: (settings) => MaterialPageRoute<dynamic>(
          settings: settings,
          builder: (context) {
            if (settings.name == AppRoute.home.path) return home(context);
            if (settings.name == AppRoute.parking.path) {
              return ParkingPage(
                selected: selection.space,
                controller: selection,
              );
            }
            expect(settings.arguments, same(selection));
            return PaymentsPage(
              parkingController: selection,
              controller: payment,
            );
          },
        ),
      ),
    );
  }

  Future<void> methods(WidgetTester tester) async {
    await tester.tap(find.text('Elegir espacio'));
    await tester.pumpAndSettle();
    expect(find.text('Selecciona un vehículo'), findsOneWidget);
    await tap(tester, 'Continuar');
    expect(find.text('Tiempo de estacionamiento'), findsOneWidget);
    await tap(tester, 'Continuar');
    expect(find.text('Confirmar pago'), findsOneWidget);
    await tap(tester, 'Seleccionar método de pago');
    expect(find.text('Método de pago'), findsOneWidget);
    expect(find.text('Tarjeta de crédito o débito'), findsOneWidget);
    expect(find.text('Deuna'), findsOneWidget);
    expect(find.text('Próximamente'), findsNWidgets(2));
  }

  Future<void> summary(WidgetTester tester) async {
    await tap(tester, 'Método DEV');
    noOperations();
    await tap(tester, 'Continuar con el pago');
    expect(find.text('Confirmar pago'), findsOneWidget);
    expect(find.text('Método de pago'), findsNothing);
    expect(find.text('Zona'), findsOneWidget);
    expect(find.text('Espacio'), findsOneWidget);
    expect(find.text('Vehículo'), findsOneWidget);
    expect(find.text('Tiempo contratado'), findsOneWidget);
    expect(find.text('Total a pagar'), findsOneWidget);
    expect(find.text(r'Pagar $0,31'), findsOneWidget);
    expect(find.text('Solicitud pendiente de pago'), findsNothing);
    expect(find.text('Continuar al pago'), findsNothing);
    noOperations();
  }

  void noOperations() {
    expect(parking.creates, 0);
    expect(parking.existing, isEmpty);
    expect(selection.receipt, isNull);
    expect(parkingStore.value, isNull);
    expect(paymentStore.reads, 0);
    expect(paymentStore.writes, 0);
    expect(gateway.creates, 0);
    expect(gateway.gets, 0);
    expect(gateway.refreshes, 0);
    expect(selection.occupiedVehicles, isEmpty);
  }

  void dispose() {
    selection.dispose();
    payment.dispose();
    confirmed.dispose();
  }
}

Future<void> tap(WidgetTester tester, String label) async {
  await tester.ensureVisible(find.text(label));
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
}

void main() {
  for (final night in [false, true]) {
    for (final exit in [
      'Home en métodos',
      'Home en resumen',
      'Atrás Android',
      'Revisar datos',
    ]) {
      testWidgets(
        'CP24.5 navigation $exit night=$night has no business effects',
        (tester) async {
          final f = Flow();
          await tester.pumpWidget(await f.app(night: night));
          await f.methods(tester);
          final reads = f.parking.sessionReads;
          final quotes = f.parking.queries;
          if (exit != 'Home en métodos') await f.summary(tester);
          // Widget rebuilds and navigation cannot query pending transactions either.
          f.payment.chooseMethod();
          await tester.pumpAndSettle();
          expect(f.parking.sessionReads, reads);
          expect(f.parking.queries, quotes);
          if (exit.startsWith('Home')) {
            await tap(tester, 'Inicio');
            expect(find.text('Elegir espacio'), findsOneWidget);
          } else {
            if (exit == 'Revisar datos') {
              await tap(tester, 'Revisar datos');
            } else {
              await tester.binding.handlePopRoute();
              await tester.pumpAndSettle();
            }
            expect(find.text('Método de pago'), findsOneWidget);
            for (final title in [
              'Confirmar pago',
              'Tiempo de estacionamiento',
              'Selecciona un vehículo',
            ]) {
              await tester.binding.handlePopRoute();
              await tester.pumpAndSettle();
              expect(find.text(title), findsOneWidget);
            }
            await tester.tap(find.byType(BackButton));
            await tester.pumpAndSettle();
            expect(find.text('Elegir espacio'), findsOneWidget);
          }
          f.noOperations();
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox());
          f.dispose();
        },
      );
    }

    for (final outcome in ['APPROVED', 'DECLINED', 'UNCERTAIN', 'PENDING']) {
      testWidgets('CP24.5 Pay processing and result $outcome night=$night', (
        tester,
      ) async {
        final f = Flow();
        f.gateway.outcome = outcome;
        f.gateway.gate = Completer<void>();
        if (outcome == 'UNCERTAIN') {
          f.gateway.failure = const AppFailure(
            FailureKind.timeout,
            outcomeUnknown: true,
          );
        }
        f.gateway.sent = () {
          if (outcome == 'APPROVED') {
            f.parking.existing = [pay.session('ACTIVE')];
          }
          if (outcome == 'DECLINED') {
            f.parking.existing = [pay.session('CANCELLED')];
          }
        };
        await tester.pumpWidget(await f.app(night: night));
        await f.methods(tester);
        await f.summary(tester);
        await tester.ensureVisible(find.text(r'Pagar $0,31'));
        await tester.tap(find.text(r'Pagar $0,31'));
        // A second invocation while asynchronous checkout is underway is ignored.
        final duplicate = f.payment.startParking();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(find.text('Procesando pago'), findsOneWidget);
        expect(find.text('Procesando tu pago...'), findsOneWidget);
        expect(find.text('Solicitud pendiente de pago'), findsNothing);
        expect(f.parking.creates, 1);
        expect(f.gateway.creates, 1);
        expect(f.paymentStore.value, isNotNull);
        expect(f.parkingStore.value, isNull);
        f.gateway.gate!.complete();
        await duplicate;
        await tester.pumpAndSettle();
        expect(f.payment.activated, outcome == 'APPROVED');
        expect(f.parking.creates, 1);
        expect(f.gateway.creates, 1);
        await f.payment.startParking();
        expect(f.gateway.creates, 1);
        if (outcome == 'APPROVED') {
          expect(find.text('Pago confirmado'), findsOneWidget);
          expect(find.text('TEST-123'), findsOneWidget);
          await tap(tester, 'Aceptar');
          expect(f.confirmed.value, 1);
        } else if (outcome == 'DECLINED') {
          expect(find.text('Pago rechazado'), findsOneWidget);
          expect(f.parking.existing.single.status, 'CANCELLED');
        } else {
          expect(f.paymentStore.value, isNotNull);
          expect(find.text(r'Pagar $0,31'), findsNothing);
          expect(f.confirmed.value, 0);
        }
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      });
    }
  }

  for (final width in [320.0, 390.0]) {
    testWidgets('CP24.5 final title fits width=$width with large text', (
      tester,
    ) async {
      tester.view.physicalSize = Size(width, 844);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final f = Flow();
      await f.selection.load();
      await tester.pumpWidget(
        AppScope(
          config: (await citizenApp() as AppScope).config,
          auth: (await citizenApp() as AppScope).auth,
          child: MaterialApp(
            theme: AppTheme.light,
            home: PaymentsPage(
              parkingController: f.selection,
              controller: f.payment,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final title = find.descendant(
        of: find.byType(AppBar),
        matching: find.text('Confirmar pago'),
      );
      final paragraph = tester.renderObject<RenderParagraph>(title);
      expect(paragraph.didExceedMaxLines, false);
      expect(
        find.ancestor(of: title, matching: find.byType(FittedBox)),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
      f.noOperations();
      await tester.pumpWidget(const SizedBox());
      f.dispose();
    });
  }

  test(
    'CP24.5 an unresolved durable payment prevents even another checkout',
    () async {
      final f = Flow();
      await f.selection.load();
      final old = PaymentIntent(
        'fixture-citizen',
        'receipt',
        'TEST',
        createdAt: pay.clock,
      );
      f.paymentStore.value = old;
      f.parking.existing = [pay.session()];
      f.payment.stageDraft(f.selection);
      expect(f.paymentStore.reads, 0);
      await f.payment.startParking();
      expect(f.payment.phase, PaymentPhase.uncertain);
      expect(f.parking.creates, 0);
      expect(f.gateway.creates, 0);
      await f.payment.startParking();
      expect(f.parking.creates, 0);
      f.gateway.sent = () => f.parking.existing = [pay.session('ACTIVE')];
      await f.payment.check();
      expect(f.gateway.keys, [old.key]);
      expect(f.payment.activated, true);
      f.dispose();
    },
  );

  test('CP24.5 failed payment persistence reuses the same checkout after explicit review', () async {
    final f = Flow();
    await f.selection.load();
    f.payment.stageDraft(f.selection);
    f.paymentStore.error = Exception('storage failure');
    await f.payment.startParking();
    expect(f.parking.creates, 1);
    expect(f.gateway.creates, 0);
    expect(f.parkingStore.value, isNotNull);
    f.paymentStore.error = null;
    await f.payment.reviewStartedCheckout();
    f.gateway.sent = () => f.parking.existing = [pay.session('ACTIVE')];
    await f.payment.startParking();
    expect(f.parking.creates, 1);
    expect(f.gateway.creates, 1);
    expect(f.parkingStore.value, isNull);
    expect(f.payment.activated, true);
    f.dispose();
  });

  test('CP24.5 checkout stays durable across interruption before payment persistence', () async {
    final parking = ObservedParking();
    final store = p.MemoryIntent();
    final selection = p.controller(parking, store: store);
    await selection.load();
    await selection.create(retainIntent: true);
    expect(store.value, isNotNull);
    selection.dispose();
    final restored = p.controller(parking, store: store);
    await restored.load();
    expect(restored.phase.name, 'uncertain');
    final before = parking.sessionReads;
    await restored.recover(retainIntent: true);
    expect(parking.sessionReads, before + 1);
    expect(parking.creates, 1);
    expect(store.value, isNotNull);
    await restored.paymentIntentSaved();
    expect(store.value, isNull);
    restored.dispose();
  });
}
