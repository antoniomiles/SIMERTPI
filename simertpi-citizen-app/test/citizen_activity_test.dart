import 'support/auth_test_support.dart' as auth_fixture;

import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/core/widgets/citizen_navigation.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/activity_service.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/activity_controller.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/activity_pages.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/profile_page.dart';

final historical = <String, dynamic>{
  'id': 'history-id',
  'plate': 'ABC1234',
  'zone': 'Centro',
  'street': 'Sucre',
  'spaceCode': 'PIN-DEV-004',
  'startedAt': '2026-10-06T09:00:00Z',
  'expectedEndAt': '2026-10-06T10:30:00Z',
  'endedAt': '2026-10-06T10:31:00Z',
  'contractedMinutes': 90,
  'occupiedMinutes': 91,
  'status': 'COMPLETED',
  'paidAmounts': [
    {'amount': '0.38', 'currency': 'USD'},
  ],
};
final notice = <String, dynamic>{
  'id': 'notice-id',
  'title': 'Próximo a vencer',
  'message': 'Tu estacionamiento está próximo a finalizar.',
  'createdAt': '2026-10-06T10:20:00Z',
  'readAt': null,
};

class FakeActivity implements ActivityGateway {
  List<CitizenData> rows = [];
  bool more = false;
  Object? failure;
  Completer<ActivitySlice>? pending;
  List<int> offsets = [];
  int reads = 0, requests = 0;
  Map<String, bool>? saved;
  @override
  Future<ActivitySlice> list(String resource, int offset) async {
    requests++;
    offsets.add(offset);
    if (failure != null) throw failure!;
    if (pending != null) return pending!.future;
    return ActivitySlice(rows, more);
  }

  @override
  Future<CitizenData> detail(String resource, String id) async => {
    'session': historical,
    'extensions': [
      {'minutes': 30, 'approvedAt': '2026-10-06T09:50:00Z'},
    ],
  };
  @override
  Future<CitizenData> read(String id) async {
    reads++;
    return {...notice, 'readAt': '2026-10-06T10:21:00Z'};
  }

  @override
  Future<int> unread() async => 1;
  @override
  Future<CitizenData> profile() async => {
    'firstName': 'Ana',
    'lastName': 'Pérez',
    'username': 'ana',
    'email': 'ana@example.test',
    'phone': '0990000000',
  };
  @override
  Future<Map<String, bool>> preferences() async => {
    'PUSH': false,
    'EMAIL': false,
  };
  @override
  Future<void> savePreferences(Map<String, bool> values) async {
    saved = Map.of(values);
  }
}

Future<void> screen(
  WidgetTester t,
  Widget page, {
  Size size = const Size(320, 640),
  double scale = 1,
}) async {
  t.view.physicalSize = size;
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.resetPhysicalSize);
  addTearDown(t.view.resetDevicePixelRatio);
  await t.pumpWidget(
    MaterialApp(
      theme: AppTheme.light,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: child!,
      ),
      home: page,
    ),
  );
  await t.pumpAndSettle();
}

