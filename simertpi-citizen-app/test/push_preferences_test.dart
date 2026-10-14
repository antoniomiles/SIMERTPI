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
  bool failSave = false;
  @override
  Future<Map<String, bool>> preferences() async => {
    ...current,
    'PUSH': api.pushEnabled,
  };
  @override
  Future<void> savePreferences(Map<String, bool> values) async {
    if (failSave) throw StateError('save failed');
    current = Map.of(values);
    api.pushEnabled = values['PUSH'] ?? false;
    await super.savePreferences(values);
  }
}

void main() {
  testWidgets(
    'two switches are shown and PUSH is disabled when Firebase is absent',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('simertpi/notification_settings'),
            (_) async => 'granted',
          );
      await tester.pumpWidget(
        MaterialApp(home: NotificationPreferencesPage(gateway: FakeActivity())),
      );
      await tester.pumpAndSettle();
      expect(find.byType(SwitchListTile), findsNWidgets(2));
      final pushSwitch = find.ancestor(
        of: find.text('Notificaciones PUSH'),
        matching: find.byType(SwitchListTile),
      );
      expect(
        find.text('Notificaciones por correo electrónico'),
        findsOneWidget,
      );
      expect(tester.widget<SwitchListTile>(pushSwitch).onChanged, isNull);
      expect(find.textContaining('Firebase no configurado'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('simertpi/notification_settings'),
            null,
          );
    },
  );
  for (final scenario in [
    'success',
    'registrationFailure',
    'permissionDenied',
    'settingsReturn',
  ]) {
    testWidgets('PUSH switch: $scenario', (tester) async {
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
      expect(find.byType(SwitchListTile), findsNWidgets(2));
      final pushSwitch = find.ancestor(
        of: find.text('Notificaciones PUSH'),
        matching: find.byType(SwitchListTile),
      );
      expect(pushSwitch, findsOneWidget);
      await tester.tap(pushSwitch);
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
        expect(tester.widget<SwitchListTile>(pushSwitch).value, isTrue);
        await tester.tap(pushSwitch);
        await tester.pumpAndSettle();
        expect(await gateway.preferences(), {'PUSH': false, 'EMAIL': true});
        expect(runtime.presentationEnabled, isFalse);
        expect(api.disabled, isNotEmpty);
      } else {
        expect(tester.widget<SwitchListTile>(pushSwitch).value, isFalse);
        expect(runtime.presentationEnabled, isFalse);
      }
      await tester.pumpWidget(const SizedBox());
      await push.dispose();
      await runtime.dispose();
    });
  }

  for (final pushEnabled in [false, true]) {
    for (final emailEnabled in [false, true]) {
      testWidgets('initial state PUSH=$pushEnabled EMAIL=$emailEnabled', (
        tester,
      ) async {
        final runtime = FakeRuntime(),
            api = FakeApi()..pushEnabled = pushEnabled,
            store = FakeStore();
        store.consents.add(ownerA);
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
        if (pushEnabled) await push.sync();
        final gateway = PreferencesGateway(api)
          ..current = {'PUSH': pushEnabled, 'EMAIL': emailEnabled};
        await tester.pumpWidget(
          MaterialApp(
            home: NotificationPreferencesPage(
              gateway: gateway,
              pushLifecycle: push,
            ),
          ),
        );
        await tester.pumpAndSettle();
        final pushSwitch = find.ancestor(
          of: find.text('Notificaciones PUSH'),
          matching: find.byType(SwitchListTile),
        );
        final emailSwitch = find.ancestor(
          of: find.text('Notificaciones por correo electrónico'),
          matching: find.byType(SwitchListTile),
        );
        expect(tester.widget<SwitchListTile>(pushSwitch).value, pushEnabled);
        expect(tester.widget<SwitchListTile>(emailSwitch).value, emailEnabled);
        await tester.pumpWidget(const SizedBox());
        await push.dispose();
        await runtime.dispose();
      });
    }
  }

  testWidgets(
    'EMAIL saves independently without asking Android and persists after reopening',
    (tester) async {
      final runtime = FakeRuntime(),
          api = FakeApi()..pushEnabled = false,
          store = FakeStore();
      final gateway = PreferencesGateway(api)
        ..current = {'PUSH': false, 'EMAIL': false};
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
      var emailSwitch = find.ancestor(
        of: find.text('Notificaciones por correo electrónico'),
        matching: find.byType(SwitchListTile),
      );
      await tester.tap(emailSwitch);
      await tester.pumpAndSettle();
      expect(gateway.saved, {'PUSH': false, 'EMAIL': true});
      expect(api.pushEnabled, isFalse);
      expect(runtime.prompts, 0);
      await push.logout(remote: false, ownerId: ownerA);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(
        MaterialApp(
          home: NotificationPreferencesPage(
            gateway: gateway,
            pushLifecycle: push,
          ),
        ),
      );
      await tester.pumpAndSettle();
      emailSwitch = find.ancestor(
        of: find.text('Notificaciones por correo electrónico'),
        matching: find.byType(SwitchListTile),
      );
      expect(tester.widget<SwitchListTile>(emailSwitch).value, isTrue);
      expect(
        tester
            .widget<SwitchListTile>(
              find.ancestor(
                of: find.text('Notificaciones PUSH'),
                matching: find.byType(SwitchListTile),
              ),
            )
            .value,
        isFalse,
      );
      await tester.pumpWidget(const SizedBox());
      await push.dispose();
      await runtime.dispose();
    },
  );

  testWidgets('PUSH activation and voluntary OFF preserve EMAIL=false', (
    tester,
  ) async {
    final runtime = FakeRuntime(),
        api = FakeApi()..pushEnabled = false,
        store = FakeStore();
    final gateway = PreferencesGateway(api)
      ..current = {'PUSH': false, 'EMAIL': false};
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
    final pushSwitch = find.ancestor(
      of: find.text('Notificaciones PUSH'),
      matching: find.byType(SwitchListTile),
    );
    await tester.tap(pushSwitch);
    await tester.pumpAndSettle();
    expect(await gateway.preferences(), {'PUSH': true, 'EMAIL': false});
    expect(tester.widget<SwitchListTile>(pushSwitch).value, isTrue);
    await tester.tap(pushSwitch);
    await tester.pumpAndSettle();
    expect(await gateway.preferences(), {'PUSH': false, 'EMAIL': false});
    expect(tester.widget<SwitchListTile>(pushSwitch).value, isFalse);
    await tester.pumpWidget(const SizedBox());
    await push.dispose();
    await runtime.dispose();
  });

  testWidgets('EMAIL save failure does not simulate success', (tester) async {
    final runtime = FakeRuntime(), api = FakeApi(), store = FakeStore();
    final gateway = PreferencesGateway(api)
      ..current = {'PUSH': false, 'EMAIL': false}
      ..failSave = true;
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
    final emailSwitch = find.ancestor(
      of: find.text('Notificaciones por correo electrónico'),
      matching: find.byType(SwitchListTile),
    );
    await tester.tap(emailSwitch);
    await tester.pumpAndSettle();
    expect(tester.widget<SwitchListTile>(emailSwitch).value, isFalse);
    expect(find.textContaining('No pudimos guardar'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await push.dispose();
    await runtime.dispose();
  });
}
