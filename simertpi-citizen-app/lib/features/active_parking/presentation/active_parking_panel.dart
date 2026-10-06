import '../../../core/widgets/operational_ui.dart';
import '../../parking/presentation/duration_options.dart';
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
          for (final s in c.sessions) _card(context, c, s),
        ],
      );
    },
  );
  Widget _card(
    BuildContext context,
    ActiveParkingController c,
    ParkingReceipt s,
  ) {
    final left = s.expectedEndAt.difference(c.now()).inSeconds;
    final location = c.locations?.spaces
        .where((v) => v.id == s.spaceId)
        .firstOrNull;
    final expired = s.status == 'EXPIRED' || left <= 0;
    final maximum = s.status == 'MAX_TIME_REACHED';
    final soon =
        !expired &&
        !maximum &&
        (location?.endingSoonSeconds != null
            ? left <= location!.endingSoonSeconds!
            : location?.operationalStatus == 'ENDING_SOON');
    final color = expired || maximum
        ? AppColors.occupied
        : soon
        ? AppColors.endingSoon
        : AppColors.available;
    final label = maximum
        ? 'Tiempo máximo alcanzado'
        : expired
        ? 'Tiempo de estacionamiento vencido'
        : soon
        ? 'Próximo a vencer'
        : 'Estacionamiento activo';
    final extend =
        !maximum &&
        !expired &&
        !c.busy &&
        !c.stale &&
        {'ACTIVE', 'EXTENDED'}.contains(s.status) &&
        (c.gateway is! DurationOptionsGateway ||
            c.extensionOptions[s.id]?.isNotEmpty == true);
    final close = !c.busy && !c.stale && (!expired && !maximum);
    return OperationalCard(
      color: expired || maximum
          ? AppColors.dangerSurface
          : soon
          ? AppColors.warningSurface
          : AppColors.successSurface,
      border: Colors.transparent,
      child: Semantics(
        container: true,
        label: label,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  expired || maximum || soon
                      ? Icons.warning_amber_rounded
                      : Icons.directions_car,
                  color: color,
                  size: 20,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    label,
                    style: Theme.of(context).textTheme.titleSmall
                        ?.copyWith(color: color),
                  ),
                ),
                if (!expired && !maximum)
                  IconButton(
                    tooltip: 'Actualizar estado',
                    onPressed: c.busy ? null : c.load,
                    icon: Icon(Icons.refresh, color: color, size: 18),
                  ),
              ],
            ),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.directions_car, size: 32, color: color),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        visualPlate(c.plate(s)),
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text('Espacio ${c.space(s)}'),
                      if (c.street(s) != null)
                        Text(
                          c.street(s)!,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      if (c.stale)
                        const Text(
                          'Último estado conocido. Requiere actualización.',
                        ),
                      if (expired || maximum)
                        Text(
                          maximum
                              ? 'Has cumplido el tiempo máximo permitido en este espacio.\nDebes mover tu vehículo a otro espacio.'
                              : 'El tiempo contratado ha finalizado.',
                          style: TextStyle(color: color),
                        )
                      else ...[
                        const Text('Tiempo contratado restante'),
                        Semantics(
                          label: 'Tiempo restante ${(left + 59) ~/ 60} minutos',
                          child: Text(
                            '${(left + 59) ~/ 60} min',
                            style: Theme.of(context).textTheme.titleLarge
                                ?.copyWith(color: color),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (extend || close)
              LayoutBuilder(
                builder: (context, box) {
                  final buttons = <Widget>[
                    if (extend)
                      Expanded(
                        child: OutlinedButton(
                          onPressed: () => _extension(s),
                          child: const Text('Extender'),
                        ),
                      ),
                    if (extend && close) const SizedBox(width: 8),
                    if (close)
                      Expanded(
                        child: OutlinedButton(
                          onPressed: () => _close(s),
                          child: const Text('Finalizar'),
                        ),
                      ),
                  ];
                  if (MediaQuery.textScalerOf(context).scale(14) > 22) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        if (extend)
                          OutlinedButton(
                            onPressed: () => _extension(s),
                            child: const Text('Extender'),
                          ),
                        if (close)
                          OutlinedButton(
                            onPressed: () => _close(s),
                            child: const Text('Finalizar'),
                          ),
                      ],
                    );
                  }
                  return Row(children: buttons);
                },
              ),
            if (expired && !maximum && !extend && !c.loading)
              const Text(
                'No es posible extender una sesión vencida. Solicita asistencia para resolverla.',
              ),
            if (maximum)
              const Text(
                'El límite continuo impide extender este estacionamiento.',
              ),
            if (c.busy) const LinearProgressIndicator(),
          ],
        ),
      ),
    );
  }
}

class ExtensionPage extends StatefulWidget {
  const ExtensionPage({super.key, required this.controller});
  final ActiveParkingController controller;
  @override
  State<ExtensionPage> createState() => _ExtensionPageState();
}

class _ExtensionPageState extends State<ExtensionPage> {
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
                  if (c.extensionConfirmed) ...[
                    const SuccessMark(),
                    Text(
                      'Tiempo extendido correctamente',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    SummaryRow(
                      'Nuevo tiempo contratado restante',
                      '${c.selected!.expectedEndAt.difference(c.now()).inMinutes.clamp(0, 999999)} min',
                    ),
                    SummaryRow('Importe', displayMoney(p?.currency, p?.amount)),
                    PrimaryButton(
                      label: 'Volver al inicio',
                      onPressed: () => Navigator.pop(context),
                    ),
                  ] else ...[
                    if (c.selected != null)
                      OperationalCard(
                        child: Column(
                          children: [
                            Text(
                              visualPlate(c.plate(c.selected!)),
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            Text(c.space(c.selected!)),
                          ],
                        ),
                      ),
                    if (c.message != null)
                      ErrorState(
                        message: c.message!,
                        onRetry: () => c.prepare(c.selected!.id),
                      ),
                    if (!c.dev)
                      const InfoCard(
                        'La extensión de tiempo no está habilitada para este ambiente.',
                      ),
                    if (c.intent == null && p == null && q != null) ...[
                      Text(
                        '¿Cuánto tiempo deseas agregar?',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 16),
                      DurationOptions(
                        load: c.durationOptions,
                        selected: q.minutes,
                        onSelected: c.selectDuration,
                        enabled: !c.busy,
                      ),
                      if (q.quoted)
                        SummaryRow(
                          'Total adicional',
                          displayMoney(q.currency, q.amount),
                          important: true,
                        ),
                      if (c.canExtend)
                        PrimaryButton(label: 'Continuar', onPressed: _confirm),
                      if (!q.quoted) InfoCard(ruleMessage(q.reason)),
                    ],
                    if (p != null)
                      InfoCard(switch (p.status) {
                        PaymentStatus.declined =>
                          'Pago rechazado. El tiempo no se amplió.',
                        PaymentStatus.failed =>
                          'No se completó el pago. El tiempo no se amplió.',
                        _ => 'El resultado está por confirmar. Consulta antes de repetir.',
                      }),
                    if (c.intent != null && !c.busy)
                      AsyncButton(
                        label: 'Consultar resultado',
                        onPressed: c.checkExtension,
                      ),
                    if (c.busy)
                      const Center(child: CircularProgressIndicator()),
                  ],
                  if (c.dev)
                    Text(
                      'Pago de prueba. Sin transacción bancaria real.',
                      style: Theme.of(context).textTheme.bodySmall,
                      textAlign: TextAlign.center,
                    ),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}
