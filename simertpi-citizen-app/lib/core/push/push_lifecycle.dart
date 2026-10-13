import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../network/api_client.dart';

enum PushRegistrationState { idle, syncing, registered, disabled, failed }

enum PushPermissionState {
  unavailable,
  notDetermined,
  denied,
  systemSettingsRequired,
  granted,
}

class PushNavigationTarget {
  const PushNavigationTarget({
    required this.notificationId,
    required this.eventType,
    required this.resourceType,
    required this.resourceId,
  });
  final String notificationId, eventType, resourceType, resourceId;
  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );
  static PushNavigationTarget? parse(Map<String, dynamic> data) {
    final notificationId = data['notificationId'];
    final eventType = data['eventType'];
    final resourceType = data['resourceType'];
    final resourceId = data['resourceId'];
    if (notificationId is! String ||
        !_uuid.hasMatch(notificationId) ||
        !{
          'PARKING_STARTED',
          'PARKING_ENDING_SOON',
          'PARKING_TIME_EXPIRED',
          'PARKING_EXTENSION_CONFIRMED',
          'PARKING_COMPLETED',
          'VEHICLE_REGISTERED',
          'VEHICLE_DELETED',
        }.contains(eventType) ||
        resourceType !=
            ({'VEHICLE_REGISTERED', 'VEHICLE_DELETED'}.contains(eventType)
                ? 'VEHICLE'
                : 'PARKING_SESSION') ||
        resourceId is! String ||
        !_uuid.hasMatch(resourceId)) {
      return null;
    }
    return PushNavigationTarget(
      notificationId: notificationId,
      eventType: eventType as String,
      resourceType: resourceType as String,
      resourceId: resourceId,
    );
  }
}

abstract interface class PushRuntime {
  Future<void> setPresentationGate(
    String? ownerId, {
    required bool enabled,
    String? backendDeviceId,
  });
  Future<void> activate();
  Future<void> setAutoInitEnabled(bool enabled);
  Future<PushPermissionState> permission();
  Future<PushPermissionState> requestPermission();
  Future<String?> token();
  Stream<String> get tokenRefreshes;
  Stream<void> get foregroundMessages;
  Stream<PushNavigationTarget?> get openedMessages;
  Future<PushNavigationTarget?> initialTap();
  Future<void> deactivate();
  Future<void> openNotificationSettings();
}

abstract interface class PushDeviceStore {
  Future<String?> deviceId();
  Future<String?> token();
  Future<String?> registeredOwnerId();
  Future<bool> permissionWasRequested(String ownerId);
  Future<bool> consentGranted(String ownerId);
  Future<void> grantConsent(String ownerId);
  Future<bool> pushVoluntarilyDisabled(String ownerId);
  Future<void> setPushVoluntarilyDisabled(String ownerId, bool disabled);
  Future<bool> pushEnablePending(String ownerId);
  Future<void> setPushEnablePending(String ownerId, bool pending);
  Future<bool> claimWelcomePermission(String ownerId);
  Future<List<PendingPushRevocation>> pendingRevocations();
  Future<void> addPendingRevocation(String ownerId, String deviceId);
  Future<void> completePendingRevocation(String ownerId, String deviceId);
  Future<void> saveDevice(String id, String token, String ownerId);
  Future<void> markPermissionRequested(String ownerId);
  Future<bool> onboardingSeen(String ownerId);
  Future<void> markOnboardingSeen(String ownerId);
  Future<void> clearActive();
}

/// Device-scoped record for a single OS permission prompt from Welcome.
/// It is intentionally separate from a citizen's in-app PUSH consent.
abstract interface class WelcomePermissionPromptStore {
  Future<bool> welcomePermissionPrompted();
  Future<void> markWelcomePermissionPrompted();
}

abstract interface class NotificationPermissionGateway {
  Future<PushPermissionState> permission();
  Future<PushPermissionState> requestPermission();
}

