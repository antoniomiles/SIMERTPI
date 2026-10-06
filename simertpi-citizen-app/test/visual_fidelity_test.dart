import 'dart:io';
import 'dart:async';

import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';

import 'package:simertpi_citizen_app/core/widgets/app_layout.dart';
import 'package:simertpi_citizen_app/core/config/map_config.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/parking_map.dart';

import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/core/widgets/operational_ui.dart';
import 'package:simertpi_citizen_app/features/home/home_page.dart';
import 'package:simertpi_citizen_app/features/home/start_parking_page.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/discovery_page.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/space_selection_page.dart';
import 'package:simertpi_citizen_app/features/discovery/presentation/parking_map_marker.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';
import 'package:simertpi_citizen_app/features/discovery/state/discovery_controller.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';
import 'package:simertpi_citizen_app/features/active_parking/presentation/active_parking_panel.dart';

import 'discovery_test.dart' as d;
import 'parking_test.dart' as p;
import 'payments_test.dart' as pay;
import 'active_parking_test.dart' as a;

class BatchParking extends p.FakeParking implements DurationOptionsGateway {
  int batches = 0;
  @override
  Future<List<ParkingRules>> options(String id) async {
    batches++;
    return [
      for (final n in [15, 30, 45])
        ParkingRules({
          ...p.ruleFixture(n),
          'calculatedAmount': '0.31',
          'billedDurationMinutes': n,
          'expiresAt': DateTime.utc(
            2026,
            10,
            4,
            15,
          ).add(Duration(minutes: n)).toIso8601String(),
        }),
    ];
  }
}

class FinalConfiguredParking extends BatchParking {
  @override
  Future<ParkingRules> rules(String id, [int? minutes]) async => ParkingRules({
    ...p.ruleFixture(minutes),
    'minimumFractionMinutes': 30,
    'maximumContinuousMinutes': 240,
    'unitPrice': '0.25',
    'unitDurationMinutes': 60,
    'calculatedAmount': minutes == null ? null : '0.13',
    'billedDurationMinutes': minutes,
    'expiresAt': minutes == null
        ? null
        : DateTime.utc(
            2026,
            10,
            4,
            15,
          ).add(Duration(minutes: minutes)).toIso8601String(),
  });
  @override
  Future<List<ParkingRules>> options(String id) async {
    const prices = [
      '0.13',
      '0.25',
      '0.38',
      '0.50',
      '0.63',
      '0.75',
      '0.88',
      '1.00',
    ];
    return [
      for (int i = 0; i < 8; i++)
        ParkingRules({
          ...p.ruleFixture((i + 1) * 30),
          'calculatedAmount': prices[i],
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
  }
}

class UnconfirmedClose extends a.FakeActive {
  @override
  Future<ParkingReceipt> close(String id) async {
    closes++;
    return receipt();
  }
}

class EligibleActive extends a.FakeActive implements DurationOptionsGateway {
  bool allowed = true;
  @override
  Future<List<ParkingRules>> options(String id) async =>
      allowed ? [ParkingRules(p.ruleFixture(15))] : [];
}

Future<void> capture(WidgetTester tester, GlobalKey key, String name) async {
  await tester.pump();
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: 1);
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    const directory = String.fromEnvironment(
      'VISUAL_CAPTURE_DIR',
      defaultValue: 'build/cp23.2-review',
    );
    await Directory(directory).create(recursive: true);
    await File('$directory/$name.png').writeAsBytes(data!.buffer.asUint8List());
    image.dispose();
  });
}

