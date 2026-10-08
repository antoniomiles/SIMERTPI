import 'dart:async';

import '../citizen_activity/activity_pages.dart';
import '../citizen_activity/profile_page.dart';
import '../citizen_activity/activity_service.dart';
import '../../core/widgets/citizen_navigation.dart';
import '../../core/widgets/operational_ui.dart';
import '../../core/config/app_config.dart';
import '../../core/network/api_client.dart';
import '../discovery/presentation/discovery_page.dart';
import '../active_parking/data/active_parking_service.dart';
import '../active_parking/state/active_parking_controller.dart';
import '../active_parking/presentation/active_parking_panel.dart';
import '../payments/data/payment_contract.dart';
import '../discovery/data/parking_catalog.dart';

import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/theme/app_tokens.dart';
import '../../core/theme/app_typography.dart';
import '../vehicles/data/vehicle_service.dart';
import '../vehicles/state/vehicles_controller.dart';

class HomePage extends StatefulWidget {
  const HomePage({
    super.key,
    this.controller,
    this.activeController,
    this.initialTab = 0,
  });
  final int initialTab;
  final VehiclesController? controller;
  final ActiveParkingController? activeController;
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  VehiclesController? _vehicles;
  ActiveParkingController? _active;
  int tab = 0, mapRefresh = 0;
  int unreadCount = 0;
  int inboxRefresh = 0;
  ActivityService? activity;
  Future<void> refreshUnread() async {
    final scope = AppScope.of(context);
    final owner = scope.auth.userId;
    try {
      final value = await activity?.unread();
      if (mounted &&
          value != null &&
          scope.auth.isAuthenticated &&
          owner == scope.auth.userId) {
        setState(() => unreadCount = value);
        AppScope.of(context).unreadNotifications?.value = value;
      }
    } catch (_) {}
  }

  bool mapOpened = false;
  bool _initialTabApplied = false;
  String? greeting;
  ValueNotifier<int>? _parkingConfirmed, _citizenTab;
  ValueNotifier<String?>? _parkingSessionTarget;
  void _parkingTargetChanged() {
    final target = _parkingSessionTarget?.value;
    if (target == null || !mounted) return;
    _parkingSessionTarget!.value = null;
    _active?.prioritizeSession(target);
    _tab(0);
    unawaited(_resolveParkingTarget(target));
  }