/// Uses Android's OS permission API directly. This never initializes Firebase
/// and is safe to use from the signed-out Welcome screen.
class AndroidNotificationPermissionGateway
    implements NotificationPermissionGateway {
  const AndroidNotificationPermissionGateway();
  static const MethodChannel _channel = MethodChannel(
    'simertpi/notification_settings',
  );

  PushPermissionState _parse(String? state) => switch (state) {
    'granted' => PushPermissionState.granted,
    'denied' => PushPermissionState.denied,
    'notDetermined' => PushPermissionState.notDetermined,
    'systemSettingsRequired' => PushPermissionState.systemSettingsRequired,
    _ => PushPermissionState.unavailable,
  };

  @override
  Future<PushPermissionState> permission() async =>
      _parse(await _channel.invokeMethod<String>('permissionStatus'));

  @override
  Future<PushPermissionState> requestPermission() async => _parse(
    await _channel.invokeMethod<String>('requestNotificationPermission'),
  );
}

class WelcomePushPermissionPrompt {
  WelcomePushPermissionPrompt({
    required this.store,
    required this.gateway,
    required this.isAuthenticated,
  });
  final WelcomePermissionPromptStore store;
  final NotificationPermissionGateway gateway;
  final bool Function() isAuthenticated;
  bool _inFlight = false;

  Future<PushPermissionState> requestOnce() async {
    if (isAuthenticated() || _inFlight) return PushPermissionState.unavailable;
    _inFlight = true;
    try {
      final current = await gateway.permission();
      if (current == PushPermissionState.granted) {
        await store.markWelcomePermissionPrompted();
        return current;
      }
      if (current == PushPermissionState.unavailable ||
          current == PushPermissionState.systemSettingsRequired ||
          await store.welcomePermissionPrompted()) {
        return current == PushPermissionState.denied
            ? PushPermissionState.systemSettingsRequired
            : current;
      }
      // Persist before asking Android so a second mount or process restart
      // cannot repeat the system prompt. This marker is not citizen consent.
      await store.markWelcomePermissionPrompted();
      if (isAuthenticated()) return PushPermissionState.unavailable;
      final result = await gateway.requestPermission();
      return result == PushPermissionState.denied
          ? PushPermissionState.systemSettingsRequired
          : result;
    } catch (_) {
      return PushPermissionState.unavailable;
    } finally {
      _inFlight = false;
    }
  }
}

class PendingPushRevocation {
  const PendingPushRevocation(this.ownerId, this.deviceId);
  final String ownerId, deviceId;
  Map<String, String> toJson() => {'ownerId': ownerId, 'deviceId': deviceId};
}

abstract interface class PushDeviceApi {
  Future<String> register(String token);
  Future<void> disable(String id);
  Future<bool> pushPreferenceEnabled();
  Future<void> setPushPreferenceEnabled(bool enabled);
  Future<int> unreadCount();
}

class NotificationDeviceApi implements PushDeviceApi {
  const NotificationDeviceApi(this.client);
  final ApiClient client;
  @override
  Future<String> register(String token) async {
    final response = await client.request(
      ApiMethod.post,
      'notifications/devices',
      body: {'platform': 'ANDROID', 'token': token},
    );
    final body = response.body;
    if (body is! Map ||
        body['id'] is! String ||
        body['platform'] != 'ANDROID') {
      throw const FormatException('Invalid device registration response');
    }
    return body['id'] as String;
  }

  @override
  Future<void> disable(String id) async {
    await client.request(ApiMethod.delete, 'notifications/devices/$id');
  }

  @override
  Future<bool> pushPreferenceEnabled() async {
    final rows = (await client.request(
      ApiMethod.get,
      'notifications/preferences',
    )).body;
    if (rows is! List) return false;
    for (final row in rows) {
      if (row is Map && row['channel'] == 'PUSH') return row['enabled'] == true;
    }
    return false;
  }

