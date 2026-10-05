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
  bool _reviewing = false;
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
    if (mounted) setState(() => _reviewing = false);
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
    if (mounted &&
        _controller!.phase != ParkingPhase.created &&
        _controller!.phase != ParkingPhase.uncertain) {
      setState(() => _reviewing = false);
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
      final c = _controller!;
      final q = c.quote;
      final phase = c.phase;
      return PopScope(
        canPop: !c.busy,
        child: AppPage(
          title: _reviewing
              ? 'Resumen de la solicitud'
              : 'Preparar estacionamiento',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Espacio ${c.space.space.number}',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(c.space.space.code),
              Text(c.space.catalog.streetOf(c.space.space)?.name ?? ''),
              const SizedBox(height: AppSpace.md),
              if (phase == ParkingPhase.initial ||
                  phase == ParkingPhase.loading)
                const SkeletonList(count: 2)
              else if (phase == ParkingPhase.created) ...[
                Semantics(
                  liveRegion: true,
                  child: Text(
                    'Solicitud pendiente de pago',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                const SizedBox(height: AppSpace.md),
                const Text(
                  'Se activará únicamente cuando el sistema confirme el pago aprobado.',
                ),
                const SizedBox(height: AppSpace.md),
                PrimaryButton(
                  label: 'Continuar al pago',
                  onPressed: () => Navigator.pushNamed(
                    context,
                    AppRoute.payments.path,
                    arguments: c.receipt!.id,
                  ),
                ),
                const SizedBox(height: AppSpace.md),
                Text('Inicio registrado: ${localTime(c.receipt!.startedAt)}'),
                Text('Fin previsto: ${localTime(c.receipt!.expectedEndAt)}'),
                const Text(
                  'Horas mostradas según la zona horaria del dispositivo.',
                ),
              ] else if (phase == ParkingPhase.uncertain) ...[
                ErrorState(
                  message: c.message ?? 'Hay una solicitud por confirmar. Comprueba su estado antes de continuar.',
                  onRetry: c.recover,
                ),
                if (c.busy) const LinearProgressIndicator(),
                const Text(
                  'Volver no cancela una solicitud que haya sido registrada.',
                ),
              ] else ...[
                if (c.message != null)
                  ErrorState(message: c.message!, onRetry: _load),
                if (c.busy) const LinearProgressIndicator(),
                if (phase != ParkingPhase.error &&
                    phase != ParkingPhase.conflict &&
                    q != null) ...[
                  if (!_reviewing) ...[
                    Text(
                      'Vehículo',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    if (c.items.isEmpty)
                      EmptyState(
                        message: 'No tienes vehículos habilitados.',
                        actionLabel: 'Registrar vehículo',
                        onAction: c.busy ? null : _register,
                      )
                    else
                      Wrap(
                        spacing: AppSpace.sm,
                        runSpacing: AppSpace.sm,
                        children: [
                          for (final v in c.items)
                            Semantics(
                              label: 'Vehículo ${v.plate}',
                              selected: c.vehicleId == v.id,
                              child: ChoiceChip(
                                label: Text(v.plate),
                                selected: c.vehicleId == v.id,
                                onSelected: c.busy
                                    ? null
                                    : (_) => c.choose(v.id),
                              ),
                            ),
                        ],
                      ),
                    const SizedBox(height: AppSpace.lg),
                    if (q.minimum != null && q.maximum != null) ...[
                      Text(
                        'Duración en minutos',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      Text('Mínimo ${q.minimum} min · máximo ${q.maximum} min'),
                      Text(
                        'Fracción facturable: ${q.minimum} min. El importe lo calcula el sistema.',
                      ),
                      const SizedBox(height: AppSpace.sm),
                      Form(
                        key: _form,
                        child: AppTextField(
                          controller: _duration,
                          label: 'Minutos',
                          enabled: !c.busy && c.space.selectable,
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
                      const SizedBox(height: AppSpace.sm),
                      if (!c.busy && c.space.selectable)
                        AsyncButton(
                          label: 'Consultar importe',
                          processingLabel: 'Consultando...',
                          onPressed: _estimate,
                        ),
                    ],
                  ],
                  const SizedBox(height: AppSpace.lg),
                  if (q.schedule != null)
                    Text('Horario aplicable: ${q.window}'),
                  if (q.schedule != null)
                    Text('Zona horaria: ${serverOffset(q.evaluatedAt)}'),
                  if (q.holiday)
                    const Text('Calendario de excepción aplicado.'),
                  if (q.unitPrice != null)
                    Text(
                      'Tarifa: ${q.currency} ${q.unitPrice} por ${q.unitMinutes} min',
                    ),
                  if (q.quoted && q.minutes == c.minutes) ...[
                    const SizedBox(height: AppSpace.md),
                    AppCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(
                            'Importe estimado: ${q.currency} ${q.amount}',
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                          Text('Duración solicitada: ${q.minutes} min'),
                          Text('Tiempo facturado: ${q.billed} min'),
                          Text(
                            'Fin estimado: ${localTime(DateTime.parse(q.expiresAt!))}',
                          ),
                          const Text(
                            'Hora del dispositivo. Las condiciones se volverán a consultar al confirmar.',
                          ),
                          if (_reviewing)
                            Text(
                              'Vehículo: ${c.items.firstWhere((v) => v.id == c.vehicleId).plate}',
                            ),
                        ],
                      ),
                    ),
                  ],
                  const SizedBox(height: AppSpace.lg),
                  const Text(
                    'La disponibilidad se confirma al registrar la solicitud. Ubicación habilitada no significa espacio libre.',
                  ),
                  if (_reviewing) ...[
                    const SizedBox(height: AppSpace.md),
                    const Text(
                      'Al confirmar se crea una solicitud pendiente de pago. El espacio queda ocupado y el tiempo previsto empieza según el registro del sistema.',
                    ),
                    const SizedBox(height: AppSpace.md),
                    if (c.canSubmit || c.phase == ParkingPhase.processing)
                      AsyncButton(
                        label: 'Preparar solicitud',
                        processingLabel: 'Preparando...',
                        onPressed: _submit,
                      ),
                    SecondaryButton(
                      label: 'Revisar datos',
                      onPressed: c.busy
                          ? null
                          : () => setState(() => _reviewing = false),
                    ),
                  ] else
                    PrimaryButton(
                      label: 'Revisar resumen',
                      onPressed: c.canSubmit
                          ? () => setState(() => _reviewing = true)
                          : null,
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
