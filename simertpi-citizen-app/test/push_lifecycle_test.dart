import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';

const ownerA = 'citizen-a';
const ownerB = 'citizen-b';
const sessionId = '00000000-0000-4000-8000-000000000001';
const notificationId = '00000000-0000-4000-8000-000000000002';

class FakeRuntime implements PushRuntime {
  PushPermissionState state = PushPermissionState.granted;
  String currentToken = 'token-a';
  int tokenReads = 0, activations = 0, prompts = 0;
  bool deleted = false, autoInit = false;
  String? presentationOwner;
  bool presentationEnabled = false, failDelete = false;
  PushNavigationTarget? initial;
  final tokens = StreamController<String>.broadcast();
  final foreground = StreamController<void>.broadcast();
  final opened = StreamController<PushNavigationTarget?>.broadcast();
  @override
  Future<void> activate() async {
    activations++;
  }

  @override
  Future<void> setAutoInitEnabled(bool enabled) async {
    autoInit = enabled;
  }

  @override
  Future<void> setPresentationGate(
    String? ownerId, {
    required bool enabled,
  }) async {
    presentationOwner = enabled ? ownerId : null;
    presentationEnabled = enabled && ownerId != null;
  }

  @override
  Future<PushPermissionState> permission() async => state;
  @override
  Future<PushPermissionState> requestPermission() async {
    prompts++;
    activations++;
    return state;
  }

  @override
  Future<String?> token() async {
    tokenReads++;
    return currentToken;
  }

  @override
  Stream<String> get tokenRefreshes => tokens.stream;
  @override
  Stream<void> get foregroundMessages => foreground.stream;
  @override
  Stream<PushNavigationTarget?> get openedMessages => opened.stream;
  @override
  Future<PushNavigationTarget?> initialTap() async => initial;
  @override
  Future<void> deactivate() async {
    autoInit = false;
    deleted = true;
    if (failDelete) throw StateError('deleteToken failed');
  }

  @override
  Future<void> openNotificationSettings() async {}
  Future<void> dispose() async {
    await tokens.close();
    await foreground.close();
    await opened.close();
  }
}

class FakeStore implements PushDeviceStore {
  String? id, savedToken, savedOwner;
  final askedOwners = <String>{};
  bool cleared = false;
  final consents = <String>{};
  final revocations = <PendingPushRevocation>[];
  @override
  Future<String?> deviceId() async => id;
  @override
  Future<String?> token() async => savedToken;
  @override
  Future<String?> registeredOwnerId() async => savedOwner;
  @override
  Future<bool> permissionWasRequested(String owner) async =>
      askedOwners.contains(owner);
  @override
  Future<bool> consentGranted(String owner) async => consents.contains(owner);
  @override
  Future<void> grantConsent(String owner) async {
    consents.add(owner);
  }

  @override
  Future<List<PendingPushRevocation>> pendingRevocations() async =>
      List.unmodifiable(revocations);
  @override
  Future<void> addPendingRevocation(String owner, String device) async {
    if (!revocations.any((e) => e.ownerId == owner && e.deviceId == device)) {
      revocations.add(PendingPushRevocation(owner, device));
    }
  }

  @override
  Future<void> completePendingRevocation(String owner, String device) async {
    revocations.removeWhere((e) => e.ownerId == owner && e.deviceId == device);
  }

  @override
  Future<void> saveDevice(String value, String token, String owner) async {
    id = value;
    savedToken = token;
    savedOwner = owner;
  }

  @override
  Future<void> markPermissionRequested(String owner) async {
    askedOwners.add(owner);
  }

  @override
  Future<void> clearActive() async {
    id = null;
    savedToken = null;
    savedOwner = null;
    cleared = true;
  }
}

class FakeApi implements PushDeviceApi {
  final registrations = <String>[];
  final disabled = <String>[];
  bool failDisable = false;
  String? registerResult;
  int count = 0;
  @override
  Future<String> register(String token) async {
    registrations.add(token);
    return registerResult ?? 'device-${registrations.length}';
  }

  @override
  Future<void> disable(String id) async {
    if (failDisable) throw StateError('offline');
    disabled.add(id);
  }

  @override
  Future<int> unreadCount() async => count;
}

