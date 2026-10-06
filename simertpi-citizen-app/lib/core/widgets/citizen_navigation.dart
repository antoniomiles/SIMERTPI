import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';

class CitizenNavigation extends StatelessWidget {
  const CitizenNavigation({super.key, this.selected = 0, this.onSelected});
  final int selected;
  final ValueChanged<int>? onSelected;
  @override
  Widget build(BuildContext context) => NavigationBar(
    selectedIndex: selected,
    onDestinationSelected:
        onSelected ??
        (value) {
          final scope = AppScope.of(context);
          scope.citizenTab?.value = value;
          Navigator.of(context).popUntil(
            (route) =>
                route.settings.name == AppRoute.home.path || route.isFirst,
          );
        },
    destinations: const [
      NavigationDestination(
        icon: Icon(Icons.home_outlined),
        selectedIcon: Icon(Icons.home),
        label: 'Inicio',
      ),
      NavigationDestination(
        icon: Icon(Icons.map_outlined),
        selectedIcon: Icon(Icons.map),
        label: 'Mapa',
      ),
      NavigationDestination(
        icon: Icon(Icons.notifications_outlined),
        label: 'Notificaciones',
      ),
      NavigationDestination(icon: Icon(Icons.person_outline), label: 'Perfil'),
    ],
  );
}
