import 'package:flutter/material.dart';

import '../../features/auth/state/auth_controller.dart';
import '../../features/discovery/data/parking_catalog.dart';
import '../../features/discovery/presentation/discovery_page.dart';
import '../../features/discovery/presentation/qr_scan_page.dart';
import '../../features/discovery/presentation/space_selection_page.dart';
import '../../features/auth/presentation/login_page.dart';
import '../../features/auth/presentation/register_page.dart';
import '../../features/splash/splash_page.dart';
import '../../features/showcase/home_page.dart' as showcase;
import '../../features/home/home_page.dart';
import '../../features/vehicles/presentation/vehicles_page.dart';
import '../../features/showcase/components_page.dart';
import '../../features/showcase/states_page.dart';

enum AppRoute {
  splash('/'),
  login('/login'),
  register('/register'),
  home('/home'),
  vehicles('/vehicles'),
  discovery('/discovery'),
  qr('/qr'),
  space('/space'),
  showcase('/showcase'),
  components('/components'),
  states('/states');

  const AppRoute(this.path);
  final String path;
  bool get isPublic => this == splash || this == login || this == register;
}

abstract final class AppRouter {
  static Route<void> generate(RouteSettings settings, AuthController auth) {
    final selected =
        AppRoute.values.where((r) => r.path == settings.name).firstOrNull ??
        AppRoute.home;
    return MaterialPageRoute<void>(
      settings: settings,
      builder: (context) => ListenableBuilder(
        listenable: auth,
        builder: (context, _) {
          if (!selected.isPublic && !auth.isAuthenticated) {
            return const LoginPage();
          }
          if ((selected == AppRoute.login || selected == AppRoute.register) &&
              auth.isAuthenticated) {
            return const HomePage();
          }
          return switch (selected) {
            AppRoute.splash => const SplashPage(),
            AppRoute.login => const LoginPage(),
            AppRoute.register => const RegisterPage(),
            AppRoute.home => const HomePage(),
            AppRoute.vehicles => const VehiclesPage(),
            AppRoute.discovery => const DiscoveryPage(),
            AppRoute.qr => const QrScanPage(),
            AppRoute.space =>
              settings.arguments is IdentifiedSpace
                  ? SpaceSelectionPage(
                      result: settings.arguments! as IdentifiedSpace,
                    )
                  : const DiscoveryPage(),
            AppRoute.showcase => const showcase.HomePage(),
            AppRoute.components => const ComponentsPage(),
            AppRoute.states => const StatesPage(),
          };
        },
      ),
    );
  }
}