  @override
  Future<void> setPushPreferenceEnabled(bool enabled) async {
    await client.request(
      ApiMethod.put,
      'notifications/preferences',
      body: {'PUSH': enabled},
    );
  }

  @override
  Future<int> unreadCount() async {
    final body = (await client.request(
      ApiMethod.get,
      'notifications/unread-count',
    )).body;
    if (body is! Map || body['unreadCount'] is! int) {
      throw const FormatException('Invalid unread count response');
    }
    return body['unreadCount'] as int;
  }
}

class SecurePushDeviceStore
    implements PushDeviceStore, WelcomePermissionPromptStore {
  SecurePushDeviceStore(String namespace)
    : _prefix = 'simertpi.push.v2.${base64Url.encode(utf8.encode(namespace))}';
  final String _prefix;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();
  String get _deviceKey => '$_prefix.device';
  String get _tokenKey => '$_prefix.token';
  String get _ownerKey => '$_prefix.owner';
  String _askedKey(String owner) =>
      '$_prefix.permission-requested.${base64Url.encode(utf8.encode(owner))}';
  String get _revocationsKey => '$_prefix.pending-revocations';
  String _onboardingKey(String owner) =>
      '$_prefix.onboarding-seen.${base64Url.encode(utf8.encode(owner))}';
  String _consentKey(String owner) =>
      '$_prefix.consent.${base64Url.encode(utf8.encode(owner))}';
  String get _welcomePermissionPromptKey =>
      '$_prefix.welcome-permission-prompted';
  @override
  Future<String?> deviceId() => _storage.read(key: _deviceKey);
  @override
  Future<String?> token() => _storage.read(key: _tokenKey);
  @override
  Future<String?> registeredOwnerId() => _storage.read(key: _ownerKey);
  @override
  Future<bool> permissionWasRequested(String ownerId) async =>
      await _storage.read(key: _askedKey(ownerId)) == 'true';
  @override
  Future<bool> welcomePermissionPrompted() async =>
      await _storage.read(key: _welcomePermissionPromptKey) == 'true';
  @override
  Future<void> markWelcomePermissionPrompted() =>
      _storage.write(key: _welcomePermissionPromptKey, value: 'true');
  @override
  Future<bool> onboardingSeen(String ownerId) async =>
      await _storage.read(key: _onboardingKey(ownerId)) == 'true';
  @override
  Future<void> markOnboardingSeen(String ownerId) =>
      _storage.write(key: _onboardingKey(ownerId), value: 'true');
  @override
  Future<bool> consentGranted(String ownerId) async =>
      await _storage.read(key: _consentKey(ownerId)) == 'true';
  @override
  Future<void> grantConsent(String ownerId) =>
      _storage.write(key: _consentKey(ownerId), value: 'true');
  String _choiceKey(String owner) =>
      '$_prefix.push-disabled.${base64Url.encode(utf8.encode(owner))}';
  String _pendingEnableKey(String owner) =>
      '$_prefix.push-enable-pending.${base64Url.encode(utf8.encode(owner))}';
  @override
  Future<bool> pushVoluntarilyDisabled(String ownerId) async =>
      await _storage.read(key: _choiceKey(ownerId)) == 'true';
  @override
  Future<void> setPushVoluntarilyDisabled(String ownerId, bool disabled) =>
      _storage.write(key: _choiceKey(ownerId), value: disabled.toString());
  @override
  Future<bool> pushEnablePending(String ownerId) async =>
      await _storage.read(key: _pendingEnableKey(ownerId)) == 'true';
  @override
  Future<void> setPushEnablePending(String ownerId, bool pending) => _storage
      .write(key: _pendingEnableKey(ownerId), value: pending.toString());
  @override
  Future<bool> claimWelcomePermission(String ownerId) async {
    if (!await welcomePermissionPrompted()) return false;
    final key = '$_prefix.welcome-permission-owner';
    final claimed = await _storage.read(key: key);
    if (claimed != null) return claimed == ownerId;
    await _storage.write(key: key, value: ownerId);
    return true;
  }

  @override
  Future<void> markPermissionRequested(String ownerId) =>
      _storage.write(key: _askedKey(ownerId), value: 'true');
  @override
  Future<void> saveDevice(String id, String token, String ownerId) async {
    await _storage.write(key: _deviceKey, value: id);
    await _storage.write(key: _tokenKey, value: token);
    await _storage.write(key: _ownerKey, value: ownerId);
  }

  @override
  Future<void> clearActive() async {
    await _storage.delete(key: _deviceKey);
    await _storage.delete(key: _tokenKey);
    await _storage.delete(key: _ownerKey);
  }

  @override
  Future<List<PendingPushRevocation>> pendingRevocations() async {
    final raw = await _storage.read(key: _revocationsKey);
    if (raw == null) return const [];
    try {
      final parsed = jsonDecode(raw);
      if (parsed is! List) return const [];
      return parsed
          .whereType<Map>()
          .map(
            (e) => PendingPushRevocation(
              e['ownerId'] as String,
              e['deviceId'] as String,
            ),
          )
          .toList();
    } catch (_) {
      return const [];
    }
  }

  Future<void> _writeRevocations(List<PendingPushRevocation> values) =>
      _storage.write(
        key: _revocationsKey,
        value: jsonEncode(values.map((e) => e.toJson()).toList()),
      );
  @override
  Future<void> addPendingRevocation(String ownerId, String deviceId) async {
    final values = await pendingRevocations();
    if (!values.any((e) => e.ownerId == ownerId && e.deviceId == deviceId)) {
      await _writeRevocations([
        ...values,
        PendingPushRevocation(ownerId, deviceId),
      ]);
    }
  }

  @override
  Future<void> completePendingRevocation(
    String ownerId,
    String deviceId,
  ) async {
    final values = await pendingRevocations();
    await _writeRevocations(
      values
          .where((e) => e.ownerId != ownerId || e.deviceId != deviceId)
          .toList(),
    );
  }
}

