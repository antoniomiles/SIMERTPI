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

class AppScope extends InheritedWidget {
  const AppScope({
    super.key,
    required this.config,
    this.api,
    this.parkingConfirmed,
    required this.auth,
    required super.child,
  });
  final AppConfig config;
  final ApiClient? api;
  final ValueNotifier<int>? parkingConfirmed;
  final AuthController auth;
  static AppScope of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AppScope>()!;
  @override
  bool updateShouldNotify(AppScope oldWidget) =>
      config != oldWidget.config ||
      api != oldWidget.api ||
      auth != oldWidget.auth;
}

class Bootstrap extends StatefulWidget {
  const Bootstrap({super.key});
  @override
  State<Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<Bootstrap> {
  ApiClient? _api;
  AppConfig? _config;
  AuthController? _auth;
  final _parkingConfirmed = ValueNotifier<int>(0);
  @override
  void initState() {
    super.initState();
    _configure();
  }

  void _configure() {
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
          onSessionRejected: () => _auth!.logout(expired: true, remote: false),
          onUnauthorized: (authorization) =>
              _auth!.recoverUnauthorized(authorization),
        );
      }
      _auth = AuthController(
        AuthService(_api),
        store: SecureSessionStore(
          '${_config!.environment.name}:${_config!.apiBaseUrl}',
        ),
      );
    } on FormatException {
      /* Fail closed; never render raw configuration. */
    }
  }

  @override
  void dispose() {
    _api?.close();
    _auth?.dispose();
    _parkingConfirmed.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _config != null
      ? AppScope(
          config: _config!,
          api: _api,
          parkingConfirmed: _parkingConfirmed,
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
