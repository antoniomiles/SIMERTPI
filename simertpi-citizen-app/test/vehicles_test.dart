import 'dart:async';
import 'dart:io';
import 'dart:convert';

import 'package:flutter/services.dart';

import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/core/widgets/app_skeleton.dart';
import 'package:simertpi_citizen_app/core/widgets/app_buttons.dart';
import 'package:simertpi_citizen_app/features/home/home_page.dart';
import 'package:simertpi_citizen_app/features/vehicles/data/vehicle_service.dart';
import 'package:simertpi_citizen_app/features/vehicles/state/vehicles_controller.dart';
import 'package:simertpi_citizen_app/features/vehicles/presentation/vehicles_page.dart';
import 'package:simertpi_citizen_app/features/vehicles/presentation/vehicle_form_page.dart';

import 'support/auth_test_support.dart';

const vehicle = CitizenVehicle(
  id: 'fixture-id',
  userId: 'fixture-citizen',
  plate: 'TEST-123',
  brand: 'Fixture',
  model: 'Vehicle',
  active: true,
);

class FakeVehicles implements VehicleGateway {
  @override
  Future<void> deactivate(String id) async {}
  List<CitizenVehicle> items = [];
  int lists = 0, creates = 0;
  Object? error;
  Completer<void>? pending;
  VehicleInput? input;
  @override
  Future<List<CitizenVehicle>> list() async {
    lists++;
    if (pending != null) await pending!.future;
    if (error != null) throw error!;
    return items;
  }

  @override
  Future<CitizenVehicle> create(VehicleInput value) async {
    creates++;
    input = value;
    if (pending != null) await pending!.future;
    if (error != null) throw error!;
    return vehicle;
  }
}

Future<Widget> screen(Widget child) async {
  final auth = await citizenApp();
  final scope = auth as AppScope;
  return AppScope(
    config: AppConfig.parse(environment: 'dev'),
    auth: scope.auth,
    child: MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      home: child,
    ),
  );
}

