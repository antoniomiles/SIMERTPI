import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';

class CitizenNavigation extends StatelessWidget {
  const CitizenNavigation({
    super.key,
    this.selected = 0,
    this.onSelected,
    this.unreadCount,
  });
  final int selected;
  final int? unreadCount;
  final ValueChanged<int>? onSelected;
  @override
  Widget build(BuildContext context) {
    final counter = context
        .dependOnInheritedWidgetOfExactType<AppScope>()
        ?.unreadNotifications;
    if (unreadCount != null || counter == null) {
      return bar(context, unreadCount ?? 0);
    }
    return ValueListenableBuilder<int>(
      valueListenable: counter,
      builder: (context, count, _) => bar(context, count),
    );
  }

  Widget bar(BuildContext context, int unreadCount) => NavigationBar(
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
    destinations: [
      const NavigationDestination(
        icon: Icon(Icons.home_outlined),
        selectedIcon: Icon(Icons.home),
        label: 'Inicio',
      ),
      const NavigationDestination(
        icon: Icon(Icons.map_outlined),
        selectedIcon: Icon(Icons.map),
        label: 'Mapa',
      ),
      NavigationDestination(
        icon: Badge(
          isLabelVisible: unreadCount > 0,
          label: Text(unreadCount > 99 ? '99+' : '$unreadCount'),
          child: const Icon(Icons.notifications_outlined),
        ),
        label: 'Notificaciones',
      ),
      const NavigationDestination(
        icon: Icon(Icons.person_outline),
        label: 'Perfil',
      ),
    ],
  );
}
