import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/theme/app_tokens.dart';
import '../../core/theme/app_typography.dart';
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/app_qr_marker.dart';
import '../../core/widgets/app_feedback.dart';
import '../vehicles/data/vehicle_service.dart';
import '../vehicles/state/vehicles_controller.dart';
import '../vehicles/presentation/vehicle_list.dart';
import '../vehicles/presentation/vehicle_form_page.dart';

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
  Future<void> _addVehicle() async {
    if (_openingVehicles || _vehicles!.processing) return;
    _openingVehicles = true;
    try {
      final saved = await Navigator.push<bool>(
        context,
        MaterialPageRoute(
          builder: (_) => VehicleFormPage(controller: _vehicles!),
        ),
      );
      if (mounted && saved == true) {
        await _vehicles!.load();
        if (mounted) AppSnackbar.show(context, 'Vehículo registrado.');
      }
    } finally {
      _openingVehicles = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('SIMERTPI', style: AppTypography.brand),
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
                      const SizedBox(width: AppSpace.xl),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Escanear código QR',
                              style: AppTypography.section.copyWith(
                                color: AppColors.primary,
                              ),
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
                PrimaryButton(
                  label: 'ESCANEAR QR',
                  onPressed: () =>
                      Navigator.pushNamed(context, AppRoute.qr.path),
                ),
                const SizedBox(height: AppSpace.md),
                SecondaryButton(
                  label: 'BUSCAR ESTACIONAMIENTO',
                  filled: true,
                  onPressed: () =>
                      Navigator.pushNamed(context, AppRoute.discovery.path),
                ),
                const SizedBox(height: AppSpace.xl),
                VehicleList(controller: _vehicles!, onAdd: _addVehicle),
                const SizedBox(height: AppSpace.xl),
                SecondaryButton(
                  label: 'CONSULTAR PAGO PENDIENTE',
                  onPressed: () =>
                      Navigator.pushNamed(context, AppRoute.payments.path),
                ),
                const SizedBox(height: AppSpace.md),
                TextButton(
                  style: TextButton.styleFrom(padding: EdgeInsets.zero),
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