void main() {
  test('Controller distinguishes loading, empty, success and error; refresh retains content', () async {
    final fake = FakeVehicles()..pending = Completer<void>();
    final controller = VehiclesController(fake);
    final load = controller.load();
    expect(controller.phase, VehiclesPhase.loading);
    fake.pending!.complete();
    await load;
    expect(controller.phase, VehiclesPhase.empty);
    fake.items = [vehicle];
    await controller.load();
    expect(controller.items.single.plate, 'TEST-123');
    fake.error = const AppFailure(FailureKind.offline);
    await controller.load();
    expect(controller.phase, VehiclesPhase.success);
    expect(controller.items, [vehicle]);
    expect(controller.message, isNotNull);
    fake.error = null;
    await controller.load();
    expect(controller.message, isNull);
    controller.dispose();
  });
  test(
    'Create is confirmed before adding and deduplicates concurrent submission',
    () async {
      final fake = FakeVehicles()..pending = Completer<void>();
      final controller = VehiclesController(fake);
      final save = controller.create(const VehicleInput('TEST-123'));
      expect(controller.items, isEmpty);
      expect(await controller.create(const VehicleInput('TEST-123')), false);
      expect(fake.creates, 1);
      fake.pending!.complete();
      expect(await save, true);
      expect(controller.items.single.id, vehicle.id);
      controller.dispose();
    },
  );
  test(
    'Failed creation preserves data and maps authorization/duplicate safely',
    () async {
      final fake = FakeVehicles()
        ..error = const AppFailure(FailureKind.conflict);
      final controller = VehiclesController(fake);
      expect(await controller.create(const VehicleInput('TEST-123')), false);
      expect(controller.items, isEmpty);
      expect(controller.message, contains('placa'));
      expect(
        vehicleError(const AppFailure(FailureKind.forbidden)),
        contains('autorización'),
      );
      expect(
        vehicleError(const AppFailure(FailureKind.timeout)),
        isNot(contains('SQL')),
      );
      controller.dispose();
    },
  );
  test('Missing session/configuration and malformed DTO fail closed', () async {
    await expectLater(
      const VehicleService(null, null).list(),
      throwsA(isA<AppFailure>()),
    );
    expect(
      () => CitizenVehicle.fromJson({'id': 'only-id'}),
      throwsA(isA<AppFailure>()),
    );
  });
  testWidgets('List skeleton, empty, retry and success are honest', (
    tester,
  ) async {
    final fake = FakeVehicles()..pending = Completer<void>();
    final controller = VehiclesController(fake);
    await tester.pumpWidget(await screen(VehiclesPage(controller: controller)));
    expect(find.byType(SkeletonList), findsOneWidget);
    expect(find.text('No tienes vehículos registrados.'), findsNothing);
    fake.pending!.complete();
    await tester.pumpAndSettle();
    expect(find.text('No tienes vehículos registrados.'), findsOneWidget);
    fake.items = [vehicle];
    await controller.load();
    await tester.pumpAndSettle();
    expect(find.text('TEST-123'), findsWidgets);
    controller.dispose();
  });
  testWidgets('Error retry reloads without inventing vehicles', (tester) async {
    final fake = FakeVehicles()..error = const AppFailure(FailureKind.offline);
    final controller = VehiclesController(fake);
    await tester.pumpWidget(await screen(VehiclesPage(controller: controller)));
    await tester.pumpAndSettle();
    expect(find.text('Reintentar'), findsOneWidget);
    fake.error = null;
    fake.items = [vehicle];
    await tester.tap(find.text('Reintentar'));
    await tester.pumpAndSettle();
    expect(find.text('TEST-123'), findsWidgets);
    controller.dispose();
  });
  testWidgets(
    'Create validates plate and optional limits; processing prevents a double tap',
    (tester) async {
      final fake = FakeVehicles()..pending = Completer<void>();
      final controller = VehiclesController(fake);
      await tester.pumpWidget(
        await screen(VehicleFormPage(controller: controller)),
      );
      await tester.ensureVisible(find.text('Guardar vehículo'));
      await tester.tap(find.text('Guardar vehículo'));
      await tester.pumpAndSettle();
      expect(find.text('Ingresa la placa.'), findsOneWidget);
      expect(fake.creates, 0);
      await tester.enterText(find.byType(TextFormField).first, 'Test-123');
      await tester.ensureVisible(find.text('Guardar vehículo'));
      await tester.tap(find.text('Guardar vehículo'));
      await tester.pump();
      expect(find.text('Guardando…'), findsOneWidget);
      await tester.tap(find.text('Guardando…'));
      expect(fake.creates, 1);
      fake.pending!.complete();
      await tester.pumpAndSettle();
      expect(fake.input!.plate, 'TEST-123');
      controller.dispose();
    },
  );
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets(
      'Home and vehicles form fit $size with 200% text and keyboard',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final controller = VehiclesController(FakeVehicles());
        await tester.pumpWidget(
          await screen(
            MediaQuery(
              data: const MediaQueryData(textScaler: TextScaler.linear(2)),
              child: HomePage(controller: controller),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.text('¿Qué deseas hacer?'), findsOneWidget);
        expect(find.text('TEST-123'), findsNothing);
        await tester.pumpWidget(
          await screen(
            MediaQuery(
              data: const MediaQueryData(
                textScaler: TextScaler.linear(2),
                viewInsets: EdgeInsets.only(bottom: 180),
              ),
              child: VehicleFormPage(controller: controller),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final fields = find.byType(TextFormField);
        await tester.ensureVisible(fields.first);
        await tester.tap(fields.first);
        await tester.pumpAndSettle();
        expect(tester.testTextInput.isVisible, true);
        await tester.testTextInput.receiveAction(TextInputAction.next);
        await tester.pumpAndSettle();
        expect(
          tester
              .widget<EditableText>(find.byType(EditableText).at(1))
              .focusNode
              .hasFocus,
          true,
        );
        await tester.ensureVisible(fields.last);
        await tester.tap(fields.last);
        await tester.pumpAndSettle();
        expect(tester.testTextInput.isVisible, true);
        final save = find.byType(AsyncButton);
        await tester.ensureVisible(save);
        await tester.pumpAndSettle();
        final viewport = tester.getRect(find.byType(Scrollable).first);
        expect(viewport.contains(tester.getCenter(save)), true);
        await tester.tap(find.text('Guardar vehículo'));
        await tester.pumpAndSettle();
        expect(find.text('Ingresa la placa.'), findsOneWidget);
        expect(tester.takeException(), isNull);
        controller.dispose();
      },
    );
  }
  testWidgets('Vehicle plate semantics and citizen actions remain accessible', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();

    final controller = VehiclesController(FakeVehicles()..items = [vehicle]);
    await tester.pumpWidget(await screen(VehiclesPage(controller: controller)));
    await tester.pumpAndSettle();
    expect(
      find.bySemanticsLabel(RegExp('Vehículo, placa T E S T - 1 2 3')),
      findsOneWidget,
    );
    await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
    final contrast =
        (AppColors.accent.computeLuminance() + .05) /
        (AppColors.ink.computeLuminance() + .05);
    expect(contrast, greaterThanOrEqualTo(4.5));
    expect(tester.takeException(), isNull);
    controller.dispose();
    semantics.dispose();
  });
  testWidgets('Home reference render for visual review uses honest empty state', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.runAsync(() async {
      final packages =
          (jsonDecode(
                await File('.dart_tool/package_config.json').readAsString(),
              ) as Map)['packages']
              as List;
      final flutterPackage = packages.cast<Map>().firstWhere(
        (p) => p['name'] == 'flutter',
      );
      final root = Uri.parse('${flutterPackage['rootUri']}/');
      final fonts = root.resolve('../../bin/cache/artifacts/material_fonts/');
      for (final family in {
        'Roboto': ['roboto-regular.ttf', 'roboto-bold.ttf'],
        'MaterialIcons': ['materialicons-regular.otf'],
      }.entries) {
        final loader = FontLoader(family.key);
        for (final file in family.value) {
          loader.addFont(
            File.fromUri(fonts.resolve(file))
                .readAsBytes()
                .then(ByteData.sublistView),
          );
        }
        await loader.load();
      }
    });
    final boundaryKey = GlobalKey();
    final controller = VehiclesController(FakeVehicles());
    await tester.pumpWidget(
      await screen(
        RepaintBoundary(
          key: boundaryKey,
          child: MediaQuery(
            data: const MediaQueryData(
              padding: EdgeInsets.only(top: 24),
              viewPadding: EdgeInsets.only(top: 24),
            ),
            child: HomePage(controller: controller),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('ESTACIONAR'), findsOneWidget);
    await tester.runAsync(() async {
      final boundary =
          boundaryKey.currentContext!.findRenderObject()!
              as RenderRepaintBoundary;
      final image = await boundary.toImage(pixelRatio: 1);
      final data = await image.toByteData(format: ui.ImageByteFormat.png);
      await File(
        '${Platform.environment['TEMP'] ?? Directory.systemTemp.path}/simertpi-cp19-home.png',
      ).writeAsBytes(data!.buffer.asUint8List());
      image.dispose();
    });
    for (final entry in {
      'vehicles-empty': VehiclesPage(controller: controller),
      'vehicle-form': VehicleFormPage(controller: controller),
    }.entries) {
      await tester.pumpWidget(
        await screen(RepaintBoundary(key: boundaryKey, child: entry.value)),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.runAsync(() async {
        final boundary =
            boundaryKey.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final image = await boundary.toImage(pixelRatio: 1);
        final data = await image.toByteData(format: ui.ImageByteFormat.png);
        await File(
          '${Platform.environment['TEMP'] ?? Directory.systemTemp.path}/simertpi-cp19-1-${entry.key}.png',
        ).writeAsBytes(data!.buffer.asUint8List());
        image.dispose();
      });
    }
    controller.dispose();
  });
}