void main() {
  testWidgets(
    'Profile links preserve named routes, preferences and hardened logout',
    (t) async {
      final gateway = auth_fixture.FakeAuthGateway();
      final store = auth_fixture.MemorySessionStore();
      final auth = AuthController(gateway, store: store);
      await auth.login('fixture-citizen', 'test-only-password');
      int inbox = 0;
      await t.pumpWidget(
        AppScope(
          config: AppConfig.parse(environment: 'dev'),
          auth: auth,
          child: MaterialApp(
            theme: AppTheme.light,
            home: CitizenProfilePage(
              gateway: FakeActivity(),
              onNotifications: () => inbox++,
            ),
            routes: {
              '/history': (_) => Scaffold(
                appBar: AppBar(title: const Text('fixture-history')),
              ),
              '/vehicles': (_) => Scaffold(
                appBar: AppBar(title: const Text('fixture-vehicles')),
              ),
            },
          ),
        ),
      );
      await t.pumpAndSettle();
      for (final link in {
        'Historial': 'fixture-history',
        'Mis veh\u00edculos': 'fixture-vehicles',
      }.entries) {
        await t.ensureVisible(find.text(link.key));
        await t.tap(find.text(link.key));
        await t.pumpAndSettle();
        expect(find.text(link.value), findsOneWidget);
        await t.pageBack();
        await t.pumpAndSettle();
      }
      await t.ensureVisible(find.text('Notificaciones'));
      await t.tap(find.text('Notificaciones'));
      expect(inbox, 1);
      await t.ensureVisible(find.text('Preferencias de notificaci\u00f3n'));
      await t.tap(find.text('Preferencias de notificaci\u00f3n'));
      await t.pumpAndSettle();
      expect(find.byType(NotificationPreferencesPage), findsOneWidget);
      await t.pageBack();
      await t.pumpAndSettle();
      await t.ensureVisible(find.text('Cerrar sesi\u00f3n'));
      await t.tap(find.text('Cerrar sesi\u00f3n'));
      await t.pumpAndSettle();
      expect(auth.isAuthenticated, false);
      expect(store.session, isNull);
      expect(gateway.logouts, 1);
      await t.pumpWidget(const SizedBox());
      auth.dispose();
    },
  );
  testWidgets('History shows clean location and monetary separators', (
    t,
  ) async {
    final g = FakeActivity()..rows = [historical];
    await screen(t, ActivityListPage(resource: 'history', gateway: g));
    expect(find.text('Sucre \u00b7 PIN-DEV-004'), findsOneWidget);
    expect(find.text(r'$0,38'), findsOneWidget);
    expect(find.textContaining('?'), findsNothing);
    expect(t.takeException(), isNull);
  });
  test(
    'Paid amounts separate currencies without replacing formatter output',
    () {
      expect(
        paidLabel([
          {'amount': '0.25', 'currency': 'USD'},
          {'amount': '1.00', 'currency': 'EUR'},
        ]),
        r'$0,25 '
        '\u00b7'
        r' EUR 1.00',
      );
      expect(paidLabel(historical['paidAmounts']), r'$0,38');
      expect(paidLabel(historical['paidAmounts']).contains('?'), isFalse);
    },
  );

  testWidgets('Load more appends backend history and ends pagination', (
    t,
  ) async {
    final g = FakeActivity()
      ..rows = [historical]
      ..more = true;
    await screen(t, ActivityListPage(resource: 'history', gateway: g));
    g.rows = [
      {...historical, 'id': 'second-history', 'plate': 'DEF5678'},
    ];
    g.more = false;
    await t.ensureVisible(find.text('Cargar m\u00e1s'));
    await t.tap(find.text('Cargar m\u00e1s'));
    await t.pumpAndSettle();
    expect(g.offsets, [0, 1]);
    expect(find.text('DEF5678'), findsOneWidget);
    expect(find.text('Cargar m\u00e1s'), findsNothing);
  });

  test(
    'Pagination deduplicates shifted rows while advancing the server offset',
    () async {
      final g = FakeActivity()
        ..rows = [notice]
        ..more = true;
      final c = ActivityController(g, 'inbox');
      await c.load();
      await c.load(more: true);
      expect(c.items.length, 1);
      g.rows = [
        {...notice, 'id': 'second'},
      ];
      g.more = false;
      await c.load(more: true);
      expect(g.offsets, [0, 1, 2]);
      expect(c.items.length, 2);
      c.dispose();
    },
  );

  test('Controller pagination retains backend amounts and retries same offset on failure', () async {
    final g = FakeActivity()
      ..rows = [historical]
      ..more = true;
    final c = ActivityController(g, 'history');
    await c.load();
    g.failure = const AppFailure(FailureKind.offline);
    await c.load(more: true);
    expect(c.items.single['paidAmounts'], historical['paidAmounts']);
    expect(c.error, contains('conexión'));
    g.failure = null;
    g.more = false;
    await c.load(more: true);
    expect(g.offsets, [0, 1, 1]);
    expect(c.hasMore, false);
    c.dispose();
  });
  test('Late page after refresh is discarded and dispose is safe', () async {
    final g = FakeActivity()..pending = Completer<ActivitySlice>();
    final c = ActivityController(g, 'inbox');
    final first = c.load();
    final old = g.pending!;
    g.pending = null;
    g.rows = [notice];
    await c.load();
    old.complete(ActivitySlice([historical], false));
    await first;
    expect(c.items.single['id'], 'notice-id');
    c.dispose();
  });
  testWidgets('Inbox tap marks logical item read and only opens safe content', (
    t,
  ) async {
    final g = FakeActivity()..rows = [notice];
    int updates = 0;
    await screen(
      t,
      ActivityListPage(resource: 'inbox', gateway: g, onRead: () => updates++),
    );
    expect(find.text('No leída'), findsOneWidget);
    await t.tap(find.text('Próximo a vencer'));
    await t.pumpAndSettle();
    expect(g.reads, 1);
    expect(updates, 1);
    expect(find.text('Notificación'), findsOneWidget);
    expect(find.text('notice-id'), findsNothing);
    expect(find.text('Extender'), findsNothing);
  });
  testWidgets(
    'History detail retains inactive vehicle plate and backend paid total',
    (t) async {
      final g = FakeActivity()..rows = [historical];
      await screen(t, ActivityListPage(resource: 'history', gateway: g));
      expect(find.text('ABC1234'), findsOneWidget);
      expect(find.text(r'$0,38'), findsOneWidget);
      await t.tap(find.text('ABC1234'));
      await t.pumpAndSettle();
      expect(find.text('Detalle del estacionamiento'), findsOneWidget);
      expect(find.text('Tiempo contratado'), findsOneWidget);
      expect(find.text('history-id'), findsNothing);
      expect(find.text('Extensiones aprobadas'), findsOneWidget);
    },
  );
  for (final resource in ['history', 'inbox']) {
    testWidgets('$resource empty', (t) async {
      await screen(
        t,
        ActivityListPage(resource: resource, gateway: FakeActivity()),
      );
      expect(
        find.text(
          resource == 'history'
              ? 'Aún no tienes estacionamientos finalizados.'
              : 'No tienes notificaciones.',
        ),
        findsOneWidget,
      );
    });
    testWidgets('$resource loading', (t) async {
      final g = FakeActivity()..pending = Completer<ActivitySlice>();
      await t.pumpWidget(
        MaterialApp(
          home: ActivityListPage(resource: resource, gateway: g),
        ),
      );
      await t.pump();
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      g.pending!.complete(const ActivitySlice([], false));
      await t.pumpAndSettle();
    });
    for (final kind in [
      FailureKind.offline,
      FailureKind.unauthorized,
      FailureKind.unavailable,
    ]) {
      testWidgets('$resource sanitized $kind retry', (t) async {
        final g = FakeActivity()..failure = AppFailure(kind);
        await screen(t, ActivityListPage(resource: resource, gateway: g));
        expect(find.text(AppFailure(kind).message), findsOneWidget);
        expect(find.text('Reintentar'), findsOneWidget);
      });
    }
  }
  testWidgets(
    'Profile is read only and links preferences without verification claims',
    (t) async {
      await screen(
        t,
        CitizenProfilePage(gateway: FakeActivity(), onNotifications: () {}),
      );
      expect(find.text('Ana Pérez'), findsOneWidget);
      expect(find.text('ana@example.test'), findsOneWidget);
      expect(find.text('Historial'), findsOneWidget);
      expect(find.text('Cerrar sesión'), findsOneWidget);
      expect(find.textContaining('verificado'), findsNothing);
      expect(find.text('Editar'), findsNothing);
    },
  );
  testWidgets(
    'Preferences save only cell and email, no WhatsApp or in-app switch',
    (t) async {
      final g = FakeActivity();
      await screen(t, NotificationPreferencesPage(gateway: g));
      expect(find.byType(SwitchListTile), findsNWidgets(2));
      expect(find.text('WHATSAPP'), findsNothing);
      await t.tap(find.byType(SwitchListTile).first);
      await t.pump();
      await t.ensureVisible(find.text('Guardar preferencias'));
      await t.tap(find.text('Guardar preferencias'));
      await t.pumpAndSettle();
      expect(g.saved, {'PUSH': true, 'EMAIL': false});
    },
  );
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    for (final scale in [1.0, 2.0]) {
      for (final page in [
        'history',
        'inbox',
        'profile',
        'preferences',
        'detail',
      ]) {
        testWidgets('$page responsive $size text $scale', (t) async {
          final g = FakeActivity()
            ..rows = [page == 'inbox' ? notice : historical];
          final widget = switch (page) {
            'profile' => CitizenProfilePage(gateway: g),
            'preferences' => NotificationPreferencesPage(gateway: g),
            'detail' => ActivityDetailPage(
              history: true,
              data: {'session': historical, 'extensions': []},
            ),
            _ => ActivityListPage(resource: page, gateway: g),
          };
          await screen(t, widget, size: size, scale: scale);
          expect(t.takeException(), isNull);
        });
      }
    }
  }
  for (final count in [0, 3, 120]) {
    testWidgets('Badge $count uses backend count and fits 320px', (t) async {
      await screen(
        t,
        Scaffold(
          bottomNavigationBar: CitizenNavigation(
            unreadCount: count,
            onSelected: (_) {},
          ),
        ),
      );
      final badge = t.widget<Badge>(find.byType(Badge));
      expect(badge.isLabelVisible, count > 0);
      if (count == 120) expect(find.text('99+'), findsOneWidget);
      expect(t.takeException(), isNull);
    });
  }
}
