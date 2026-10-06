import '../../vehicles/presentation/vehicle_plate.dart';
import '../../../app/router/app_router.dart';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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
  final _duration = TextEditingController();
  final _form = GlobalKey<FormState>();
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
    _duration.text = _controller!.minutes?.toString() ?? '';
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

  Future<void> _estimate() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    await _controller!.estimate();
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
    _duration.dispose();
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
          title: step == 0
              ? 'Selecciona un vehículo'
              : step == 1
              ? 'Tiempo de estacionamiento'
              : 'Resumen del estacionamiento',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                c.space.space.code,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Text(c.space.catalog.streetOf(c.space.space)?.name ?? ''),
              const SizedBox(height: AppSpace.lg),
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
                    Padding(
                      padding: const EdgeInsets.only(bottom: AppSpace.md),
                      child: AppCard(
                        child: Semantics(
                          label: 'Vehículo ${v.plate}',
                          selected: c.vehicleId == v.id,
                          enabled: !c.occupiedVehicles.contains(v.id),
                          child: ListTile(
                            contentPadding: EdgeInsets.zero,
                            leading: const Icon(Icons.directions_car),
                            title: Text(visualPlate(v.plate)),
                            subtitle: Text(
                              c.occupiedVehicles.contains(v.id)
                                  ? 'Estacionado actualmente'
                                  : 'Vehículo disponible',
                            ),
                            trailing: Icon(
                              c.occupiedVehicles.contains(v.id)
                                  ? Icons.lock
                                  : c.vehicleId == v.id
                                  ? Icons.radio_button_checked
                                  : Icons.radio_button_unchecked,
                            ),
                            onTap: c.busy || c.occupiedVehicles.contains(v.id)
                                ? null
                                : () => c.choose(v.id),
                          ),
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
                  if (q.minimum != null && q.maximum != null) ...[
                    Text('Mínimo ${q.minimum} min · máximo ${q.maximum} min'),
                    Text('Fracción facturable: ${q.minimum} min'),
                    const SizedBox(height: AppSpace.md),
                    Form(
                      key: _form,
                      child: AppTextField(
                        controller: _duration,
                        label: 'Minutos',
                        enabled: !c.busy,
                        keyboardType: TextInputType.number,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                        ],
                        onChanged: c.changedDuration,
                        validator: (text) {
                          final n = int.tryParse(text ?? '');
                          return n == null || n < q.minimum! || n > q.maximum!
                              ? 'Ingresa de ${q.minimum} a ${q.maximum} minutos.'
                              : null;
                        },
                      ),
                    ),
                    AsyncButton(
                      label: 'Consultar importe',
                      onPressed: _estimate,
                    ),
                  ],
                  if (q.quoted && q.minutes == c.minutes)
                    Text(
                      'Importe cotizado: ${q.currency} ${q.amount}',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  if (q.schedule != null)
                    Text('Horario aplicable: ${q.window}'),
                  PrimaryButton(
                    label: 'Revisar resumen',
                    onPressed: c.canSubmit
                        ? () => setState(() => step = 2)
                        : null,
                  ),
                  SecondaryButton(
                    label: 'Cambiar vehículo',
                    onPressed: c.busy ? null : () => setState(() => step = 0),
                  ),
                ] else if (q != null) ...[
                  Text(
                    'Zona: ${c.space.catalog.zoneOf(c.space.space)?.name ?? ""}',
                  ),
                  Text('Espacio: ${c.space.space.code}'),
                  Text(
                    'Vehículo: ${visualPlate(c.items.where((v) => v.id == c.vehicleId).firstOrNull?.plate ?? "")}',
                  ),
                  Text('Tiempo: ${q.minutes} min'),
                  const SizedBox(height: AppSpace.lg),
                  Text(
                    'Total: ${q.currency} ${q.amount}',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: AppSpace.md),
                  const Text(
                    'La solicitud reserva el espacio; el estacionamiento se activa solo después de confirmar el pago.',
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
