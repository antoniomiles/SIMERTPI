import 'dart:async';
import 'dart:convert';

import 'package:flutter_map/flutter_map.dart';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/config/map_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/core/widgets/app_skeleton.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';
import 'package:simertpi_citizen_app/features/discovery/state/discovery_controller.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/discovery_page.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/parking_map.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/qr_scan_page.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/qr_camera.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/space_selection_page.dart';

import 'support/auth_test_support.dart';

const zone = ParkingZone('z', 'Z-TEST', 'Zona fixture', true);
const street = ParkingStreet('t', 'z', 'Calle fixture', true);
const space = CatalogSpace(
  's',
  't',
  'SPACE-TEST',
  'QR-TEST',
  '1',
  true,
  operationalStatus: 'AVAILABLE',
  backendSelectable: true,
  latitude: -3,
  longitude: -79,
);
ParkingCatalog fixture() => ParkingCatalog([zone], [street], [space]);

class FakeCatalog implements ParkingCatalogGateway {
  ParkingCatalog data = fixture();
  Object? error;
  Completer<void>? pending;
  int loads = 0, resolutions = 0;
  bool? qr;
  @override
  Future<ParkingCatalog> load() async {
    loads++;
    if (pending != null) await pending!.future;
    if (error != null) throw error!;
    return data;
  }

  @override
  Future<IdentifiedSpace> identify(String value, {required bool qr}) async {
    resolutions++;
    this.qr = qr;
    if (pending != null) await pending!.future;
    if (!safeIdentifier(value)) {
      throw const AppFailure(FailureKind.invalidRequest);
    }
    if (error != null) throw error!;
    return IdentifiedSpace(space, data);
  }
}

Future<Widget> screen(Widget child) async {
  final scope = await citizenApp() as AppScope;
  return AppScope(
    config: scope.config,
    auth: scope.auth,
    child: MaterialApp(
      theme: AppTheme.light,
      home: child,
      onGenerateRoute: (s) => AppRouter.generate(s, scope.auth),
    ),
  );
}

class FixtureTiles extends TileProvider {
  @override
  ImageProvider getImage(TileCoordinates coordinates, TileLayer options) =>
      MemoryImage(
        base64Decode(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==',
        ),
      );
}

