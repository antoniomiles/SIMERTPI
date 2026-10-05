import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/features/vehicles/data/vehicle_service.dart';
import 'package:simertpi_citizen_app/features/vehicles/presentation/vehicle_form_page.dart';
import 'package:simertpi_citizen_app/features/vehicles/presentation/vehicle_plate.dart';
import 'package:simertpi_citizen_app/features/vehicles/presentation/vehicles_page.dart';
import 'package:simertpi_citizen_app/features/vehicles/state/vehicles_controller.dart';

import 'vehicles_test.dart' show FakeVehicles, screen;

CitizenVehicle sample(int i, {bool active = true}) => CitizenVehicle(
  id: '$i',
  userId: 'owner',
  plate: i == 0 ? 'TBE1234' : 'ATL${768 + i}',
  active: active,
);

class LifecycleVehicles extends FakeVehicles {
  int deactivations = 0;
  Object? deactivateError;
  Completer<void>? deactivationPending;
  @override
  Future<void> deactivate(String id) async {
    deactivations++;
    if (deactivationPending != null) await deactivationPending!.future;
    if (deactivateError != null) throw deactivateError!;
    items = items.where((v) => v.id != id).toList();
  }
}

void main() {
  test('Visual spacing never changes functional identifiers; unknown formats retained', () {
    expect(visualPlate('TBE1234'), 'TBE 1234');
    expect(visualPlate('ATL768'), 'ATL 768');
    expect(visualPlate('AB-123'), 'AB-123');
    expect(sample(0).plate, 'TBE1234');
    expect(const VehicleInput(' tbe1234 ').toJson('owner')['plate'], 'TBE1234');
  });
  test('Uppercase input preserves cursor, paste, replacement and composition ranges', () {
    final formatter = UppercasePlateFormatter();
    for (final text in ['t', 'tb', 'tbe', 'tbe1', 'tbe12', 'tbe1234']) {
      final value = formatter.formatEditUpdate(
        TextEditingValue.empty,
        TextEditingValue(
          text: text,
          selection: TextSelection.collapsed(offset: text.length),
        ),
      );
      expect(value.text, text.toUpperCase());
      expect(value.selection.baseOffset, text.length);
    }
    final paste = formatter.formatEditUpdate(
      TextEditingValue.empty,
      const TextEditingValue(
        text: 'tbe1234',
        selection: TextSelection(baseOffset: 2, extentOffset: 5),
        composing: TextRange(start: 0, end: 3),
      ),
    );
    expect(paste.text, 'TBE1234');
    expect(
      paste.selection,
      const TextSelection(baseOffset: 2, extentOffset: 5),
    );
    expect(paste.composing, const TextRange(start: 0, end: 3));
    final expanded = formatter.formatEditUpdate(
      TextEditingValue.empty,
      const TextEditingValue(
        text: 'aé1',
        selection: TextSelection.collapsed(offset: 2),
      ),
    );
    expect(expanded.text, 'AÉ1');
    expect(expanded.selection.baseOffset, 2);
  });
  for (final count in [0, 1, 2, 3, 6]) {
    testWidgets(
      'Only the footer add control opens the existing form with $count vehicles',
      (tester) async {
        final fake = LifecycleVehicles()..items = List.generate(count, sample);
        final controller = VehiclesController(fake);
        await tester.pumpWidget(
          await screen(VehiclesPage(controller: controller)),
        );
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('vehicles-add-header')), findsNothing);
        expect(
          find.byKey(const ValueKey('vehicles-add-footer')),
          findsOneWidget,
        );
        for (final key in ['vehicles-add-footer']) {
          final control = find.byKey(ValueKey(key));
          await tester.ensureVisible(control);
          await tester.tap(control);
          await tester.pumpAndSettle();
          expect(find.byType(VehicleFormPage), findsOneWidget);
          await tester.pageBack();
          await tester.pumpAndSettle();
        }
        controller.dispose();
      },
    );
  }
  testWidgets(
    'Input uppercases paste and submits uppercase without graphic spaces',
    (tester) async {
      final fake = FakeVehicles()
        ..error = const AppFailure(FailureKind.invalidRequest);
      final controller = VehiclesController(fake);
      await tester.pumpWidget(
        await screen(VehicleFormPage(controller: controller)),
      );
      await tester.enterText(find.byType(TextFormField).first, 'tbe1234');
      expect(
        tester
            .widget<TextFormField>(find.byType(TextFormField).first)
            .controller!
            .text,
        'TBE1234',
      );
      await tester.ensureVisible(find.text('Guardar vehículo'));
      await tester.tap(find.text('Guardar vehículo'));
      await tester.pumpAndSettle();
      expect(fake.input!.plate, 'TBE1234');
      controller.dispose();
    },
  );
  testWidgets(
    'Menu, cancellation, pending confirmation and success refresh are safe',
    (tester) async {
      final fake = LifecycleVehicles()
        ..items = [sample(0)]
        ..deactivationPending = Completer<void>();
      final controller = VehiclesController(fake);
      await tester.pumpWidget(
        await screen(VehiclesPage(controller: controller)),
      );
      await tester.pumpAndSettle();
      expect(find.byType(VehiclePlate), findsOneWidget);
      expect(find.text('TBE 1234'), findsOneWidget);
      Future<void> open() async {
        await tester.tap(find.byTooltip('Opciones del vehículo TBE1234'));
        await tester.pumpAndSettle();
        expect(find.text('Dar de baja vehículo'), findsOneWidget);
        await tester.tap(find.text('Dar de baja vehículo'));
        await tester.pumpAndSettle();
      }

      await open();
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();
      expect(fake.deactivations, 0);
      await open();
      await tester.tap(find.text('Dar de baja'));
      await tester.pump();
      expect(find.text('Dando de baja…'), findsOneWidget);
      expect(controller.items, hasLength(1));
      await tester.tap(find.text('Dando de baja…'));
      expect(fake.deactivations, 1);
      fake.deactivationPending!.complete();
      await tester.pumpAndSettle();
      expect(controller.items, isEmpty);
      expect(fake.lists, 2);
      expect(find.text('Vehículo dado de baja.'), findsOneWidget);
      controller.dispose();
    },
  );
  for (final error in [
    const AppFailure(FailureKind.conflict),
    const AppFailure(FailureKind.offline),
  ]) {
    testWidgets('Failed deactivation retains vehicle: ${error.kind}', (
      tester,
    ) async {
      final fake = LifecycleVehicles()
        ..items = [sample(0)]
        ..deactivateError = error;
      final controller = VehiclesController(fake);
      await tester.pumpWidget(
        await screen(VehiclesPage(controller: controller)),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Opciones del vehículo TBE1234'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Dar de baja vehículo'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Dar de baja'));
      await tester.pumpAndSettle();
      expect(controller.items, hasLength(1));
      expect(
        controller.message,
        error.kind == FailureKind.conflict
            ? contains('estacionamiento activo')
            : contains('confirmar la baja'),
      );
      expect(find.byType(VehiclePlate), findsOneWidget);
      controller.dispose();
    });
  }
  test(
    'Inactive vehicles not selectable and concurrent deactivation submits once',
    () async {
      final fake = LifecycleVehicles()
        ..items = [sample(0), sample(1, active: false)]
        ..deactivationPending = Completer<void>();
      final controller = VehiclesController(fake);
      await controller.load();
      expect(controller.items, hasLength(1));
      final result = controller.deactivate('0');
      expect(await controller.deactivate('0'), false);
      expect(fake.deactivations, 1);
      fake.deactivationPending!.complete();
      expect(await result, true);
      expect(controller.items, isEmpty);
      controller.dispose();
    },
  );
  for (final size in [
    const Size(320, 640),
    const Size(390, 844),
    const Size(640, 320),
  ]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets(
        'Six plates, controls and semantics fit $size at $scale text',
        (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final semantics = tester.ensureSemantics();
          final controller = VehiclesController(
            LifecycleVehicles()..items = List.generate(6, sample),
          );
          await tester.pumpWidget(
            await screen(
              MediaQuery(
                data: MediaQueryData(textScaler: TextScaler.linear(scale)),
                child: VehiclesPage(controller: controller),
              ),
            ),
          );
          await tester.pumpAndSettle();
          expect(find.byType(VehiclePlate), findsNWidgets(6));
          expect(
            find.byTooltip('Opciones del vehículo TBE1234'),
            findsOneWidget,
          );
          expect(
            find.bySemanticsLabel(RegExp('Vehículo, placa T B E 1 2 3 4')),
            findsOneWidget,
          );
          await tester.ensureVisible(
            find.byKey(const ValueKey('vehicles-add-footer')),
          );
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
          controller.dispose();
          semantics.dispose();
        },
      );
    }
  }
}
