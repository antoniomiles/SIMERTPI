import '../../vehicles/presentation/vehicle_plate.dart';
import '../../../app/router/app_router.dart';

import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/widgets/app_skeleton.dart';
import '../../parking/data/parking_contract.dart';
import '../../payments/data/payment_contract.dart';
import '../data/active_parking_service.dart';
import '../state/active_parking_controller.dart';

class ActiveParkingPanel extends StatefulWidget {
  const ActiveParkingPanel({super.key, required this.controller});
  final ActiveParkingController controller;
  @override
  State<ActiveParkingPanel> createState() => _ActiveParkingPanelState();
}

class _ActiveParkingPanelState extends State<ActiveParkingPanel>
    with WidgetsBindingObserver {
  Timer? timer;
  bool opening = false;
  final refreshed = <String>{};
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _start();
  }

  void _start() {
    timer?.cancel();
    timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      final c = widget.controller;
      for (final s in c.sessions) {
        final key = '${s.id}:${s.expectedEndAt.toIso8601String()}';
        if (!s.expectedEndAt.isAfter(c.now()) && !refreshed.contains(key)) {
          refreshed.add(key);
          c.load();
        }
      }
      setState(() {});
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      widget.controller.load();
      _start();
    } else {
      timer?.cancel();
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _extension(ParkingReceipt s) async {
    if (opening || widget.controller.busy) return;
    opening = true;
    try {
      await widget.controller.prepare(s.id);
      if (!mounted) return;
      await Navigator.pushNamed(
        context,
        AppRoute.activeParking.path,
        arguments: widget.controller,
      );
      await widget.controller.load();
    } finally {
      opening = false;
    }
  }

  Future<void> _close(ParkingReceipt s) async {
    if (widget.controller.busy || opening) return;
    opening = true;
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => StatefulBuilder(
          builder: (dialogContext, setDialogState) => PopScope(
            canPop: !widget.controller.busy,
            child: AlertDialog(
              scrollable: true,
              title: const Text('Finalizar estacionamiento'),
              content: Text(
                '¿Deseas finalizar el estacionamiento de ${widget.controller.plate(s)}?\n\nEl espacio quedará liberado cuando el sistema confirme el cierre.',
              ),
              actions: [
                TextButton(
                  onPressed: widget.controller.busy
                      ? null
                      : () => Navigator.pop(dialogContext, false),
                  child: const Text('Cancelar'),
                ),
                AsyncButton(
                  label: 'Finalizar',
                  processingLabel: 'Finalizando…',
                  onPressed: () async {
                    final result = widget.controller.closeSession(s.id);
                    setDialogState(() {});
                    final success = await result;
                    if (dialogContext.mounted) {
                      Navigator.pop(dialogContext, success);
                    }
                  },
                ),
              ],
            ),
          ),
        ),
      );
      if (mounted && confirmed == true) {
        AppSnackbar.show(context, 'Estacionamiento finalizado.');
      }
    } finally {
      opening = false;
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (c.loading && !c.loaded) const SkeletonCard(),
          if (c.message != null)
            ErrorState(message: c.message!, onRetry: c.load),
          for (final s in c.sessions)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpace.lg),
              child: Semantics(
                container: true,
                label: 'Estacionamiento ${activeStatus(s.status)}',
                child: Container(
                  padding: const EdgeInsets.all(AppSpace.lg),
                  decoration: BoxDecoration(
                    color: AppColors.parkingHint,
                    borderRadius: BorderRadius.circular(AppSize.radius),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        'Estacionamiento activo',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      Text(activeStatus(s.status)),
                      if (c.stale)
                        const Text(
                          'Último estado conocido. Requiere actualización.',
                        ),
                      const SizedBox(height: AppSpace.md),
                      Text('Espacio: ${c.space(s)}'),
                      if (c.street(s) != null)
                        Text('Ubicación: ${c.street(s)}'),
                      Text('Vehículo: ${visualPlate(c.plate(s))}'),
                      Semantics(
                        label:
                            'Tiempo contratado restante ${remainingTime(s, c.now())}',
                        child: ExcludeSemantics(
                          child: Text(
                            'Tiempo contratado restante: ${remainingTime(s, c.now())}',
                          ),
                        ),
                      ),
                      Text(
                        'Hasta: ${s.expectedEndAt.toLocal().hour.toString().padLeft(2, '0')}:${s.expectedEndAt.toLocal().minute.toString().padLeft(2, '0')}',
                      ),
                      if (s.status == 'EXPIRED')
                        const Text(
                          'El período contratado terminó. La sesión aún no está finalizada.',
                        ),
                      if (s.status == 'MAX_TIME_REACHED')
                        const Text(
                          'Se alcanzó el límite continuo. No es posible extender el tiempo.',
                        ),
                      const SizedBox(height: AppSpace.md),
                      if (!c.busy &&
                          !c.stale &&
                          {'ACTIVE', 'EXTENDED', 'EXPIRED'}.contains(s.status))
                        SecondaryButton(
                          label: 'Extender tiempo',
                          onPressed: () => _extension(s),
                        ),
                      if (!c.busy)
                        SecondaryButton(
                          label: 'Finalizar estacionamiento',
                          onPressed: () => _close(s),
                        ),
                      if (c.busy) const LinearProgressIndicator(),
                      TextButton(
                        onPressed: c.busy ? null : c.load,
                        child: const Text('Actualizar estado'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
        ],
      );
    },
  );
}

