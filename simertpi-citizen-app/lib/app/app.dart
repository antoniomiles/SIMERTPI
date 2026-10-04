import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../core/theme/app_theme.dart';
import '../features/auth/state/auth_controller.dart';
import 'router/app_router.dart';

class SimertpiApp extends StatefulWidget {
  const SimertpiApp({super.key, required this.auth, this.initialRoute});
  final AuthController auth;
  final String? initialRoute;
  @override
  State<SimertpiApp> createState() => _SimertpiAppState();
}

class _SimertpiAppState extends State<SimertpiApp> {
  final _navigator = GlobalKey<NavigatorState>();
  late bool _hadSession;
  @override
  void initState() {
    super.initState();
    _hadSession = widget.auth.isAuthenticated;
    widget.auth.addListener(_authChanged);
  }

  void _authChanged() {
    final signedOut = _hadSession && !widget.auth.isAuthenticated;
    _hadSession = widget.auth.isAuthenticated;
    if (signedOut) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && !widget.auth.isAuthenticated) {
          _navigator.currentState?.pushNamedAndRemoveUntil(
            AppRoute.login.path,
            (_) => false,
          );
        }
      });
    }
  }

  @override
  void dispose() {
    widget.auth.removeListener(_authChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: _navigator,
    title: 'SIMERTPI',
    debugShowCheckedModeBanner: false,
    locale: const Locale('es'),
    supportedLocales: const [Locale('es')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: AppTheme.light,
    themeMode: ThemeMode.light,
    initialRoute: widget.initialRoute ?? AppRoute.splash.path,
    onGenerateRoute: (settings) => AppRouter.generate(settings, widget.auth),
    onGenerateInitialRoutes: (route) => [
      AppRouter.generate(RouteSettings(name: route), widget.auth),
    ],
  );
}