  Future<void> _resolveParkingTarget(String target) async {
    final active = _active;
    if (active == null) return;
    await active.load();
    for (var i = 0; i < 300 && active.loading && mounted; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
    if (!mounted || !AppScope.of(context).auth.isAuthenticated) return;
    AppScope.of(context).pushLifecycle?.completeParkingTarget(target);
    if (!active.sessions.any((session) => session.id == target)) _tab(2);
  }

  void _requestedTab() {
    if (mounted && _citizenTab!.value != tab) _tab(_citizenTab!.value);
  }

  void _confirmed() {
    refreshUnread();
    if (!mounted) return;
    setState(() {
      tab = 0;
      mapRefresh++;
    });
    _citizenTab?.value = 0;
    _active?.load();
    _vehicles?.load();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    tab = widget.initialTab;
    mapOpened = tab == 1;
  }

  void _tab(int value) {
    refreshUnread();
    setState(() {
      tab = value;
      if (value == 2) inboxRefresh++;
      if (value == 1) {
        mapOpened = true;
        mapRefresh++;
      }
    });
    if (_citizenTab?.value != value) _citizenTab?.value = value;
    if (value == 0) {
      _active?.load();
      _vehicles?.load();
    }
  }

  Future<void> _launch(AppRoute route) async {
    await Navigator.pushNamed(context, route.path);
    if (mounted) {
      refreshUnread();
      _active?.load();
      _vehicles?.load();
      setState(() => mapRefresh++);
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_active != null) return;
    final scope = AppScope.of(context);
    if (!_initialTabApplied &&
        widget.initialTab == 0 &&
        scope.citizenTab?.value == 2) {
      tab = 2;
    }
    _initialTabApplied = true;
    activity = ActivityService(scope.api);
    refreshUnread();
    _citizenTab = scope.citizenTab;
    _citizenTab?.value = tab;
    _citizenTab?.addListener(_requestedTab);
    _parkingSessionTarget = scope.parkingSessionTarget;
    _parkingSessionTarget?.addListener(_parkingTargetChanged);
    if (_parkingSessionTarget?.value != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _parkingTargetChanged();
      });
    }
    _parkingConfirmed = scope.parkingConfirmed;
    _parkingConfirmed?.addListener(_confirmed);
    // A vehicles controller is only needed when injected by a host/test.
    // The dedicated vehicles route owns its existing controller.
    _vehicles = widget.controller;
    _vehicles?.load();
    final owner = scope.auth.userId ?? '';
    _active =
        widget.activeController ??
        ActiveParkingController(
          owner: owner,
          gateway: ActiveParkingService(scope.api),
          payments: PaymentService(scope.api),
          store: SecureExtensionStore(
            '${scope.config.environment.name}:${scope.config.apiBaseUrl}:$owner',
          ),
          catalog: ParkingCatalogService(scope.api),
          vehicles: VehicleService(scope.api, owner),
          dev: scope.config.environment == AppEnvironment.dev,
        );
    _active!.load();
    scope.api
        ?.request(ApiMethod.get, 'users/mine')
        .then((r) {
          if (mounted &&
              r.body is Map &&
              (r.body as Map)['username'] is String) {
            setState(
              () => greeting =
                  ((r.body as Map)['firstName'] as String?)
                          ?.trim()
                          .isNotEmpty ==
                      true
                  ? (r.body as Map)['firstName'] as String
                  : (r.body as Map)['username'] as String,
            );
          }
        })
        .catchError((Object _) {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refreshUnread();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _parkingConfirmed?.removeListener(_confirmed);
    _citizenTab?.removeListener(_requestedTab);
    _parkingSessionTarget?.removeListener(_parkingTargetChanged);
    if (widget.controller == null) _vehicles?.dispose();
    if (widget.activeController == null) _active?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: tab == 0,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop && tab != 0) _tab(0);
    },
    child: Scaffold(
      appBar: tab == 0
          ? AppBar(
              title: const Text('SIMERTPI', style: AppTypography.brand),
              backgroundColor: AppColors.primary,
              foregroundColor: Colors.white,
              actions: [
                IconButton(
                  tooltip: 'Cerrar sesión',
                  icon: const Icon(Icons.logout),
                  onPressed: () => AppScope.of(context).auth.logout(),
                ),
              ],
            )
          : null,
      body: IndexedStack(
        index: tab,
        children: [
          SafeArea(
            child: SingleChildScrollView(
              padding: AppSpace.page,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    greeting == null ? '¡Hola!' : '¡Hola, $greeting!',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: AppSpace.sm),
                  Text(
                    '¿Qué deseas hacer?',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: AppSpace.lg),
                  ActiveParkingPanel(controller: _active!),
                  OperationalCard(
                    color: AppColors.action,
                    border: AppColors.action,
                    onTap: () => _launch(AppRoute.startParking),
                    child: Row(
                      children: [
                        const Icon(
                          Icons.local_parking,
                          size: 34,
                          color: Colors.white,
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'ESTACIONAR',
                                style: Theme.of(context).textTheme.titleMedium
                                    ?.copyWith(color: Colors.white),
                              ),
                              const Text(
                                'Buscar espacio o escanear QR',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const Icon(Icons.chevron_right, color: Colors.white),
                      ],
                    ),
                  ),
                  LayoutBuilder(
                    builder: (context, box) {
                      final width =
                          MediaQuery.textScalerOf(context).scale(14) > 21
                          ? box.maxWidth
                          : (box.maxWidth - AppSpace.sm) / 2;
                      return Wrap(
                        spacing: AppSpace.sm,
                        runSpacing: AppSpace.sm,
                        children: [
                          SizedBox(
                            width: width,
                            child: OperationalCard(
                              onTap: () => _launch(AppRoute.vehicles),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.directions_car_outlined,
                                    color: AppColors.action,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      'Mis vehículos',
                                      style: Theme.of(context)
                                          .textTheme
                                          .labelMedium,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          SizedBox(
                            width: width,
                            child: OperationalCard(
                              onTap: () => _launch(AppRoute.history),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.history,
                                    color: AppColors.muted,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [Text('Historial')],
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          SizedBox(
                            width: width,
                            child: OperationalCard(
                              onTap: () => _tab(1),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.location_on_outlined,
                                    color: AppColors.action,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      'Mapa',
                                      style: Theme.of(context)
                                          .textTheme
                                          .labelMedium,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          SizedBox(
                            width: width,
                            child: OperationalCard(
                              onTap: () => _tab(2),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.notifications_none,
                                    color: AppColors.action,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      'Notificaciones',
                                      style: Theme.of(context)
                                          .textTheme
                                          .labelMedium,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                  const SizedBox(height: AppSpace.md),
                  TextButton(
                    onPressed: () => _launch(AppRoute.payments),
                    child: const Text('Consultar pago pendiente'),
                  ),
                ],
              ),
            ),
          ),
          mapOpened
              ? DiscoveryPage(
                  initialMap: true,
                  embedded: true,
                  refreshToken: mapRefresh,
                )
              : const SizedBox(),
          ActivityListPage(
            resource: 'inbox',
            refreshToken: inboxRefresh,
            gateway: activity,
            onRead: refreshUnread,
          ),
          CitizenProfilePage(gateway: activity, onNotifications: () => _tab(2)),
        ],
      ),
      bottomNavigationBar: CitizenNavigation(selected: tab, onSelected: _tab),
    ),
  );
}