void main() {
  test(
    'OSM is explicit DEV only, with required attribution and no credentials',
    () {
      final config = MapConfig.parse('', '', source: 'osm-dev');
      expect(config.tileUrl, MapConfig.osmTiles);
      expect(config.attribution, contains('openstreetmap.org/copyright'));
      for (final environment in ['qa', 'uat', 'prod']) {
        expect(
          () => MapConfig.parse(
            '',
            '',
            source: 'osm-dev',
            environment: environment,
          ),
          throwsFormatException,
        );
        expect(
          () => MapConfig.parse(
            MapConfig.osmTiles,
            MapConfig.osmCredit,
            environment: environment,
          ),
          throwsFormatException,
        );
      }
      expect(
        () => MapConfig.parse('', '', source: 'unknown'),
        throwsFormatException,
      );
      expect(MapConfig.parse('', '', environment: 'prod').configured, false);
    },
  );
  testWidgets(
    'Map fallback covers unconfigured provider and missing coordinates',
    (tester) async {
      for (final config in [
        const MapConfig(),
        MapConfig.parse('', '', source: 'osm-dev'),
      ]) {
        await tester.pumpWidget(
          MaterialApp(
            home: ParkingMap(
              spaces: [],
              catalog: fixture(),
              config: config,
              enabled: true,
              onSelect: (_) => fail('No invented marker'),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(FlutterMap), findsNothing);
        expect(find.byType(IconButton), findsNothing);
        expect(tester.takeException(), isNull);
      }
    },
  );
  test(
    'Catalog permits only the real active hierarchy, not occupancy assumptions',
    () {
      expect(fixture().selectable(space), true);
      expect(
        ParkingCatalog([const ParkingZone('z', 'Z', 'Zone', false)], [street], [
          space,
        ]).selectable(space),
        false,
      );
      expect(
        ParkingCatalog(
          [zone],
          [const ParkingStreet('t', 'z', 'Street', false)],
          [space],
        ).selectable(space),
        false,
      );
      expect(ParkingCatalog([zone], [], [space]).selectable(space), false);
      expect(
        ParkingCatalog([zone], [street], [
          const CatalogSpace('s', 't', 'S', 'Q', '1', false),
        ]).selectable(const CatalogSpace('s', 't', 'S', 'Q', '1', false)),
        false,
      );
      expect(fixture().inZone('unknown'), isEmpty);
    },
  );
  test('Coordinates are optional, finite and map-safe; no invented center', () {
    expect(space.hasCoordinates, true);
    expect(
      const CatalogSpace('s', 't', 'c', 'q', '1', true).hasCoordinates,
      false,
    );
    expect(
      const CatalogSpace(
        's',
        't',
        'c',
        'q',
        '1',
        true,
        latitude: 91,
        longitude: 0,
      ).hasCoordinates,
      false,
    );
    expect(
      () => CatalogSpace.fromJson({
        'id': 's',
        'streetId': 't',
        'code': 'c',
        'qrCode': 'q',
        'spaceNumber': '1',
        'active': true,
        'latitude': 'bad',
      }),
      throwsA(isA<AppFailure>()),
    );
    expect(() => ParkingZone.fromJson({'id': 'z'}), throwsA(isA<AppFailure>()));
  });
  test('Untrusted QR URLs, JSON, traversal, controls and excessive size fail closed', () {
    expect(safeIdentifier('QR-TEST'), true);
    for (final value in [
      '',
      'https://malicious.invalid/',
      '{"id":"s"}',
      '../s',
      's\n',
      'Q' * 256,
      's%2F../x',
    ]) {
      expect(
        safeIdentifier(value),
        false,
        reason: 'unsafe fixture must be rejected',
      );
    }
  });
  test('Map config requires HTTPS, placeholders and attribution, not an invented provider', () {
    expect(MapConfig.parse('', '').configured, false);
    expect(
      MapConfig.parse(
        'https://tiles.fixture.invalid/{z}/{x}/{y}.png',
        'Fixture attribution',
      ).configured,
      true,
    );
    for (final url in [
      'http://tiles.fixture.invalid/{z}/{x}/{y}',
      'https://secret@tiles.fixture.invalid/{z}/{x}/{y}',
      'https://tiles.fixture.invalid/',
    ]) {
      expect(() => MapConfig.parse(url, 'Fixture'), throwsFormatException);
    }
    expect(
      () => MapConfig.parse('https://tiles.fixture.invalid/{z}/{x}/{y}', ''),
      throwsFormatException,
    );
  });
  test(
    'Controller states, retry and refresh deduplicate; stale data is flagged',
    () async {
      final f = FakeCatalog()..pending = Completer<void>();
      final c = DiscoveryController(f);
      final loading = c.load();
      expect(c.phase, DiscoveryPhase.loading);
      await c.load();
      expect(f.loads, 1);
      f.pending!.complete();
      await loading;
      expect(c.phase, DiscoveryPhase.success);
      c.selectZone('z');
      expect(c.spaces.single.id, 's');
      f.error = const AppFailure(FailureKind.offline);
      await c.load();
      expect(c.catalog, isNotNull);
      expect(c.message, isNotNull);
      f.error = null;
      await c.resolve('QR-TEST', qr: true);
      expect(c.catalog, same(f.data));
      expect(c.message, isNull);
      expect(c.identified, isNotNull);
      f.data = ParkingCatalog([], [], []);
      await c.load();
      expect(c.phase, DiscoveryPhase.empty);
      expect(c.zoneId, isNull);
      c.dispose();
    },
  );
  test(
    'Double QR resolution is blocked and late result after disposal is ignored',
    () async {
      final f = FakeCatalog()..pending = Completer<void>();
      final c = DiscoveryController(f);
      final first = c.resolve('QR-TEST', qr: true);
      await c.resolve('QR-TEST', qr: true);
      expect(f.resolutions, 1);
      expect(c.resolving, true);
      c.dispose();
      f.pending!.complete();
      await first;
      expect(c.identified, isNull);
    },
  );
  testWidgets('Loading, error retry, zones and spaces are honest', (
    tester,
  ) async {
    final f = FakeCatalog()..pending = Completer<void>();
    final c = DiscoveryController(f);
    await tester.pumpWidget(await screen(DiscoveryPage(controller: c)));
    expect(find.byType(SkeletonList), findsOneWidget);
    f.error = const AppFailure(FailureKind.offline);
    f.pending!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Reintentar'), findsOneWidget);
    f.error = null;
    await tester.tap(find.text('Reintentar'));
    await tester.pumpAndSettle();
    expect(find.text('Zona fixture'), findsOneWidget);
    expect(find.textContaining('1 espacios'), findsOneWidget);
    await tester.tap(find.text('Zona fixture'));
    await tester.pumpAndSettle();
    expect(c.zoneId, 'z');
    await tester.ensureVisible(find.text('Ver espacio 1'));
    await tester.tap(find.text('Ver espacio 1'));
    await tester.pumpAndSettle();
    expect(find.text('Detalle del espacio'), findsOneWidget);
    expect(find.text('Seleccionar espacio'), findsOneWidget);
    expect(f.resolutions, 1);
    expect(f.qr, false);
    c.dispose();
  });
  testWidgets('Empty catalog is different from loading and error', (
    tester,
  ) async {
    final c = DiscoveryController(
      FakeCatalog()..data = ParkingCatalog([], [], []),
    );
    await tester.pumpWidget(await screen(DiscoveryPage(controller: c)));
    await tester.pumpAndSettle();
    expect(find.text('No hay zonas ni espacios registrados.'), findsOneWidget);
    expect(find.byType(SkeletonList), findsNothing);
    c.dispose();
  });
  testWidgets(
    'QR error, double capture and explicit retry; no external navigation',
    (tester) async {
      final f = FakeCatalog()
        ..error = const AppFailure(FailureKind.rejected)
        ..pending = Completer<void>();
      final c = DiscoveryController(f);
      late ValueChanged<String> capture;
      await tester.pumpWidget(
        await screen(
          QrScanPage(
            controller: c,
            cameraBuilder: (read, retry) {
              capture = read;
              return const Text('Camera fixture');
            },
          ),
        ),
      );
      capture('QR-TEST');
      capture('QR-TEST');
      await tester.pump();
      expect(f.resolutions, 1);
      expect(find.text('Consultando el espacio…'), findsOneWidget);
      f.pending!.complete();
      await tester.pumpAndSettle();
      expect(
        find.text('No encontramos ese espacio. Revisa el código.'),
        findsOneWidget,
      );
      await tester.tap(find.text('Volver a escanear'));
      await tester.pumpAndSettle();
      capture('https://malicious.invalid/');
      await tester.pumpAndSettle();
      expect(find.textContaining('No se aceptan enlaces'), findsOneWidget);
      c.dispose();
    },
  );
  test(
    'Camera denied/unavailable errors provide safe contextual alternatives',
    () {
      expect(
        cameraErrorMessage(MobileScannerErrorCode.permissionDenied),
        contains('ajustes'),
      );
      expect(
        cameraErrorMessage(MobileScannerErrorCode.unsupported),
        contains('código'),
      );
    },
  );
  testWidgets('Home navigates to protected discovery; guards remain active', (
    tester,
  ) async {
    await tester.pumpWidget(await citizenApp());
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('ESTACIONAR'));
    await tester.tap(find.text('ESTACIONAR'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('BUSCAR ESTACIONAMIENTO'));
    await tester.tap(find.text('BUSCAR ESTACIONAMIENTO'));
    await tester.pumpAndSettle();
    expect(find.text('Buscar estacionamiento'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('¿Dónde vas a estacionar?'), findsOneWidget);
    final scope = await citizenApp() as AppScope;
    await scope.auth.logout(remote: false);
    await tester.pumpWidget(
      authTestApp(
        scope.auth,
        route: AppRoute.discovery,
        key: const ValueKey('guard-check'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Inicia sesión'), findsOneWidget);
  });
  testWidgets(
    'Map renders supplied coordinates and accessible markers without network',
    (tester) async {
      CatalogSpace? picked;
      final handle = tester.ensureSemantics();
      try {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: ParkingMap(
                spaces: [space],
                catalog: fixture(),
                config: MapConfig.parse(
                  'https://tiles.fixture.invalid/{z}/{x}/{y}.png',
                  'Fixture attribution',
                ),
                tileProvider: FixtureTiles(),
                enabled: true,
                onSelect: (s) => picked = s,
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(FlutterMap), findsOneWidget);
        expect(find.text('Fixture attribution'), findsOneWidget);
        expect(find.bySemanticsLabel('Espacio 1, Disponible'), findsOneWidget);
        await tester.tap(find.byTooltip('Espacio 1'));
        await tester.pump();
        expect(picked?.id, 's');
        expect(
          tester.getSize(find.byType(IconButton)).height,
          greaterThanOrEqualTo(48),
        );
        expect(tester.takeException(), isNull);
      } finally {
        handle.dispose();
      }
    },
  );
  testWidgets(
    'Viewport fits backend markers and keeps attribution visible at 200%',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      const second = CatalogSpace(
        's2',
        't',
        'S2',
        'Q2',
        '2',
        false,
        latitude: -3.001,
        longitude: -79.002,
      );
      final catalog = ParkingCatalog([zone], [street], [space, second]);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.light,
          home: Scaffold(
            body: ParkingMap(
              spaces: catalog.spaces,
              catalog: catalog,
              config: MapConfig.parse('', '', source: 'osm-dev'),
              tileProvider: FixtureTiles(),
              enabled: true,
              onSelect: (_) {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final map = tester.widget<FlutterMap>(find.byType(FlutterMap));
      expect(map.options.initialCameraFit, isNotNull);
      expect(find.byTooltip('Espacio 1'), findsOneWidget);
      expect(find.byTooltip('Espacio 2'), findsOneWidget);
      final credit = tester.getRect(find.text(MapConfig.osmCredit));
      final viewport = tester.getRect(find.byType(FlutterMap));
      expect(viewport.contains(credit.topLeft), true);
      expect(credit.bottom <= viewport.bottom, true);
      expect(tester.takeException(), isNull);
    },
  );
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets(
      'Discovery, QR controls and selection fit $size with text 200%',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = 2;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        final c = DiscoveryController(FakeCatalog());
        for (final page in [
          DiscoveryPage(controller: c),
          QrScanPage(
            controller: c,
            cameraBuilder: (r, t) => const Text('Camera fixture'),
          ),
          SpaceSelectionPage(result: IdentifiedSpace(space, fixture())),
        ]) {
          await tester.pumpWidget(await screen(page));
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
        }
        c.dispose();
      },
    );
  }
}