PushNavigationTarget target(String type) => PushNavigationTarget(
  notificationId: notificationId,
  eventType: type,
  resourceType: 'PARKING_SESSION',
  resourceId: sessionId,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('no Firebase activation, token fetch or registration before explicit consent', () async {
    final runtime = FakeRuntime(), api = FakeApi(), store = FakeStore();
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => true,
      ownerId: () => ownerA,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    )..start();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(runtime.activations, 0);
    expect(runtime.tokenReads, 0);
    expect(api.registrations, isEmpty);
    expect(runtime.presentationEnabled, isFalse);
    expect(await lifecycle.requestPermission(), PushPermissionState.granted);
    expect(store.consents, contains(ownerA));
    expect(runtime.autoInit, isTrue);
    expect(api.registrations, ['token-a']);
    expect(runtime.presentationEnabled, isTrue);
    expect(runtime.presentationOwner, ownerA);
    await lifecycle.dispose();
    await runtime.dispose();
  });
  test(
    'denial leaves citizen operations usable and never fetches a token',
    () async {
      final runtime = FakeRuntime()..state = PushPermissionState.denied,
          api = FakeApi(),
          store = FakeStore();
      final lifecycle = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => ownerA,
        unreadNotifications: ValueNotifier(0),
        citizenTab: ValueNotifier(0),
        parkingSessionTarget: ValueNotifier(null),
      );
      expect(
        await lifecycle.requestPermission(),
        PushPermissionState.systemSettingsRequired,
      );
      expect(runtime.tokenReads, 0);
      expect(api.registrations, isEmpty);
      expect(runtime.autoInit, isFalse);
      expect(runtime.presentationEnabled, isFalse);
      await lifecycle.dispose();
      await runtime.dispose();
    },
  );
  test(
    'one citizen consent is never inherited by another account on the device',
    () async {
      final runtime = FakeRuntime();
      final api = FakeApi();
      final store = FakeStore()..askedOwners.add(ownerA);
      final lifecycle = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => ownerB,
        unreadNotifications: ValueNotifier(0),
        citizenTab: ValueNotifier(0),
        parkingSessionTarget: ValueNotifier(null),
      );
      await lifecycle.onAuthenticated();
      expect(store.consents, isEmpty);
      expect(runtime.tokenReads, 0);
      expect(api.registrations, isEmpty);
      await lifecycle.dispose();
      await runtime.dispose();
    },
  );
  test('permission revoked deactivates owned backend registration without asking Firebase for token', () async {
    final runtime = FakeRuntime()..state = PushPermissionState.denied,
        api = FakeApi(),
        store = FakeStore()
          ..id = 'device-a'
          ..savedOwner = ownerA
          ..consents.add(ownerA);
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => true,
      ownerId: () => ownerA,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    );
    await lifecycle.refreshPermission();
    expect(api.disabled, ['device-a']);
    expect(runtime.tokenReads, 0);
    expect(store.revocations, isEmpty);
    await lifecycle.dispose();
    await runtime.dispose();
  });
  test('failed logout revocation is durably reconciled only after same owner authenticates', () async {
    final runtime = FakeRuntime(),
        api = FakeApi()..failDisable = true,
        store = FakeStore()
          ..id = 'device-a'
          ..savedOwner = ownerA
          ..savedToken = 'token-a'
          ..consents.add(ownerA);
    var authenticated = true, currentOwner = ownerA;
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => authenticated,
      ownerId: () => currentOwner,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    );
    await lifecycle.logout(remote: true, ownerId: ownerA);
    expect(api.disabled, isEmpty);
    expect(store.revocations.single.ownerId, ownerA);
    expect(store.id, isNull);
    expect(runtime.deleted, isTrue);
    api.failDisable = false;
    authenticated = true;
    currentOwner = ownerA;
    await lifecycle.onAuthenticated();
    expect(api.disabled, ['device-a']);
    expect(store.revocations, isEmpty);
    expect(api.registrations, ['token-a']);
    await lifecycle.dispose();
    await runtime.dispose();
  });
  test('critical shared-device sequence fails closed when logout remote and deleteToken both fail', () async {
    final runtime = FakeRuntime()..failDelete = true;
    final api = FakeApi()..failDisable = true;
    final store = FakeStore()
      ..id = 'device-a'
      ..savedOwner = ownerA
      ..savedToken = 'token-a'
      ..consents.add(ownerA);
    var authenticated = true, currentOwner = ownerA;
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => authenticated,
      ownerId: () => currentOwner,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    );

    await lifecycle.logout(remote: false, ownerId: ownerA);
    expect(store.revocations.single.ownerId, ownerA);
    expect(api.disabled, isEmpty);
    expect(store.id, isNull);
    expect(runtime.presentationEnabled, isFalse);
    expect(runtime.presentationOwner, isNull);

    authenticated = true;
    currentOwner = ownerB;
    await lifecycle.onAuthenticated();
    expect(runtime.presentationEnabled, isFalse);
    expect(runtime.tokenReads, 0);
    expect(api.registrations, isEmpty);
    expect(api.disabled, isEmpty, reason: 'B cannot revoke A\'s device');
    expect(store.revocations.single.ownerId, ownerA);

    await lifecycle.dispose();
    await runtime.dispose();
  });
  test(
    'consenting account registers reused token before its display gate opens',
    () async {
      final runtime = FakeRuntime()..failDelete = true;
      final api = FakeApi()..registerResult = 'device-a';
      final store = FakeStore()
        ..revocations.add(const PendingPushRevocation(ownerA, 'device-a'))
        ..consents.add(ownerB);
      final lifecycle = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => ownerB,
        unreadNotifications: ValueNotifier(0),
        citizenTab: ValueNotifier(0),
        parkingSessionTarget: ValueNotifier(null),
      );

      await lifecycle.onAuthenticated();
      expect(api.registrations, ['token-a']);
      expect(store.savedOwner, ownerB);
      expect(
        store.revocations,
        isEmpty,
        reason: 'backend registration confirmed token transfer to B',
      );
      expect(runtime.presentationEnabled, isTrue);
      expect(runtime.presentationOwner, ownerB);

      await lifecycle.dispose();
      await runtime.dispose();
    },
  );
  test('a rotated token registers the replacement and durably revokes the old device', () async {
    final runtime = FakeRuntime()..currentToken = 'token-b';
    final api = FakeApi();
    final store = FakeStore()
      ..id = 'device-old'
      ..savedOwner = ownerA
      ..savedToken = 'token-a'
      ..consents.add(ownerA);
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => true,
      ownerId: () => ownerA,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    );
    await lifecycle.onAuthenticated();
    expect(api.registrations, ['token-b']);
    expect(api.disabled, ['device-old']);
    expect(store.savedToken, 'token-b');
    expect(store.revocations, isEmpty);
    await lifecycle.dispose();
    await runtime.dispose();
  });
  test('a different account cannot reconcile another owner device; device token is rotated', () async {
    final runtime = FakeRuntime(),
        api = FakeApi(),
        store = FakeStore()
          ..id = 'device-a'
          ..savedOwner = ownerA
          ..savedToken = 'old-token'
          ..consents.add(ownerB);
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => true,
      ownerId: () => ownerB,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    );
    await lifecycle.onAuthenticated();
    expect(api.disabled, isEmpty);
    expect(store.revocations.single.ownerId, ownerA);
    expect(store.savedOwner, ownerB);
    await lifecycle.dispose();
    await runtime.dispose();
  });
  test('foreground refreshes badge; ending-soon and extension taps route to owned active parking without starting a payment', () async {
    final runtime = FakeRuntime(),
        api = FakeApi()..count = 7,
        store = FakeStore()..consents.add(ownerA);
    final badge = ValueNotifier(0),
        tab = ValueNotifier(2),
        parkingTarget = ValueNotifier<String?>(null);
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => true,
      ownerId: () => ownerA,
      unreadNotifications: badge,
      citizenTab: tab,
      parkingSessionTarget: parkingTarget,
    )..start();
    runtime.foreground.add(null);
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(badge.value, 7);
    runtime.opened.add(target('PARKING_ENDING_SOON'));
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(tab.value, 0);
    expect(parkingTarget.value, sessionId);
    expect(api.registrations, ['token-a']);
    runtime.opened.add(target('PARKING_EXTENSION_CONFIRMED'));
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(parkingTarget.value, sessionId);
    await lifecycle.dispose();
    await runtime.dispose();
  });
  test('invalid or unsupported payload falls back to inbox', () async {
    final runtime = FakeRuntime(), api = FakeApi(), store = FakeStore();
    final tab = ValueNotifier(0);
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => true,
      ownerId: () => ownerA,
      unreadNotifications: ValueNotifier(0),
      citizenTab: tab,
      parkingSessionTarget: ValueNotifier(null),
    )..start();
    runtime.opened.add(null);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(tab.value, 2);
    await lifecycle.dispose();
    await runtime.dispose();
  });
}
