import 'dart:async';
import 'dart:io';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/services.dart';

import 'push_lifecycle.dart';

@pragma('vm:entry-point')
Future<void> simertpiFirebaseBackgroundMessage(RemoteMessage message) async {
  // The notification is displayed by Android; never create an inbox event here.
}

class FirebasePushRuntime implements PushRuntime {
  FirebasePushRuntime(this._options);
  final FirebaseOptions _options;
  static const _settings = MethodChannel('simertpi/notification_settings');
  FirebaseMessaging? _messaging;
  final _tokenEvents = StreamController<String>.broadcast();
  final _foregroundEvents = StreamController<void>.broadcast();
  final _openedEvents = StreamController<PushNavigationTarget?>.broadcast();
  StreamSubscription<String>? _tokenSubscription;
  StreamSubscription<RemoteMessage>? _foregroundSubscription,
      _openedSubscription;
  static FirebasePushRuntime? fromDefines() {
    if (!Platform.isAndroid) return null;
    const apiKey = String.fromEnvironment('FCM_FIREBASE_API_KEY');
    const appId = String.fromEnvironment('FCM_FIREBASE_APP_ID');
    const senderId = String.fromEnvironment('FCM_FIREBASE_SENDER_ID');
    const projectId = String.fromEnvironment('FCM_FIREBASE_PROJECT_ID');
    if ([apiKey, appId, senderId, projectId].any((value) => value.isEmpty)) {
      return null;
    }
    return FirebasePushRuntime(
      const FirebaseOptions(
        apiKey: apiKey,
        appId: appId,
        messagingSenderId: senderId,
        projectId: projectId,
      ),
    );
  }

  Future<FirebaseMessaging> _activate() async {
    if (_messaging != null) return _messaging!;
    if (Firebase.apps.isEmpty) await Firebase.initializeApp(options: _options);
    final messaging = FirebaseMessaging.instance;
    // Native manifest also disables auto-init before Firebase is ever initialized.
    await messaging.setAutoInitEnabled(false);
    FirebaseMessaging.onBackgroundMessage(simertpiFirebaseBackgroundMessage);
    _messaging = messaging;
    _settings.setMethodCallHandler((call) async {
      if (call.method == 'pushOpened' && call.arguments is Map) {
        _openedEvents.add(
          PushNavigationTarget.parse(
            Map<String, dynamic>.from(call.arguments as Map),
          ),
        );
        return true;
      }
      return false;
    });
    _tokenSubscription = messaging.onTokenRefresh.listen(_tokenEvents.add);
    _foregroundSubscription = FirebaseMessaging.onMessage.listen(
      (_) => _foregroundEvents.add(null),
    );
    _openedSubscription = FirebaseMessaging.onMessageOpenedApp.listen(
      (message) => _openedEvents.add(PushNavigationTarget.parse(message.data)),
    );
    return messaging;
  }

  @override
  Future<void> activate() async {
    await _activate();
  }

  @override
  Future<void> setAutoInitEnabled(bool enabled) async {
    if (_messaging != null) await _messaging!.setAutoInitEnabled(enabled);
  }

  @override
  Future<void> setPresentationGate(
    String? ownerId, {
    required bool enabled,
  }) async {
    final persisted = await _settings.invokeMethod<bool>(
      'setPresentationGate',
      {
        'ownerId': enabled ? ownerId : null,
        'enabled': enabled && ownerId != null,
      },
    );
    if (enabled && persisted != true) {
      throw StateError('Native push presentation gate did not persist');
    }
  }

  PushPermissionState _map(String? state) => switch (state) {
    'granted' => PushPermissionState.granted,
    'denied' => PushPermissionState.denied,
    'notDetermined' => PushPermissionState.notDetermined,
    'systemSettingsRequired' => PushPermissionState.systemSettingsRequired,
    _ => PushPermissionState.unavailable,
  };
  @override
  Future<PushPermissionState> permission() async =>
      _map(await _settings.invokeMethod<String>('permissionStatus'));
  @override
  Future<PushPermissionState> requestPermission() async {
    final messaging = await _activate();
    final settings = await messaging.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );
    final state = switch (settings.authorizationStatus) {
      AuthorizationStatus.authorized ||
      AuthorizationStatus.provisional => PushPermissionState.granted,
      AuthorizationStatus.notDetermined => PushPermissionState.notDetermined,
      AuthorizationStatus.denied => PushPermissionState.denied,
      AuthorizationStatus.deniedPermanently =>
        PushPermissionState.systemSettingsRequired,
    };
    // Permission acceptance is not yet the durable application consent. The
    // lifecycle stores that consent first, then enables auto-init and fetches
    // the token only after backend registration is authorized.
    if (state != PushPermissionState.granted) {
      await messaging.setAutoInitEnabled(false);
    }
    return state;
  }

  @override
  Future<String?> token() async => (await _activate()).getToken();
  @override
  Stream<String> get tokenRefreshes => _tokenEvents.stream;
  @override
  Stream<void> get foregroundMessages => _foregroundEvents.stream;
  @override
  Stream<PushNavigationTarget?> get openedMessages => _openedEvents.stream;
  @override
  Future<PushNavigationTarget?> initialTap() async {
    final nativeTap = await _settings.invokeMethod<Map<dynamic, dynamic>>(
      'consumePushTap',
    );
    if (nativeTap != null) {
      return PushNavigationTarget.parse(Map<String, dynamic>.from(nativeTap));
    }
    final message = await (await _activate()).getInitialMessage();
    return message == null ? null : PushNavigationTarget.parse(message.data);
  }

  @override
  Future<void> deactivate() async {
    final messaging = _messaging;
    if (messaging == null) return;
    await messaging.setAutoInitEnabled(false);
    await messaging.deleteToken();
  }

  @override
  Future<void> openNotificationSettings() =>
      _settings.invokeMethod<void>('openNotificationSettings');
  Future<void> dispose() async {
    await _tokenSubscription?.cancel();
    await _foregroundSubscription?.cancel();
    await _openedSubscription?.cancel();
    await _tokenEvents.close();
    await _foregroundEvents.close();
    await _openedEvents.close();
  }
}
