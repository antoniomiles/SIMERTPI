import '../../vehicles/presentation/vehicle_plate.dart';
import '../../../app/router/app_router.dart';

import 'package:flutter/material.dart';

import '../../../core/widgets/operational_ui.dart';
import 'duration_options.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../core/config/app_config.dart';
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
  bool _devMethodSelected = false;
  bool _openingCheckout = false;
  bool _leaving = false;

  void _leave(int tab) {
    if (_controller!.phase == ParkingPhase.processing || _openingCheckout) {
      return;
    }
    AppScope.of(context).citizenTab?.value = tab;
    setState(() => _leaving = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      Navigator.of(context).popUntil(
        (route) => route.settings.name == AppRoute.home.path || route.isFirst,
      );
    });
  }

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

  Future<void> _openSummary() async {
    if (_openingCheckout ||
        !_devMethodSelected ||
        AppScope.of(context).config.environment != AppEnvironment.dev) {
      return;
    }
    setState(() {
      _openingCheckout = true;
      _leaving = true;
    });
    try {
      await Navigator.pushNamed<void>(
        context,
        AppRoute.payments.path,
        arguments: _controller!,
      );
      if (mounted &&
          _controller!.receipt != null &&
          ModalRoute.of(context)?.isCurrent == true) {
        Navigator.of(context).pop();
      }
    } finally {
      if (mounted) {
        setState(() {
          _openingCheckout = false;
          _leaving = false;
        });
      }
    }
  }

  void _selectDevMethod() {
    if (_controller?.busy == true ||
        AppScope.of(context).config.environment != AppEnvironment.dev) {
      return;
    }
    setState(() => _devMethodSelected = true);
  }

  Future<void> _reviewStartedOperation() async {
    await _controller!.recover();
    if (!mounted || _controller!.receipt == null) return;
    setState(() => _leaving = true);
    await Navigator.pushNamed<void>(
      context,
      AppRoute.payments.path,
      arguments: _controller!.receipt!.id,
    );
    if (mounted) Navigator.of(context).pop();
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
            c.phase != ParkingPhase.processing &&
            (_leaving ||
                step == 0 ||
                c.phase == ParkingPhase.created ||
                c.phase == ParkingPhase.uncertain),
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop && !c.busy && step > 0) setState(() => step--);
        },
        child: AppPage(
          showNavigation: c.phase != ParkingPhase.processing,
          onNavigationSelected: _leave,
          title: step == 0
              ? 'Selecciona un vehículo'
              : step == 1
              ? 'Tiempo de estacionamiento'
              : step == 2
              ? 'Confirmar pago'
              : 'Método de pago',
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
              else if (c.phase == ParkingPhase.uncertain ||
                  c.phase == ParkingPhase.created)
                ErrorState(
                  message:
                      c.message ?? 'Comprueba la solicitud antes de continuar.',
                  onRetry: _reviewStartedOperation,
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
                ] else if (step == 2 && q != null) ...[
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
                  PrimaryButton(
                    label: 'Seleccionar método de pago',
                    onPressed: c.canSubmit
                        ? () => setState(() {
                            _devMethodSelected = false;
                            step = 3;
                          })
                        : null,
                  ),
                  SecondaryButton(
                    label: 'Revisar datos',
                    onPressed: c.busy ? null : () => setState(() => step = 1),
                  ),
                ] else if (step == 3 && q != null) ...[
                  const Text(
                    'Selecciona cómo deseas pagar tu estacionamiento.',
                  ),
                  const SizedBox(height: AppSpace.sm),
                  _PaymentMethodOption(
                    icon: Icons.credit_card,
                    title: 'Tarjeta de crédito o débito',
                    subtitle: 'Próximamente',
                    enabled: false,
                    selected: false,
                    onTap: null,
                  ),
                  _PaymentMethodOption(
                    icon: Icons.phone_android,
                    title: 'Deuna',
                    subtitle: 'Próximamente',
                    enabled: false,
                    selected: false,
                    onTap: null,
                  ),
                  _PaymentMethodOption(
                    icon: Icons.developer_mode,
                    title: 'Método DEV',
                    subtitle:
                        AppScope.of(context).config.environment ==
                            AppEnvironment.dev
                        ? 'Disponible'
                        : 'No disponible',
                    enabled:
                        AppScope.of(context).config.environment ==
                        AppEnvironment.dev,
                    selected: _devMethodSelected,
                    onTap: _selectDevMethod,
                  ),
                  PrimaryButton(
                    label: 'Continuar con el pago',
                    onPressed: _devMethodSelected && c.canSubmit
                        ? _openSummary
                        : null,
                  ),
                  SecondaryButton(
                    label: 'Volver',
                    onPressed: c.busy ? null : () => setState(() => step = 2),
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

class _PaymentMethodOption extends StatelessWidget {
  const _PaymentMethodOption({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.enabled,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool enabled;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpace.sm),
      child: Semantics(
        button: true,
        enabled: enabled,
        selected: selected,
        label: '$title. $subtitle',
        child: Material(
          color: selected
              ? theme.colorScheme.primaryContainer
              : theme.colorScheme.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: BorderSide(
              color: selected
                  ? theme.colorScheme.primary
                  : theme.colorScheme.outlineVariant,
            ),
          ),
          child: InkWell(
            onTap: enabled ? onTap : null,
            borderRadius: BorderRadius.circular(16),
            child: Padding(
              padding: const EdgeInsets.all(AppSpace.md),
              child: Row(
                children: [
                  Icon(icon, color: enabled ? theme.colorScheme.primary : null),
                  const SizedBox(width: AppSpace.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(title, style: theme.textTheme.titleMedium),
                        Text(subtitle, style: theme.textTheme.bodySmall),
                      ],
                    ),
                  ),
                  if (enabled)
                    Icon(
                      selected
                          ? Icons.radio_button_checked
                          : Icons.radio_button_unchecked,
                      color: theme.colorScheme.primary,
                    )
                  else
                    const Icon(Icons.lock_outline),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
