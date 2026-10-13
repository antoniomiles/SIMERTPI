import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/parking/presentation/parking_page.dart';
import 'package:simertpi_citizen_app/features/payments/presentation/payments_page.dart';
import 'package:simertpi_citizen_app/features/payments/state/payments_controller.dart';

import 'parking_test.dart' as p;
import 'payments_test.dart' as pay;
import 'support/auth_test_support.dart';

class CheckoutParking extends p.FakeParking {
  @override
  Future<ParkingReceipt> create(ParkingIntent intent) async {
    final result = await super.create(intent);
    existing = [result];
    return result;
  }
}

void main() {
  for (final dark in [false, true]) {
    for (final outcome in ['ABANDON', 'APPROVED', 'DECLINED', 'UNCERTAIN']) {
      testWidgets('CP24.4.1 full flow $outcome dark=$dark', (tester) async {
        final scope = await citizenApp() as AppScope;
        final parking = CheckoutParking();
        final selection = p.controller(parking);
        final gateway = pay.FakePayments()..outcome = outcome;
        if (outcome == 'UNCERTAIN') {
          gateway.failure = const AppFailure(
            FailureKind.timeout,
            outcomeUnknown: true,
          );
        }
        gateway.sent = () {
          if (outcome == 'APPROVED') parking.existing = [pay.session('ACTIVE')];
          if (outcome == 'DECLINED') {
            parking.existing = [pay.session('CANCELLED')];
          }
        };
        final payment = pay.controller(parking: parking, payments: gateway);
        await tester.pumpWidget(
          AppScope(
            config: scope.config,
            auth: scope.auth,
            child: MaterialApp(
              theme: AppTheme.forNight(dark),
              home: Builder(
                builder: (context) => Scaffold(
                  body: TextButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => ParkingPage(
                          selected: selection.space,
                          controller: selection,
                        ),
                      ),
                    ),
                    child: const Text('Seleccionar espacio'),
                  ),
                ),
              ),
              onGenerateRoute: (settings) =>
                  settings.name == AppRoute.payments.path
                  ? MaterialPageRoute<dynamic>(
                      settings: settings,
                      builder: (_) => PaymentsPage(
                        parkingController: selection,
                        controller: payment,
                      ),
                    )
                  : null,
            ),
          ),
        );
        await tester.tap(find.text('Seleccionar espacio'));
        await tester.pumpAndSettle();
        for (var i = 0; i < 2; i++) {
          await tester.ensureVisible(find.text('Continuar'));
          await tester.tap(find.text('Continuar'));
          await tester.pumpAndSettle();
        }
        await tester.tap(find.text('Seleccionar método de pago'));
        await tester.pumpAndSettle();
        expect(parking.creates, 0);
        expect(gateway.creates, 0);
        expect(find.text('Resumen del estacionamiento'), findsNothing);
        if (outcome == 'ABANDON') {
          await tester.tap(find.text('Inicio'));
          await tester.pumpAndSettle();
          expect(find.text('Seleccionar espacio'), findsOneWidget);
          expect(find.text('Selecciona tu método de pago'), findsNothing);
          expect(parking.creates, 0);
          expect(gateway.creates, 0);
        } else {
          await tester.tap(find.text('Método DEV'));
          await tester.pumpAndSettle();
          expect(parking.creates, 0);
          await tester.tap(find.text('Continuar con el pago'));
          await tester.pump();
          expect(find.text('Resumen del estacionamiento'), findsNothing);
          await tester.pumpAndSettle();
          expect(parking.creates, 0);
          expect(gateway.creates, 0);
          expect(find.text(r'Pagar $0,31'), findsOneWidget);
          await tester.ensureVisible(find.text(r'Pagar $0,31'));
          await tester.tap(find.text(r'Pagar $0,31'));
          await tester.pumpAndSettle();
          expect(parking.creates, 1);
          expect(gateway.creates, 1);
          expect(payment.activated, outcome == 'APPROVED');
          expect(
            payment.phase,
            outcome == 'UNCERTAIN'
                ? PaymentPhase.uncertain
                : PaymentPhase.result,
          );
          await payment.confirm();
          expect(gateway.creates, 1);
          expect(find.text('Resumen del estacionamiento'), findsNothing);
        }
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        selection.dispose();
        payment.dispose();
      });
    }
  }
}