class PushDeviceLifecycle {
  PushDeviceLifecycle({
    required this.runtime,
    required this.api,
    required this.store,
    required this.isAuthenticated,
    required this.ownerId,
    required this.unreadNotifications,
    required this.citizenTab,
    required this.parkingSessionTarget,
  });
  final PushRuntime runtime;
  final PushDeviceApi api;
  final PushDeviceStore store;
  final bool Function() isAuthenticated;
  final String? Function() ownerId;
  final ValueNotifier<int> unreadNotifications, citizenTab;
  final ValueNotifier<String?> parkingSessionTarget;
  PushPermissionState permissionState = PushPermissionState.notDetermined;
  final registrationStatus = ValueNotifier(PushRegistrationState.idle);
  PushRegistrationState get registrationState => registrationStatus.value;
  set registrationState(PushRegistrationState state) =>
      registrationStatus.value = state;
  StreamSubscription<String>? _tokens;
  StreamSubscription<void>? _foreground;
  StreamSubscription<PushNavigationTarget?>? _opened;
  PushNavigationTarget? _pendingTarget;
  String? _pendingNavigationOwner;
  int _navigationRevision = 0;
  String? _lastTapId;
  DateTime? _lastTapAt;
  bool _syncing = false, _disposed = false, _initialRead = false;
  void start() {
    _tokens = runtime.tokenRefreshes.listen((_) => unawaited(sync()));
    _foreground = runtime.foregroundMessages.listen(
      (_) => unawaited(_refreshUnread()),
    );
    _opened = runtime.openedMessages.listen(
      (target) => unawaited(_handleTap(target)),
    );
    unawaited(refreshPermission());
  }

  Future<void> _readInitialTap() async {
    if (_initialRead) return;
    _initialRead = true;
    final owner = ownerId();
    final revision = _navigationRevision;
    try {
      final target = await runtime.initialTap();
      if (_disposed ||
          revision != _navigationRevision ||
          !isAuthenticated() ||
          ownerId() != owner) {
        return;
      }
      if (target != null) await _handleTap(target);
    } catch (_) {
      /* Missing/failed initial intent is not navigation. */
    }
  }

