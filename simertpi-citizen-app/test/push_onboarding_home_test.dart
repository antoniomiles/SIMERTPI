import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/home/home_page.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/activity_service.dart';

import 'active_parking_test.dart' as active_fixture;
import 'push_lifecycle_test.dart' as push_fixture;
import 'support/auth_test_support.dart';

class FakeActivity implements ActivityGateway {
  Map<String, bool>? saved;
  @override
  Future<ActivitySlice> list(String resource, int offset) async =>
      const ActivitySlice([], false);
  @override
  Future<CitizenData> detail(String resource, String id) async => {};
  @override
  Future<CitizenData> read(String id) async => {};
  @override
  Future<int> unread() async => 0;
  @override
  Future<CitizenData> profile() async => {};
  @override
  Future<Map<String, bool>> preferences() async => {
    'PUSH': true,
    'EMAIL': false,
  };
  @override
  Future<void> savePreferences(Map<String, bool> values) async {
    saved = Map.of(values);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  for (final accept in [false, true]) {
    testWidgets(
      'First authenticated Home invites PUSH despite saved account preference; accept=$accept',
      (tester) async {
        final auth = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
        );
        await auth.login('fixture-citizen', 'fixture-password');
        final runtime = push_fixture.FakeRuntime();
        final pushApi = push_fixture.FakeApi()..pushEnabled = true;
        final activity = FakeActivity();
        final store = push_fixture.FakeStore();
        final unread = ValueNotifier<int>(0);
        final tab = ValueNotifier<int>(0);
        final target = ValueNotifier<String?>(null);
        final lifecycle = PushDeviceLifecycle(
          runtime: runtime,
          api: pushApi,
          store: store,
          isAuthenticated: () => auth.isAuthenticated,
          ownerId: () => auth.userId,
          unreadNotifications: unread,
          citizenTab: tab,
          parkingSessionTarget: target,
        )..start();
        final active = active_fixture.controller(active_fixture.FakeActive());
        await tester.pumpWidget(
          AppScope(
            config: AppConfig.parse(environment: 'dev'),
            auth: auth,
            pushLifecycle: lifecycle,
            unreadNotifications: unread,
            citizenTab: tab,
            parkingSessionTarget: target,
            child: MaterialApp(
              home: HomePage(
                activeController: active,
                activityGateway: activity,
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('Activa tus notificaciones'), findsOneWidget);
        expect(find.text('Ahora no'), findsOneWidget);
        expect(find.text('Activar notificaciones'), findsOneWidget);

        if (accept) {
          await tester.tap(find.text('Activar notificaciones'));
          await tester.pumpAndSettle();
          // The OS permission was already granted; the in-app opt-in must not
          // request it a second time. Device registration follows app consent.
          expect(runtime.prompts, 0);
          expect(pushApi.registrations, ['token-a']);
          expect(activity.saved?['PUSH'], isTrue);
        } else {
          await tester.tap(find.text('Ahora no'));
          await tester.pumpAndSettle();
          expect(runtime.prompts, 0);
          expect(runtime.tokenReads, 0);
          expect(pushApi.registrations, isEmpty);
        }

        lifecycle.dispose();
        await runtime.dispose();
        active.dispose();
        auth.dispose();
        unread.dispose();
        tab.dispose();
        target.dispose();
      },
    );
  }
}
