import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/config/app_config.dart';
import '../../core/network/api_client.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/app_layout.dart';
import '../app.dart';
import '../../features/auth/data/auth_service.dart';
import '../../features/auth/state/auth_controller.dart';
import '../../features/auth/data/session_store.dart';
import '../../core/push/push_lifecycle.dart';

class AppScope extends InheritedWidget {
  const AppScope({
    super.key,
    required this.config,
    this.api,
    this.parkingConfirmed,
    this.citizenTab,
    this.parkingSessionTarget,
    this.unreadNotifications,
    this.pushLifecycle,
    required this.auth,
    required super.child,
  });
  final AppConfig config;
  final ApiClient? api;
  final ValueNotifier<int>? parkingConfirmed;
  final ValueNotifier<int>? citizenTab;
  final ValueNotifier<String?>? parkingSessionTarget;
  final ValueNotifier<int>? unreadNotifications;
  final PushDeviceLifecycle? pushLifecycle;
  final AuthController auth;
  static AppScope of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AppScope>()!;
  @override
  bool updateShouldNotify(AppScope oldWidget) =>
      config != oldWidget.config ||
      api != oldWidget.api ||
      pushLifecycle != oldWidget.pushLifecycle ||
      auth != oldWidget.auth;
}

class Bootstrap extends StatefulWidget {
  const Bootstrap({super.key, this.pushRuntime});
  final PushRuntime? pushRuntime;
  @override
  State<Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<Bootstrap> with WidgetsBindingObserver {
  ApiClient? _api;
  AppConfig? _config;
  AuthController? _auth;
  PushDeviceLifecycle? _pushLifecycle;
  bool _wasAuthenticated = false;
  final _citizenTab = ValueNotifier<int>(0);
  final _parkingSessionTarget = ValueNotifier<String?>(null);
  final _unreadNotifications = ValueNotifier<int>(0);
  final _parkingConfirmed = ValueNotifier<int>(0);
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _configure();
  }

  void _authChanged() {
    final authenticated = _auth?.isAuthenticated ?? false;
    if (!authenticated) {
      _unreadNotifications.value = 0;
    }
    if (authenticated && !_wasAuthenticated) {
      unawaited(_pushLifecycle?.onAuthenticated());
    }
    _wasAuthenticated = authenticated;
  }

  void _configure() {
    _pushLifecycle?.dispose();
    _pushLifecycle = null;
    _api?.close();
    _auth?.dispose();
    _auth = null;
    _api = null;
    _config = null;
    try {
      _config = AppConfig.fromDefines();
      if (_config!.apiBaseUrl != null) {
        _api = ApiClient(
          baseUrl: _config!.apiBaseUrl!,
          headersProvider: () => _auth!.headers(),
          onSessionRejected: () => _auth!.isLoggingOut
              ? Future<void>.value()
              : _auth!.logout(expired: true, remote: false),
          onUnauthorized: (authorization) => _auth!.isLoggingOut
              ? Future<bool>.value(false)
              : _auth!.recoverUnauthorized(authorization),
        );
      }
      _auth = AuthController(
        AuthService(_api),
        store: SecureSessionStore(
          '${_config!.environment.name}:${_config!.apiBaseUrl}',
        ),
        onBeforeLogout: ({required remote, required ownerId}) async =>
            _pushLifecycle?.logout(remote: remote, ownerId: ownerId),
      );
      _auth!.addListener(_authChanged);
      _wasAuthenticated = _auth!.isAuthenticated;
      if (_api != null && widget.pushRuntime != null) {
        _pushLifecycle = PushDeviceLifecycle(
          runtime: widget.pushRuntime!,
          api: NotificationDeviceApi(_api!),
          store: SecurePushDeviceStore(
            '${_config!.environment.name}:${_config!.apiBaseUrl}',
          ),
          isAuthenticated: () => _auth?.isAuthenticated ?? false,
          ownerId: () => _auth?.userId,
          unreadNotifications: _unreadNotifications,
          citizenTab: _citizenTab,
          parkingSessionTarget: _parkingSessionTarget,
        )..start();
        if (_wasAuthenticated) {
          unawaited(_pushLifecycle!.onAuthenticated());
        }
      }
    } on FormatException {
      /* Fail closed; never render raw configuration. */
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_pushLifecycle?.onResumed());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pushLifecycle?.dispose();
    _api?.close();
    _auth?.dispose();
    _parkingConfirmed.dispose();
    _citizenTab.dispose();
    _parkingSessionTarget.dispose();
    _unreadNotifications.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _config != null
      ? AppScope(
          config: _config!,
          api: _api,
          parkingConfirmed: _parkingConfirmed,
          citizenTab: _citizenTab,
          parkingSessionTarget: _parkingSessionTarget,
          unreadNotifications: _unreadNotifications,
          pushLifecycle: _pushLifecycle,
          auth: _auth!,
          child: SimertpiApp(auth: _auth!),
        )
      : MaterialApp(
          theme: AppTheme.light,
          home: AppPage(
            title: 'SIMERTPI',
            child: ErrorState(
              message:
                  'No fue posible iniciar la aplicación. Contacta con soporte.',
              onRetry: () => setState(_configure),
            ),
          ),
        );
}
