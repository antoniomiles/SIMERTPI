import 'package:flutter/material.dart';

import '../../features/splash/splash_page.dart';
import '../../features/showcase/home_page.dart';
import '../../features/showcase/components_page.dart';
import '../../features/showcase/states_page.dart';

enum AppRoute {
  splash('/'),
  home('/home'),
  components('/components'),
  states('/states');

  const AppRoute(this.path);
  final String path;
}

abstract final class AppRouter {
  // Add public/protected route policy here in CP18, after session contract exists.
  static Route<void> generate(RouteSettings settings) {
    final routes = <String, WidgetBuilder>{
      AppRoute.splash.path: (_) => const SplashPage(),
      AppRoute.home.path: (_) => const HomePage(),
      AppRoute.components.path: (_) => const ComponentsPage(),
      AppRoute.states.path: (_) => const StatesPage(),
    };
    final page = routes[settings.name]?.call;
    return MaterialPageRoute<void>(
      settings: settings,
      builder: (context) => page?.call(context) ?? const HomePage(),
    );
  }
}
