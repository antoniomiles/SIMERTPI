import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/theme/app_tokens.dart';
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/app_qr_marker.dart';
import '../../core/widgets/app_feedback.dart';
import '../vehicles/data/vehicle_service.dart';
import '../vehicles/state/vehicles_controller.dart';
import '../vehicles/presentation/vehicle_list.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key, this.controller});
  final VehiclesController? controller;
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  VehiclesController? _vehicles;
  bool _openingVehicles = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_vehicles != null) return;
    final scope = AppScope.of(context);
    _vehicles =
        widget.controller ??
        VehiclesController(VehicleService(scope.api, scope.auth.userId));
    _vehicles!.load();
  }

  @override
  void dispose() {
    if (widget.controller == null) _vehicles?.dispose();
    super.dispose();
  }

  void _future() =>
      AppSnackbar.show(context, 'Esta función todavía no está disponible.');
  Future<void> _openVehicles() async {
    if (_openingVehicles) return;
    _openingVehicles = true;
    try {
      await Navigator.pushNamed(context, AppRoute.vehicles.path);
      if (mounted) await _vehicles!.load();
    } finally {
      _openingVehicles = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('SIMERTPI'),
      titleSpacing: AppSpace.lg,
      backgroundColor: AppColors.primary,
      foregroundColor: Colors.white,
      toolbarHeight: AppSize.brandHeader,
      actions: [
        IconButton(
          tooltip: 'Cerrar sesión',
          icon: const Icon(Icons.logout),
          onPressed: () => AppScope.of(context).auth.logout(),
        ),
      ],
    ),
    body: SafeArea(
      top: false,
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: AppSize.contentWidth),
          child: SingleChildScrollView(
            padding: AppSpace.page,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '¿Dónde vas a estacionar?',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: AppSpace.sm),
                const Text('Escanea el QR del espacio o búscalo manualmente.'),
                const SizedBox(height: AppSpace.lg),
                Container(
                  constraints: const BoxConstraints(
                    minHeight: AppSize.qrCardMinHeight,
                  ),
                  padding: const EdgeInsets.all(AppSpace.lg),
                  decoration: BoxDecoration(
                    color: AppColors.parkingHint,
                    borderRadius: BorderRadius.circular(AppSize.radius),
                  ),
                  child: Row(
                    children: [
                      const AppQrMarker(),
                      const SizedBox(width: AppSpace.md),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Escanear código QR',
                              style: Theme.of(context).textTheme.titleMedium
                                  ?.copyWith(color: AppColors.primary),
                            ),
                            const SizedBox(height: AppSpace.sm),
                            const Text('Identifica zona y espacio'),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpace.lg),
                PrimaryButton(label: 'ESCANEAR QR', onPressed: _future),
                const SizedBox(height: AppSpace.md),
                SecondaryButton(
                  label: 'INGRESAR ESPACIO MANUALMENTE',
                  filled: true,
                  onPressed: _future,
                ),
                const SizedBox(height: AppSpace.xl),
                TextButton(
                  onPressed: _openVehicles,
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Mis vehículos',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                ),
                const SizedBox(height: AppSpace.md),
                VehicleList(
                  controller: _vehicles!,
                  onAdd: _openVehicles,
                  limit: 3,
                ),
                const SizedBox(height: AppSpace.xl),
                TextButton(
                  onPressed: _future,
                  child: const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Historial de estacionamientos'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