class ExtensionPage extends StatefulWidget {
  const ExtensionPage({super.key, required this.controller});
  final ActiveParkingController controller;
  @override
  State<ExtensionPage> createState() => _ExtensionPageState();
}

class _ExtensionPageState extends State<ExtensionPage> {
  final minutes = TextEditingController();
  @override
  void dispose() {
    minutes.dispose();
    super.dispose();
  }

  bool confirming = false;
  Future<void> _confirm() async {
    if (confirming || widget.controller.busy) return;
    confirming = true;
    final c = widget.controller;
    final q = c.quote!;
    try {
      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => ListenableBuilder(
          listenable: c,
          builder: (context, _) => PopScope(
            canPop: !c.busy,
            child: AlertDialog(
              scrollable: true,
              title: const Text('Confirmar extensión'),
              content: Text(
                'Agregar ${q.minutes} minutos por ${q.currency} ${q.amount}. El tiempo solo se ampliará cuando el sistema confirme el pago.',
              ),
              actions: [
                TextButton(
                  onPressed: c.busy ? null : () => Navigator.pop(dialogContext),
                  child: const Text('Cancelar'),
                ),
                AsyncButton(
                  label: 'Confirmar',
                  onPressed: () async {
                    await c.confirmExtension();
                    if (dialogContext.mounted) Navigator.pop(dialogContext);
                  },
                ),
              ],
            ),
          ),
        ),
      );
    } finally {
      confirming = false;
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller, q = c.quote, p = c.payment;
      return PopScope(
        canPop: !c.busy,
        child: Scaffold(
          appBar: AppBar(title: const Text('Extender tiempo')),
          body: SafeArea(
            child: SingleChildScrollView(
              padding: AppSpace.page,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (c.selected != null) ...[
                    Text('Espacio: ${c.space(c.selected!)}'),
                    Text('Vehículo: ${c.plate(c.selected!)}'),
                    Text(activeStatus(c.selected!.status)),
                  ],
                  if (c.message != null)
                    Semantics(liveRegion: true, child: Text(c.message!)),
                  if (c.message != null &&
                      c.intent == null &&
                      c.selected != null &&
                      !c.busy)
                    TextButton(
                      onPressed: () => c.prepare(c.selected!.id),
                      child: const Text('Reintentar consulta'),
                    ),
                  if (!c.dev)
                    const Text(
                      'No hay un método de pago habilitado para esta aplicación en este ambiente.',
                    ),
                  if (c.intent == null && p == null && q != null) ...[
                    Text(
                      'Mínimo: ${q.minimum} minutos. Máximo continuo: ${q.maximum} minutos, sujeto al tiempo ya utilizado y al calendario.',
                    ),
                    TextField(
                      controller: minutes,
                      keyboardType: TextInputType.number,
                      decoration: InputDecoration(
                        labelText: 'Minutos adicionales',
                        hintText: '${q.minutes ?? q.minimum}',
                      ),
                      enabled: !c.busy,
                      onChanged: (_) => setState(() {}),
                    ),
                    TextButton(
                      onPressed: c.busy
                          ? null
                          : () {
                              final value = int.tryParse(minutes.text.trim());
                              if (value == null || value <= 0) {
                                AppSnackbar.show(
                                  context,
                                  'Ingresa una cantidad válida de minutos.',
                                );
                                return;
                              }
                              c.pricing(value);
                            },
                      child: const Text('Consultar cotización'),
                    ),
                    if (q.quoted) ...[
                      Text('Tiempo adicional cotizado: ${q.minutes} minutos'),
                      Text('Importe calculado: ${q.currency} ${q.amount}'),
                      Text(
                        'Nueva finalización: ${DateTime.parse(q.expiresAt!).toLocal()}',
                      ),
                      if (c.canExtend &&
                          (minutes.text.trim().isEmpty ||
                              int.tryParse(minutes.text.trim()) == q.minutes))
                        SecondaryButton(
                          label: 'Confirmar extensión',
                          onPressed: _confirm,
                        ),
                    ] else
                      Text(ruleMessage(q.reason)),
                    if (c.dev)
                      const Text(
                        'Método TEST, solo para pruebas DEV. No representa un pago real.',
                      ),
                  ],
                  if (p != null) ...[
                    Text(switch (p.status) {
                      PaymentStatus.approved => 'Pago aprobado',
                      PaymentStatus.declined => 'Pago rechazado',
                      PaymentStatus.failed => 'Pago no completado',
                      PaymentStatus.cancelled => 'Pago cancelado',
                      _ => 'Pago por confirmar',
                    }),
                    Text('Importe backend: ${p.currency} ${p.amount}'),
                    if (p.status == PaymentStatus.approved &&
                        c.selected?.status == 'EXTENDED')
                      const Text('El sistema confirmó la extensión.')
                    else
                      const Text(
                        'No damos el tiempo por ampliado sin confirmación del estado de la sesión.',
                      ),
                  ],
                  if (c.intent != null && !c.busy)
                    AsyncButton(
                      label: 'Consultar resultado',
                      onPressed: c.checkExtension,
                    ),
                  if (c.busy) const LinearProgressIndicator(),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}