  Future<void> _handleTap(PushNavigationTarget? target) async {
    if (_disposed || target == null) return;
    final now = DateTime.now();
    if (_lastTapId == target.notificationId &&
        _lastTapAt != null &&
        now.difference(_lastTapAt!) < const Duration(seconds: 2)) {
      return;
    }
    _lastTapId = target.notificationId;
    _lastTapAt = now;
    _pendingTarget = target;
    _pendingNavigationOwner = ownerId();
    if (isAuthenticated()) await _deliverPendingTarget();
  }

  Future<void> _deliverPendingTarget() async {
    final target = _pendingTarget;
    if (target == null || !isAuthenticated()) return;
    if (_pendingNavigationOwner != null &&
        _pendingNavigationOwner != ownerId()) {
      _pendingTarget = null;
      return;
    }
    if (target.resourceType == 'VEHICLE') {
      // A deleted vehicle has no active detail; authenticated Profile exposes Mis vehiculos.
      citizenTab.value = 3;
      _pendingTarget = null;
      await _refreshUnread();
      return;
    }
    parkingSessionTarget.value = target.resourceId;
    citizenTab.value = 0;
    await _refreshUnread();
  }

  void completeParkingTarget(String sessionId) {
    if (_pendingTarget?.resourceId == sessionId) _pendingTarget = null;
  }

  Future<void> _refreshUnread() async {
    if (!isAuthenticated()) return;
    try {
      unreadNotifications.value = await api.unreadCount();
    } catch (_) {
      /* inbox refresh is best effort */
    }
  }

  Future<PushPermissionState> refreshPermission() async {
    try {
      // Preserve a confirmed same-account gate during transient revalidation.
      final actual = await runtime.permission();
      final currentOwner = ownerId();
      permissionState =
          actual == PushPermissionState.denied &&
              currentOwner != null &&
              await store.permissionWasRequested(currentOwner)
          ? PushPermissionState.systemSettingsRequired
          : actual;
      final savedOwner = await store.registeredOwnerId();
      if (currentOwner == null ||
          !isAuthenticated() ||
          savedOwner != currentOwner ||
          !await store.consentGranted(currentOwner) ||
          actual == PushPermissionState.denied ||
          actual == PushPermissionState.systemSettingsRequired) {
        await runtime.setPresentationGate(null, enabled: false);
        await runtime.deactivate();
      }
      if (actual == PushPermissionState.unavailable) {
        registrationState = PushRegistrationState.failed;
        return permissionState;
      }
      if (isAuthenticated()) await onAuthenticated();
    } catch (_) {
      permissionState = PushPermissionState.unavailable;
    }
    return permissionState;
  }

  Future<bool> shouldShowOnboarding() async {
    final owner = ownerId();
    return owner != null &&
        isAuthenticated() &&
        !await store.onboardingSeen(owner) &&
        !await store.permissionWasRequested(owner) &&
        !await store.consentGranted(owner);
  }

  Future<void> markOnboardingSeen() async {
    final owner = ownerId();
    if (owner != null && isAuthenticated()) {
      await store.markOnboardingSeen(owner);
    }
  }