void main() {
  test(
    'Close only removes the card after matching session is COMPLETED',
    () async {
      final gateway = UnconfirmedClose();
      final c = a.controller(gateway);
      await c.load();
      expect(await c.closeSession('receipt'), false);
      expect(c.sessions.single.status, 'ACTIVE');
      expect(c.closeUncertain, true);
      expect(gateway.closes, 1);
      c.dispose();
    },
  );

  testWidgets('Internal bottom navigation returns to retained Home and auth', (
    tester,
  ) async {
    final active = a.controller(a.FakeActive());
    final base = await d.screen(HomePage(activeController: active)) as AppScope;
    final selected = ValueNotifier<int>(0);
    await tester.pumpWidget(
      AppScope(
        config: base.config,
        auth: base.auth,
        citizenTab: selected,
        child: base.child,
      ),
    );
    await tester.pumpAndSettle();
    final authenticated = base.auth.userId;
    await tester.tap(find.text('ESTACIONAR'));
    await tester.pumpAndSettle();
    expect(find.text('¿Dónde vas a estacionar?'), findsOneWidget);
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar).last,
        matching: find.text('Mapa'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(HomePage), findsOneWidget);
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(
      tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
      1,
    );
    expect(base.auth.userId, authenticated);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(
      tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
      0,
    );
    expect(find.text('Estacionamiento activo'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    active.dispose();
    selected.dispose();
  });

  testWidgets(
    'Expired has no citizen actions even if stale gateway offers remain',
    (tester) async {
      final gateway = EligibleActive()..state = 'EXPIRED';
      final c = a.controller(gateway);
      await c.load();
      await tester.pumpWidget(a.panel(c));
      await tester.pump();
      expect(find.text('Extender'), findsNothing);
      expect(find.text('Finalizar'), findsNothing);
      gateway.allowed = false;
      await c.load();
      await tester.pump();
      expect(find.text('Extender'), findsNothing);
      expect(find.text('Finalizar'), findsNothing);
      expect(await c.closeSession('receipt'), false);
      expect(gateway.closes, 0);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );

  testWidgets('Dynamic duration cards use one batch and server amounts', (
    tester,
  ) async {
    final gateway = BatchParking();
    final c = p.controller(gateway);
    await tester.pumpWidget(await p.page(c));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continuar'));
    await tester.pumpAndSettle();
    expect(gateway.batches, 1);
    expect(find.text(r'$0,31'), findsNWidgets(3));
    expect(find.byType(TextFormField), findsNothing);
    await tester.tap(find.text('30 minutos'));
    await tester.pump();
    expect(c.minutes, 30);
    expect(c.quote!.amount, '0.31');
    expect(gateway.batches, 1);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
  test(
    'Batch parser rejects duplicates, foreign space and unquoted amounts',
    () {
      expect(
        () => parseDurationOptions([
          p.ruleFixture(15),
          p.ruleFixture(15),
        ], spaceId: 's'),
        throwsA(anything),
      );
      expect(
        () => parseDurationOptions([p.ruleFixture(15)], spaceId: 'foreign'),
        throwsA(anything),
      );
      expect(
        () => parseDurationOptions([p.ruleFixture()], spaceId: 's'),
        throwsA(anything),
      );
    },
  );
  for (final status in ['AVAILABLE', 'ENDING_SOON', 'OCCUPIED', 'DISABLED']) {
    testWidgets('Numbered pin $status has text and status semantics', (
      tester,
    ) async {
      final s = CatalogSpace('s', 't', 'PIN-DEV-001', 'QR', '1', true)
          .withAvailability({
            'parkingSpaceId': 's',
            'spaceCode': 'PIN-DEV-001',
            'active': true,
            'operationalStatus': status,
            'selectable': status == 'AVAILABLE',
          });
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ParkingMapMarker(space: s, onTap: () {}),
          ),
        ),
      );
      expect(find.text('001'), findsOneWidget);
      expect(find.byType(CustomPaint), findsWidgets);
      expect(find.bySemanticsLabel(RegExp('PIN-DEV-001')), findsOneWidget);
      semantics.dispose();
    });
  }
  for (final seconds in [840, 480, 0]) {
    testWidgets(
      'Home temporal card $seconds shares published reminder threshold',
      (tester) async {
        final c = a.controller(
          a.FakeActive(),
          now: () => pay
              .session('ACTIVE')
              .expectedEndAt
              .subtract(Duration(seconds: seconds)),
        );
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
        expect(
          find.text(
            seconds == 0
                ? 'Tu tiempo de estacionamiento terminó'
                : seconds <= 600
                ? 'Próximo a vencer'
                : 'Estacionamiento activo',
          ),
          findsOneWidget,
        );
        final card = tester.widget<OperationalCard>(
          find.byType(OperationalCard).first,
        );
        expect(
          card.color,
          seconds == 0
              ? AppColors.dangerSurface
              : seconds <= 600
              ? AppColors.warningSurface
              : AppColors.successSurface,
        );
        expect(
          find.text('Finalizar'),
          seconds == 0 ? findsNothing : findsOneWidget,
        );
        await tester.pumpWidget(const SizedBox());
        c.dispose();
      },
    );
  }
  testWidgets(
    'Storyboard review captures use actual widgets and explicit test fixtures',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.runAsync(() async {
        for (final family in ['Roboto', 'Ahem']) {
          final font = FontLoader(family)
            ..addFont(
              File('C:/Windows/Fonts/arial.ttf')
                  .readAsBytes()
                  .then((b) => ByteData.sublistView(b)),
            );
          await font.load();
        }
        final icons = FontLoader('MaterialIcons')
          ..addFont(
            File(
              'C:/src/flutter/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf',
            ).readAsBytes().then((b) => ByteData.sublistView(b)),
          );
        await icons.load();
      });
      final key = GlobalKey();
      Future<void> show(Widget widget, String name) async {
        await tester.pumpWidget(RepaintBoundary(key: key, child: widget));
        await tester.pumpAndSettle();
        await capture(tester, key, name);
        expect(tester.takeException(), isNull);
      }

      final empty = a.controller(a.FakeActive()..empty = true);
      await show(await d.screen(HomePage(activeController: empty)), '01-home');
      await show(await d.screen(const StartParkingPage()), '02-qr');
      empty.dispose();
      final discovery = DiscoveryController(d.FakeCatalog());
      await show(
        await d.screen(DiscoveryPage(controller: discovery)),
        '03-zones',
      );
      discovery.selectZone('z');
      await tester.pumpAndSettle();
      await capture(tester, key, '04-spaces');
      await tester.pumpWidget(const SizedBox());
      discovery.dispose();
      await show(
        await d.screen(
          AppPage(
            title: 'Espacios — Mapa',
            showNavigation: true,
            child: ParkingMap(
              spaces: [d.space],
              catalog: d.fixture(),
              config: MapConfig.parse('', '', source: 'osm-dev'),
              tileProvider: d.FixtureTiles(),
              enabled: true,
              onSelect: (_) {},
            ),
          ),
        ),
        '05-map',
      );
      final busy = d.space.withAvailability({
        'parkingSpaceId': 's',
        'spaceCode': 'SPACE-TEST',
        'active': true,
        'operationalStatus': 'ENDING_SOON',
        'selectable': false,
        'remainingSeconds': 480,
      });
      await show(
        await d.screen(
          SpaceSelectionPage(
            result: IdentifiedSpace(
              busy,
              ParkingCatalog([d.zone], [d.street], [busy]),
            ),
          ),
        ),
        '06-detail',
      );
      final parking = p.controller(FinalConfiguredParking());
      await show(await p.page(parking), '07-vehicle');
      await tester.tap(find.text('Continuar'));
      await tester.pumpAndSettle();
      await capture(tester, key, '08-duration');
      await tester.tap(find.text('30 minutos'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Continuar'));
      await tester.tap(find.text('Continuar'));
      await tester.pumpAndSettle();
      await capture(tester, key, '09-summary');
      await tester.pumpWidget(const SizedBox());
      parking.dispose();
      final payment = pay.controller();
      await show(await pay.page(payment), '09-payment');
      payment.chooseMethod();
      await tester.pumpWidget(const SizedBox());
      payment.dispose();
      final processingGateway = pay.FakePayments()..gate = Completer<void>();
      final processing = pay.controller(payments: processingGateway);
      await show(await pay.page(processing), '09-before-processing');
      processing.chooseMethod();
      final pending = processing.confirm();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      await capture(tester, key, '10-processing');
      processingGateway.gate!.complete();
      await pending;
      await tester.pumpWidget(const SizedBox());
      processing.dispose();
      final g = p.FakeParking()..existing = [pay.session()];
      final pg = pay.FakePayments()
        ..sent = () {
          g.existing = [pay.session('ACTIVE')];
        };
      final paid = pay.controller(parking: g, payments: pg);
      await paid.load('receipt');
      paid.chooseMethod();
      await paid.confirm();
      await show(await pay.page(paid), '11-success');
      await tester.pumpWidget(const SizedBox());
      paid.dispose();
      final active = a.controller(a.FakeActive());
      await show(
        await d.screen(HomePage(activeController: active)),
        '12-active-home',
      );
      await tester.pumpWidget(const SizedBox());
      await active.prepare('receipt');
      await show(
        MaterialApp(
          theme: AppTheme.light,
          home: ExtensionPage(controller: active),
        ),
        '13-extension',
      );
      await tester.pumpWidget(const SizedBox());
      active.dispose();
      final extensionGateway = a.FakeActive()..approve = true;
      final extended = a.controller(extensionGateway);
      await extended.prepare('receipt');
      await extended.confirmExtension();
      await show(
        MaterialApp(
          theme: AppTheme.light,
          home: ExtensionPage(controller: extended),
        ),
        '14-extension-result',
      );
      await tester.pumpWidget(const SizedBox());
      extended.dispose();
      for (final state in ['EXPIRED', 'MAX_TIME_REACHED']) {
        final critical = a.controller(a.FakeActive()..state = state);
        await critical.load();
        await show(a.panel(critical), '15-$state');
        await tester.pumpWidget(const SizedBox());
        critical.dispose();
      }
      final soon = a.controller(
        a.FakeActive(),
        now: () => pay
            .session('ACTIVE')
            .expectedEndAt
            .subtract(const Duration(minutes: 8)),
      );
      await soon.load();
      soon.locations = ParkingCatalog([d.zone], [d.street], [
        d.space.withAvailability({
          'parkingSpaceId': 's',
          'spaceCode': 'SPACE-TEST',
          'active': true,
          'operationalStatus': 'ENDING_SOON',
          'selectable': false,
          'endingSoonSeconds': 600,
        }),
      ]);
      await show(a.panel(soon), '16-ending-soon');
      await tester.pumpWidget(const SizedBox());
      soon.dispose();
      final close = a.controller(a.FakeActive());
      await close.load();
      await show(a.panel(close), '17-before-close');
      await tester.tap(find.text('Finalizar'));
      await tester.pumpAndSettle();
      await capture(tester, key, '17-close-dialog');
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      close.dispose();
    },
    skip: !const bool.fromEnvironment('VISUAL_CAPTURE'),
  );
}
