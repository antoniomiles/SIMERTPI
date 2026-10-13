import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/profile_page.dart';

import 'push_lifecycle_test.dart'
    show FakeRuntime, FakeApi, FakeStore, ownerA, ownerB;
import 'push_preferences_test.dart' show PreferencesGateway;

PushDeviceLifecycle lifecycle(
  FakeRuntime runtime,
  FakeApi api,
  FakeStore store, {
  String Function()? owner,
}) => PushDeviceLifecycle(
  runtime: runtime,
  api: api,
  store: store,
  isAuthenticated: () => true,
  ownerId: owner ?? () => ownerA,
  unreadNotifications: ValueNotifier(0),
  citizenTab: ValueNotifier(0),
  parkingSessionTarget: ValueNotifier(null),
);
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('initial ALLOW automatically saves PUSH and registers without second prompt', () async {
    final runtime = FakeRuntime(),
        api = FakeApi()..pushEnabled = false,
        store = FakeStore()..welcomePrompted = true;
    final push = lifecycle(runtime, api, store);
    await push.onAuthenticated();
    expect(api.pushEnabled, isTrue);
    expect(api.registrations, ['token-a']);
    expect(runtime.prompts, 0);
    expect(push.registrationState, PushRegistrationState.registered);
    expect(await push.shouldShowOnboarding(), isFalse);
    await push.dispose();
    await runtime.dispose();
  });
  test(
    'initial DENY stays OFF; Settings grant synchronizes automatically',
    () async {
      final runtime = FakeRuntime()..state = PushPermissionState.denied,
          api = FakeApi()..pushEnabled = false,
          store = FakeStore()..welcomePrompted = true;
      final push = lifecycle(runtime, api, store);
      await push.onAuthenticated();
      expect(api.pushEnabled, isFalse);
      expect(api.registrations, isEmpty);
      runtime.state = PushPermissionState.granted;
      await push.onResumed();
      expect(api.pushEnabled, isTrue);
      expect(api.registrations, ['token-a']);
      expect(runtime.prompts, 0);
      expect(push.registrationState, PushRegistrationState.registered);
      await push.dispose();
      await runtime.dispose();
    },
  );
  test(
    'voluntary OFF survives logout, fresh lifecycle and Settings grant',
    () async {
      final runtime = FakeRuntime(),
          api = FakeApi()..pushEnabled = false,
          store = FakeStore()..welcomePrompted = true;
      var push = lifecycle(runtime, api, store);
      await push.onAuthenticated();
      await push.disablePushVoluntarily();
      expect(api.pushEnabled, isFalse);
      final registrations = api.registrations.length;
      await push.logout(remote: true, ownerId: ownerA);
      await push.dispose();
      push = lifecycle(runtime, api, store);
      await push.onAuthenticated();
      await push.onResumed();
      expect(api.pushEnabled, isFalse);
      expect(api.registrations.length, registrations);
      expect(runtime.presentationEnabled, isFalse);
      await push.requestPermission();
      expect(api.pushEnabled, isTrue);
      expect(runtime.prompts, 0);
      expect(push.registrationState, PushRegistrationState.registered);
      await push.dispose();
      await runtime.dispose();
    },
  );
  test(
    'failed voluntary OFF remains locally closed and retries backend on resume',
    () async {
      final runtime = FakeRuntime(),
          api = FakeApi()..pushEnabled = false,
          store = FakeStore()..welcomePrompted = true;
      final push = lifecycle(runtime, api, store);
      await push.onAuthenticated();
      api.failSavePreference = true;
      await expectLater(push.disablePushVoluntarily(), throwsStateError);
      expect(runtime.presentationEnabled, isFalse);
      api.failSavePreference = false;
      await push.onResumed();
      expect(api.pushEnabled, isFalse);
      expect(runtime.presentationEnabled, isFalse);
      await push.dispose();
      await runtime.dispose();
    },
  );
  test('initial permission is claimed by one account only', () async {
    final runtime = FakeRuntime(),
        api = FakeApi()..pushEnabled = false,
        store = FakeStore()..welcomePrompted = true;
    var owner = ownerA;
    final push = lifecycle(runtime, api, store, owner: () => owner);
    await push.onAuthenticated();
    await push.logout(remote: true, ownerId: ownerA);
    owner = ownerB;
    api.pushEnabled = false;
    final writes = api.preferenceWrites.length;
    await push.onAuthenticated();
    expect(api.preferenceWrites.length, writes);
    expect(runtime.presentationEnabled, isFalse);
    expect(store.consents.contains(ownerB), isFalse);
    await push.dispose();
    await runtime.dispose();
  });
  test('FCM failure never becomes registered; resumes retry without another prompt', () async {
    final runtime = FakeRuntime()..currentToken = '',
        api = FakeApi()..pushEnabled = false,
        store = FakeStore()..welcomePrompted = true;
    final push = lifecycle(runtime, api, store);
    await push.onAuthenticated();
    expect(api.pushEnabled, isTrue);
    expect(push.registrationState, PushRegistrationState.failed);
    expect(runtime.presentationEnabled, isFalse);
    runtime.currentToken = 'synthetic-replacement';
    await push.onResumed();
    expect(push.registrationState, PushRegistrationState.registered);
    expect(runtime.prompts, 0);
    await push.dispose();
    await runtime.dispose();
  });
  test(
    'failed preference activation stays pending and retries safely',
    () async {
      final runtime = FakeRuntime(),
          api = FakeApi()
            ..pushEnabled = false
            ..failSavePreference = true,
          store = FakeStore()..welcomePrompted = true;
      final push = lifecycle(runtime, api, store);
      await push.onAuthenticated();
      expect(api.registrations, isEmpty);
      expect(push.registrationState, PushRegistrationState.failed);
      api.failSavePreference = false;
      await push.onResumed();
      expect(api.pushEnabled, isTrue);
      expect(runtime.presentationEnabled, isTrue);
      await push.dispose();
      await runtime.dispose();
    },
  );
  test(
    'legacy backend OFF without a pending activation is preserved',
    () async {
      final runtime = FakeRuntime(),
          api = FakeApi()..pushEnabled = false,
          store = FakeStore()..welcomePrompted = true;
      store.consents.add(ownerA);
      store.askedOwners.add(ownerA);
      final push = lifecycle(runtime, api, store);
      await push.onAuthenticated();
      expect(api.pushEnabled, isFalse);
      expect(api.preferenceWrites, isEmpty);
      await push.dispose();
      await runtime.dispose();
    },
  );
  testWidgets('Profile is ON after initial grant and OFF survives resume', (
    tester,
  ) async {
    final runtime = FakeRuntime(),
        api = FakeApi()..pushEnabled = false,
        store = FakeStore()..welcomePrompted = true;
    final push = lifecycle(runtime, api, store);
    await tester.pumpWidget(
      MaterialApp(
        home: NotificationPreferencesPage(
          gateway: PreferencesGateway(api),
          pushLifecycle: push,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
      isTrue,
    );
    expect(runtime.prompts, 0);
    await tester.tap(find.byType(SwitchListTile));
    await tester.pumpAndSettle();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(
      tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
      isFalse,
    );
    expect(api.pushEnabled, isFalse);
    await tester.pumpWidget(const SizedBox());
    await push.dispose();
    await runtime.dispose();
  });
  testWidgets(
    'Profile updates to ON after Settings grant, no second authorization',
    (tester) async {
      final runtime = FakeRuntime()..state = PushPermissionState.denied,
          api = FakeApi()..pushEnabled = false,
          store = FakeStore()..welcomePrompted = true;
      final push = lifecycle(runtime, api, store);
      await tester.pumpWidget(
        MaterialApp(
          home: NotificationPreferencesPage(
            gateway: PreferencesGateway(api),
            pushLifecycle: push,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        isFalse,
      );
      runtime.state = PushPermissionState.granted;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        isTrue,
      );
      expect(runtime.prompts, 0);
      await tester.pumpWidget(const SizedBox());
      await push.dispose();
      await runtime.dispose();
    },
  );
  testWidgets('Profile never shows ON when token registration failed', (
    tester,
  ) async {
    final runtime = FakeRuntime()..currentToken = '',
        api = FakeApi()..pushEnabled = false,
        store = FakeStore()..welcomePrompted = true;
    final push = lifecycle(runtime, api, store);
    await tester.pumpWidget(
      MaterialApp(
        home: NotificationPreferencesPage(
          gateway: PreferencesGateway(api),
          pushLifecycle: push,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
      isFalse,
    );
    expect(find.textContaining('pendiente'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await push.dispose();
    await runtime.dispose();
  });
}