  Future<PushPermissionState> requestPermission({
    bool registerImmediately = true,
  }) async {
    final requestedOwner = ownerId();
    if (requestedOwner == null || !isAuthenticated()) {
      return PushPermissionState.unavailable;
    }
    try {
      final actual = await runtime.permission();
      if (actual == PushPermissionState.denied ||
          actual == PushPermissionState.systemSettingsRequired) {
        final accountAlreadyAsked = await store.permissionWasRequested(
          requestedOwner,
        );
        final welcomeAlreadyAsked =
            store is WelcomePermissionPromptStore &&
            await (store as WelcomePermissionPromptStore)
                .welcomePermissionPrompted();
        if (actual == PushPermissionState.systemSettingsRequired ||
            accountAlreadyAsked ||
            welcomeAlreadyAsked) {
          await store.markPermissionRequested(requestedOwner);
          await store.setPushVoluntarilyDisabled(requestedOwner, false);
          await store.setPushEnablePending(requestedOwner, true);
          permissionState = PushPermissionState.systemSettingsRequired;
          await runtime.setAutoInitEnabled(false);
          return permissionState;
        }
      }
      await store.markPermissionRequested(requestedOwner);
      await store.setPushVoluntarilyDisabled(requestedOwner, false);
      await store.setPushEnablePending(requestedOwner, true);
      final state = actual == PushPermissionState.granted
          ? actual
          : await runtime.requestPermission();
      permissionState = state;
      final currentOwner = ownerId();
      if (state == PushPermissionState.granted &&
          currentOwner == requestedOwner &&
          isAuthenticated()) {
        await store.grantConsent(requestedOwner);
        if (registerImmediately) {
          await _enablePendingPreference(requestedOwner);
          await _readInitialTap();
          await sync();
        }
      } else {
        await runtime.setAutoInitEnabled(false);
        if (state == PushPermissionState.denied) {
          permissionState = PushPermissionState.systemSettingsRequired;
        }
      }
    } catch (_) {
      registrationState = PushRegistrationState.failed;
      if (permissionState != PushPermissionState.granted) {
        permissionState = PushPermissionState.unavailable;
      }
    }
    return permissionState;
  }

  Future<void> openSystemSettings() async {
    try {
      await runtime.openNotificationSettings();
    } catch (_) {
      permissionState = PushPermissionState.unavailable;
    }
  }

  Future<void> _enablePendingPreference(String owner) async {
    if (!isAuthenticated() ||
        ownerId() != owner ||
        await store.pushVoluntarilyDisabled(owner)) {
      return;
    }
    await api.setPushPreferenceEnabled(true);
    if (!isAuthenticated() || ownerId() != owner) return;
    if (await store.pushVoluntarilyDisabled(owner)) {
      await api.setPushPreferenceEnabled(false);
      return;
    }
    await store.setPushEnablePending(owner, false);
  }

  /// Explicit OFF is durable and closes presentation before any network operation.
  Future<void> disablePushVoluntarily() async {
    final owner = ownerId();
    if (owner == null || !isAuthenticated()) return;
    await store.setPushVoluntarilyDisabled(owner, true);
    await store.setPushEnablePending(owner, false);
    await runtime.setPresentationGate(null, enabled: false);
    registrationState = PushRegistrationState.disabled;
    try {
      await api.setPushPreferenceEnabled(false);
    } catch (_) {
      registrationState = PushRegistrationState.failed;
      rethrow;
    } finally {
      await _disableCurrent();
      await _deactivateBestEffort();
    }
  }

