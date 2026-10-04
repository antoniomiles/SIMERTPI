import 'package:flutter/material.dart';

import '../../core/config/app_config.dart';
import '../../core/network/api_client.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/app_layout.dart';
import '../app.dart';

class AppScope extends InheritedWidget {
  const AppScope({
    super.key,
    required this.config,
    this.api,
    required super.child,
  });
  final AppConfig config;
  final ApiClient? api;
  static AppScope of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AppScope>()!;
  @override
  bool updateShouldNotify(AppScope oldWidget) =>
      config != oldWidget.config || api != oldWidget.api;
}

class Bootstrap extends StatefulWidget {
  const Bootstrap({super.key});
  @override
  State<Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<Bootstrap> {
  ApiClient? _api;
  AppConfig? _config;
  @override
  void initState() {
    super.initState();
    _configure();
  }

  void _configure() {
    _api?.close();
    _api = null;
    _config = null;
    try {
      _config = AppConfig.fromDefines();
      if (_config!.apiBaseUrl != null) {
        _api = ApiClient(baseUrl: _config!.apiBaseUrl!);
      }
    } on FormatException {
      /* Fail closed; never render raw configuration. */
    }
  }

  @override
  void dispose() {
    _api?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _config != null
      ? AppScope(config: _config!, api: _api, child: const SimertpiApp())
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
