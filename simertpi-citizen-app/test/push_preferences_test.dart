import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/profile_page.dart';

import 'citizen_activity_test.dart' show FakeActivity;
import 'push_lifecycle_test.dart' show FakeRuntime, FakeApi, FakeStore, ownerA;

class PreferencesGateway extends FakeActivity {
  PreferencesGateway(this.api);
  final FakeApi api;
  Map<String, bool> current = {'PUSH': false, 'EMAIL': true};
  @override
  Future<Map<String, bool>> preferences() async => {
    ...current,
    'PUSH': api.pushEnabled,
  };
  @override
  Future<void> savePreferences(Map<String, bool> values) async {
    current = Map.of(values);
    api.pushEnabled = values['PUSH'] ?? false;
    await super.savePreferences(values);
  }
}

void main() {
  testWidgets('one switch disabled when Firebase is absent', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('simertpi/notification_settings'),
          (_) async => 'granted',
        );
    await tester.pumpWidget(
      MaterialApp(home: NotificationPreferencesPage(gateway: FakeActivity())),
    );
    await tester.pumpAndSettle();
    expect(find.byType(SwitchListTile), findsOneWidget);
    expect(
      tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
      isNull,
    );
    expect(find.textContaining('Firebase no configurado'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('simertpi/notification_settings'),
          null,
        );
  });
  for (final scenario in [
    'success',
    'registrationFailure',
    'permissionDenied',
    'settingsReturn',
  ]) {
    testWidgets('single push switch: $scenario', (tester) async {
      final runtime = FakeRuntime(),
          api = FakeApi()..pushEnabled = false,
          store = FakeStore();
      if (scenario == 'registrationFailure') runtime.currentToken = '';
      if (scenario == 'permissionDenied') {
        runtime.state = PushPermissionState.notDetermined;
        runtime.requestResult = PushPermissionState.denied;
      }
      if (scenario == 'settingsReturn') {
        runtime.state = PushPermissionState.systemSettingsRequired;
      }
      final gateway = PreferencesGateway(api);
      final push = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => ownerA,
        unreadNotifications: ValueNotifier(0),
        citizenTab: ValueNotifier(0),
        parkingSessionTarget: ValueNotifier(null),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: NotificationPreferencesPage(
            gateway: gateway,
            pushLifecycle: push,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(SwitchListTile), findsOneWidget);
      expect(find.text('Permitir notificaciones'), findsOneWidget);
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      if (scenario == 'settingsReturn') {
        expect(gateway.saved, isNull);
        runtime.state = PushPermissionState.granted;
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pumpAndSettle();
        // Settings grant now activates automatically; do not toggle it OFF.
      }
      if (scenario == 'success' || scenario == 'settingsReturn') {
        expect(await gateway.preferences(), {'PUSH': true, 'EMAIL': true});
        expect(
          tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
          isTrue,
        );
        await tester.tap(find.byType(SwitchListTile));
        await tester.pumpAndSettle();
        expect(await gateway.preferences(), {'PUSH': false, 'EMAIL': true});
        expect(runtime.presentationEnabled, isFalse);
        expect(api.disabled, isNotEmpty);
      } else {
        expect(
          tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
          isFalse,
        );
        expect(runtime.presentationEnabled, isFalse);
      }
      await tester.pumpWidget(const SizedBox());
      await push.dispose();
      await runtime.dispose();
    });
  }
}