  Future<void> onAuthenticated() async {
    if (!isAuthenticated()) return;
    final owner = ownerId();
    if (owner == null) return;
    final savedOwner = await store.registeredOwnerId();
    if (savedOwner != owner) {
      await runtime.setPresentationGate(null, enabled: false);
    }
    await _reconcile(owner);
    if (!isAuthenticated() || ownerId() != owner) return;
    final savedId = await store.deviceId();
    if (savedOwner != null && savedOwner != owner && savedId != null) {
      await store.addPendingRevocation(savedOwner, savedId);
      await _deactivateBestEffort();
      await store.clearActive();
    }
    if (await store.pushVoluntarilyDisabled(owner)) {
      await runtime.setPresentationGate(null, enabled: false);
      try {
        if (await api.pushPreferenceEnabled()) {
          await api.setPushPreferenceEnabled(false);
        }
        await _disableCurrent();
        await _deactivateBestEffort();
      } catch (_) {
        registrationState = PushRegistrationState.failed;
      }
      return;
    }
    if (await store.claimWelcomePermission(owner) &&
        !await store.permissionWasRequested(owner) &&
        !await store.consentGranted(owner)) {
      await store.markPermissionRequested(owner);
      await store.setPushEnablePending(owner, true);
    }
    final permission = await runtime.permission();
    if (permission == PushPermissionState.granted &&
        await store.permissionWasRequested(owner)) {
      await store.grantConsent(owner);
    }
    if (!await store.consentGranted(owner)) {
      await runtime.setPresentationGate(null, enabled: false);
      await runtime.setAutoInitEnabled(false);
      final id = await store.deviceId();
      if (id != null && await store.registeredOwnerId() == owner) {
        await store.addPendingRevocation(owner, id);
        await _tryRevoke(owner, id);
        await store.clearActive();
        await _deactivateBestEffort();
      }
      return;
    }
    if (permission == PushPermissionState.unavailable) {
      registrationState = PushRegistrationState.failed;
      return;
    }
    if (permission != PushPermissionState.granted) {
      await runtime.setPresentationGate(null, enabled: false);
      await _disableCurrent();
      await runtime.deactivate();
      return;
    }
    bool preferenceEnabled;
    try {
      if (await store.pushEnablePending(owner)) {
        await _enablePendingPreference(owner);
      }
      preferenceEnabled = await api.pushPreferenceEnabled();
    } catch (_) {
      registrationState = PushRegistrationState.failed;
      return; // Unknown remote preference is not a confirmed revocation.
    }
    if (!preferenceEnabled) {
      await runtime.setPresentationGate(null, enabled: false);
      await _disableCurrent();
      await runtime.deactivate();
      return;
    }
    await runtime.activate();
    await runtime.setAutoInitEnabled(true);
    await _readInitialTap();
    await _deliverPendingTarget();
    await sync();
  }

  Future<void> _reconcile(String owner) async {
    for (final entry in await store.pendingRevocations()) {
      if (entry.ownerId == owner) await _tryRevoke(owner, entry.deviceId);
    }
  }

  Future<void> _tryRevoke(String owner, String id) async {
    if (!isAuthenticated() || ownerId() != owner) return;
    try {
      await api.disable(id);
      await store.completePendingRevocation(owner, id);
    } catch (_) {
      /* durable retry after same owner's next authentication */
    }
  }

