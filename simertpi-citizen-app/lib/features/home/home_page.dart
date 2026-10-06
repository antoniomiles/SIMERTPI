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
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/app_feedback.dart';
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

class _HomePageState extends State<HomePage> {
  VehiclesController? _vehicles;
  ActiveParkingController? _active;
  int tab = 0, mapRefresh = 0;
  bool mapOpened = false;
  String? greeting;
  ValueNotifier<int>? _parkingConfirmed;

  void _confirmed() {
    if (!mounted) return;
    setState(() {
      tab = 0;
      mapRefresh++;
    });
    _active?.load();
    _vehicles?.load();
  }

  @override
  void initState() {
    super.initState();
    tab = widget.initialTab;
    mapOpened = tab == 1;
  }

  void _tab(int value) {
    setState(() {
      tab = value;
      if (value == 1) {
        mapOpened = true;
        mapRefresh++;
      }
    });
    if (value == 0) {
      _active?.load();
      _vehicles?.load();
    }
  }

  Future<void> _launch(AppRoute route) async {
    await Navigator.pushNamed(context, route.path);
    if (mounted) {
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
            setState(() => greeting = (r.body as Map)['username'] as String);
          }
        })
        .catchError((Object _) {});
  }

  @override
  void dispose() {
    _parkingConfirmed?.removeListener(_confirmed);
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
                  PrimaryButton(
                    label: 'ESTACIONAR',
                    onPressed: () => _launch(AppRoute.startParking),
                  ),
                  const SizedBox(height: AppSpace.sm),
                  const Text('Buscar espacio o escanear QR'),
                  const SizedBox(height: AppSpace.lg),
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
                            child: SecondaryButton(
                              label: 'Mis vehículos',
                              onPressed: () => _launch(AppRoute.vehicles),
                            ),
                          ),
                          SizedBox(
                            width: width,
                            child: SecondaryButton(
                              label: 'Historial',
                              onPressed: () => AppSnackbar.show(
                                context,
                                'Historial estará disponible próximamente.',
                              ),
                            ),
                          ),
                          SizedBox(
                            width: width,
                            child: SecondaryButton(
                              label: 'Mapa',
                              onPressed: () => _tab(1),
                            ),
                          ),
                          SizedBox(
                            width: width,
                            child: SecondaryButton(
                              label: 'Notificaciones',
                              onPressed: () => _tab(2),
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
              ? DiscoveryPage(initialMap: true, refreshToken: mapRefresh)
              : const SizedBox(),
          const Scaffold(
            body: SafeArea(
              child: Center(
                child: Padding(
                  padding: AppSpace.page,
                  child: Text('Notificaciones estará disponible próximamente.'),
                ),
              ),
            ),
          ),
          const Scaffold(
            body: SafeArea(
              child: Center(
                child: Padding(
                  padding: AppSpace.page,
                  child: Text('Perfil estará disponible próximamente.'),
                ),
              ),
            ),
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: _tab,
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
          NavigationDestination(
            icon: Icon(Icons.person_outline),
            label: 'Perfil',
          ),
        ],
      ),
    ),
  );
}
