import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../core/theme/app_theme.dart';
import 'router/app_router.dart';

class SimertpiApp extends StatelessWidget {
  const SimertpiApp({super.key, this.initialRoute});
  final String? initialRoute;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'SIMERTPI',
    debugShowCheckedModeBanner: false,
    locale: const Locale('es'),
    supportedLocales: const [Locale('es')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: AppTheme.light,
    themeMode: ThemeMode.light,
    initialRoute: initialRoute ?? AppRoute.splash.path,
    onGenerateRoute: AppRouter.generate,
    onGenerateInitialRoutes: (route) => [
      AppRouter.generate(RouteSettings(name: route)),
    ],
  );
}