  Future<void> sync() async {
    if (_disposed || _syncing || !isAuthenticated()) return;
    final owner = ownerId();
    if (owner == null ||
        !await store.consentGranted(owner) ||
        await store.pushVoluntarilyDisabled(owner)) {
      return;
    }
    if (await store.registeredOwnerId() != owner) {
      await runtime.setPresentationGate(null, enabled: false);
    }
    try {
      if (!await api.pushPreferenceEnabled()) {
        await runtime.setPresentationGate(null, enabled: false);
        await _disableCurrent();
        await runtime.deactivate();
        return;
      }
    } catch (_) {
      registrationState = PushRegistrationState.failed;
      return;
    }
    _syncing = true;
    registrationState = PushRegistrationState.syncing;
    try {
      final permission = await runtime.permission();
      if (permission == PushPermissionState.unavailable) {
        throw StateError('Permission temporarily unavailable');
      }
      if (permission != PushPermissionState.granted) {
        await _disableCurrent();
        return;
      }
      final previousOwner = await store.registeredOwnerId();
      final previousId = await store.deviceId();
      final previousToken = await store.token();
      if (previousOwner != null &&
          previousOwner != owner &&
          previousId != null) {
        await store.addPendingRevocation(previousOwner, previousId);
        await runtime.deactivate();
        await store.clearActive();
      }
      await runtime.activate();
      await runtime.setAutoInitEnabled(true);
      final token = await runtime.token();
      if (token == null ||
          token.isEmpty ||
          !isAuthenticated() ||
          ownerId() != owner) {
        return;
      }
      final id = await api.register(token);
      final rotatedOwnedDevice =
          previousOwner == owner &&
          previousId != null &&
          previousId != id &&
          previousToken != null &&
          previousToken != token;
      if (rotatedOwnedDevice) {
        // Persist before replacing the old local id so a crash cannot forget
        // a backend device that still needs revocation.
        await store.addPendingRevocation(owner, previousId);
      }
      await store.saveDevice(id, token, owner);
      // Only a successful authenticated registration may open Android's gate.
      if (isAuthenticated() &&
          ownerId() == owner &&
          !await store.pushVoluntarilyDisabled(owner)) {
        await runtime.setPresentationGate(
          owner,
          enabled: true,
          backendDeviceId: id,
        );
      }
      // A successful authenticated registration can transfer the token/device
      // to this owner. That backend response supersedes an old owner's queued
      // disable for the same device; it is not treated as a remote disable.
      for (final entry in await store.pendingRevocations()) {
        if (entry.ownerId != owner && entry.deviceId == id) {
          await store.completePendingRevocation(entry.ownerId, entry.deviceId);
        }
      }
      if (rotatedOwnedDevice) {
        await _tryRevoke(owner, previousId);
      } else if (previousOwner != null &&
          previousOwner != owner &&
          previousId != null &&
          previousId == id) {
        // The authenticated registration response confirms that the backend
        // reassigned this token/device to the new owner.
        await store.completePendingRevocation(previousOwner, previousId);
      }
      permissionState = PushPermissionState.granted;
      registrationState =
          isAuthenticated() &&
              ownerId() == owner &&
              !await store.pushVoluntarilyDisabled(owner)
          ? PushRegistrationState.registered
          : PushRegistrationState.disabled;
    } catch (_) {
      registrationState = PushRegistrationState.failed;
      /* retry after auth restore, token rotation, settings change or resume */
    } finally {
      if (registrationState == PushRegistrationState.syncing) {
        registrationState = PushRegistrationState.failed;
      }
      _syncing = false;
    }
  }

  Future<void> _disableCurrent() async {
    registrationState = PushRegistrationState.disabled;
    await runtime.setPresentationGate(null, enabled: false);
    if (!isAuthenticated()) return;
    final id = await store.deviceId(), owner = await store.registeredOwnerId();
    if (id == null || owner == null || owner != ownerId()) return;
    await store.addPendingRevocation(owner, id);
    await _tryRevoke(owner, id);
  }

  Future<void> _deactivateBestEffort() async {
    try {
      await runtime.deactivate();
    } catch (_) {
      // The native owner/consent gate is already closed. A backend token may
      // remain temporarily active, but cannot be rendered on this account.
    }
  }

  Future<void> logout({required bool remote, required String? ownerId}) async {
    // Cancel navigation only; CP19.1 device revocation and voluntary OFF remain intact.
    _navigationRevision++;
    _pendingTarget = null;
    _pendingNavigationOwner = null;
    _lastTapId = null;
    _lastTapAt = null;
    registrationState = PushRegistrationState.disabled;
    // Close the local presentation gate before remote revocation or Firebase
    // token deletion, either of which can fail while the old server row lives.
    // Do not continue clearing the authenticated owner if native invalidation
    // was not acknowledged: a later cold FCM process could otherwise restore it.
    await runtime.setPresentationGate(null, enabled: false);
    final id = await store.deviceId();
    final registeredOwner = await store.registeredOwnerId();
    final owner = registeredOwner ?? ownerId;
    if (id != null && owner != null) {
      await store.addPendingRevocation(
        owner,
        id,
      ); // persist before any network attempt
      if (remote && owner == ownerId && isAuthenticated()) {
        await _tryRevoke(owner, id);
      }
    }
    try {
      await runtime.deactivate();
    } catch (_) {}
    await store.clearActive();
  }

  Future<void> onResumed() => refreshPermission();
  Future<void> dispose() async {
    _disposed = true;
    await _tokens?.cancel();
    await _foreground?.cancel();
    await _opened?.cancel();
  }
}
