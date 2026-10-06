import '../../vehicles/presentation/vehicle_plate.dart';
import '../../../app/router/app_router.dart';

import 'package:flutter/material.dart';

import '../../../core/widgets/operational_ui.dart';
import 'duration_options.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/widgets/app_skeleton.dart';
import '../../discovery/data/parking_catalog.dart';
import '../../vehicles/data/vehicle_service.dart';
import '../../vehicles/state/vehicles_controller.dart';
import '../../vehicles/presentation/vehicle_form_page.dart';
import '../data/parking_contract.dart';
import '../data/parking_intent_store.dart';
import '../state/parking_controller.dart';

class ParkingPage extends StatefulWidget {
  const ParkingPage({super.key, required this.selected, this.controller});
  final IdentifiedSpace selected;
  final ParkingController? controller;
  @override
  State<ParkingPage> createState() => _ParkingPageState();
}

class _ParkingPageState extends State<ParkingPage> {
  ParkingController? _controller;
  int step = 0;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_controller != null) return;
    final scope = AppScope.of(context);
    final owner = scope.auth.userId ?? '';
    _controller =
        widget.controller ??
        ParkingController(
          space: widget.selected,
          owner: owner,
          catalog: ParkingCatalogService(scope.api),
          vehicles: VehicleService(scope.api, owner),
          parking: ParkingService(scope.api),
          store: SecureParkingIntentStore(
            '${scope.config.environment.name}:${scope.config.apiBaseUrl}:$owner:${widget.selected.space.id}',
          ),
        );
    _load();
  }

  Future<void> _load() async {
    await _controller!.load();
    if (!mounted) return;
    if (_controller!.phase == ParkingPhase.uncertain) {
      await _controller!.recover();
    }
    if (mounted) setState(() => step = 0);
  }

  Future<void> _register() async {
    final c = _controller!;
    final vehicles = VehiclesController(c.vehicles);
    try {
      await Navigator.of(context).push(
        MaterialPageRoute<bool>(
          builder: (_) => VehicleFormPage(controller: vehicles),
        ),
      );
      if (mounted) await _load();
    } finally {
      vehicles.dispose();
    }
  }

  Future<void> _submit() async {
    await _controller!.create();
    if (mounted && _controller!.phase == ParkingPhase.created) {
      await Navigator.pushNamed(
        context,
        AppRoute.payments.path,
        arguments: _controller!.receipt!.id,
      );
    }
    if (mounted &&
        _controller!.phase != ParkingPhase.created &&
        _controller!.phase != ParkingPhase.uncertain) {
      setState(() => step = 0);
    }
  }

  @override
  void dispose() {
    if (widget.controller == null) _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _controller!,
    builder: (context, _) {
      final c = _controller!, q = c.quote;
      return PopScope(
        canPop:
            !c.busy &&
            (step == 0 ||
                c.phase == ParkingPhase.created ||
                c.phase == ParkingPhase.uncertain),
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop && !c.busy && step > 0) setState(() => step--);
        },
        child: AppPage(
          showNavigation: !c.busy,
          title: step == 0
              ? 'Selecciona un vehículo'
              : step == 1
              ? 'Tiempo de estacionamiento'
              : 'Resumen del estacionamiento',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                '${c.space.space.code} · ${c.space.catalog.streetOf(c.space.space)?.name ?? ""}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 12),
              if (c.phase == ParkingPhase.loading ||
                  c.phase == ParkingPhase.initial)
                const SkeletonList(count: 2)
              else if (c.phase == ParkingPhase.created) ...[
                const Text('Solicitud pendiente de pago'),
                const Text(
                  'Se activará únicamente cuando el sistema confirme el pago aprobado.',
                ),
                PrimaryButton(
                  label: 'Continuar al pago',
                  onPressed: () => Navigator.pushNamed(
                    context,
                    AppRoute.payments.path,
                    arguments: c.receipt!.id,
                  ),
                ),
              ] else if (c.phase == ParkingPhase.uncertain)
                ErrorState(
                  message:
                      c.message ?? 'Comprueba la solicitud antes de continuar.',
                  onRetry: c.recover,
                )
              else ...[
                if (c.message != null)
                  ErrorState(message: c.message!, onRetry: _load),
                if (c.busy) const LinearProgressIndicator(),
                if (step == 0) ...[
                  if (c.items.isEmpty)
                    EmptyState(
                      message: 'No tienes vehículos habilitados.',
                      actionLabel: 'Registrar vehículo',
                      onAction: c.busy ? null : _register,
                    ),
                  for (final v in c.items)
                    OperationalCard(
                      color: c.occupiedVehicles.contains(v.id)
                          ? AppColors.dangerSurface
                          : c.vehicleId == v.id
                          ? AppColors.parkingHint
                          : AppColors.surface,
                      border: c.vehicleId == v.id
                          ? AppColors.action
                          : AppColors.outline,
                      onTap: c.busy || c.occupiedVehicles.contains(v.id)
                          ? null
                          : () => c.choose(v.id),
                      child: Semantics(
                        label: 'Vehículo ${v.plate}',
                        selected: c.vehicleId == v.id,
                        enabled: !c.occupiedVehicles.contains(v.id),
                        child: Row(
                          children: [
                            const Icon(Icons.directions_car, size: 32),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    visualPlate(v.plate),
                                    style: Theme.of(context)
                                        .textTheme
                                        .titleMedium,
                                  ),
                                  Text(
                                    c.occupiedVehicles.contains(v.id)
                                        ? 'Estacionado actualmente'
                                        : 'Vehículo disponible',
                                  ),
                                ],
                              ),
                            ),
                            Icon(
                              c.occupiedVehicles.contains(v.id)
                                  ? Icons.lock_outline
                                  : c.vehicleId == v.id
                                  ? Icons.radio_button_checked
                                  : Icons.radio_button_unchecked,
                              color: c.vehicleId == v.id
                                  ? AppColors.action
                                  : AppColors.muted,
                            ),
                          ],
                        ),
                      ),
                    ),
                  PrimaryButton(
                    label: 'Continuar',
                    onPressed:
                        c.vehicleId != null &&
                            !c.occupiedVehicles.contains(c.vehicleId) &&
                            c.space.selectable &&
                            !c.busy &&
                            q?.operational == true
                        ? () => setState(() => step = 1)
                        : null,
                  ),
                ] else if (step == 1 && q != null) ...[
                  DurationOptions(
                    load: c.durationOptions,
                    selected: c.minutes,
                    onSelected: c.selectDuration,
                    enabled: !c.busy,
                  ),
                  InfoCard(
                    'Tiempo máximo continuo: ${q.maximum == null ? "—" : durationLabel(q.maximum!)}.',
                  ),
                  PrimaryButton(
                    label: 'Continuar',
                    onPressed: c.canSubmit
                        ? () => setState(() => step = 2)
                        : null,
                  ),
                  SecondaryButton(
                    label: 'Cambiar vehículo',
                    onPressed: c.busy ? null : () => setState(() => step = 0),
                  ),
                ] else if (q != null) ...[
                  OperationalCard(
                    child: Column(
                      children: [
                        SummaryRow(
                          'Zona',
                          c.space.catalog.zoneOf(c.space.space)?.name ?? '',
                        ),
                        SummaryRow('Espacio', c.space.space.code),
                        SummaryRow(
                          'Vehículo',
                          visualPlate(
                            c.items
                                    .where((v) => v.id == c.vehicleId)
                                    .firstOrNull
                                    ?.plate ??
                                '',
                          ),
                        ),
                        SummaryRow('Tiempo', durationLabel(q.minutes!)),
                        const Divider(),
                        SummaryRow(
                          'Total',
                          displayMoney(q.currency, q.amount),
                          important: true,
                        ),
                      ],
                    ),
                  ),
                  if (c.canSubmit || c.phase == ParkingPhase.processing)
                    AsyncButton(
                      label: 'Continuar al pago',
                      processingLabel: 'Preparando...',
                      onPressed: _submit,
                    ),
                  SecondaryButton(
                    label: 'Revisar datos',
                    onPressed: c.busy ? null : () => setState(() => step = 1),
                  ),
                ],
              ],
            ],
          ),
        ),
      );
    },
  );
}

String localTime(DateTime value) {
  final d = value.toLocal();
  return '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}

String serverOffset(String evaluated) =>
    RegExp(r'(Z|[+-]\d{2}:\d{2})(?:\[[^\]]+\])?$')
        .firstMatch(evaluated)
        ?.group(1) ??
    'según el calendario del sistema';
