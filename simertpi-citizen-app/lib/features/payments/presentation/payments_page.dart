import '../../vehicles/presentation/vehicle_plate.dart';
import '../../active_parking/state/active_parking_controller.dart';
import '../../discovery/data/parking_catalog.dart';
import '../../../app/router/app_router.dart';

import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../core/config/app_config.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_skeleton.dart';
import '../../../core/widgets/operational_ui.dart';
import '../../parking/data/parking_contract.dart';
import '../../vehicles/data/vehicle_service.dart';
import '../data/payment_contract.dart';
import '../data/payment_intent_store.dart';
import '../state/payments_controller.dart';

class PaymentsPage extends StatefulWidget {
  const PaymentsPage({
    super.key,
    this.sessionId,
    this.controller,
    this.extensionController,
  });
  final String? sessionId;
  final PaymentsController? controller;
  final ActiveParkingController? extensionController;
  @override
  State<PaymentsPage> createState() => _PaymentsPageState();
}

class _PaymentsPageState extends State<PaymentsPage> {
  PaymentsController? _controller;
  bool _reviewing = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_controller != null || widget.extensionController != null) return;
    final scope = AppScope.of(context);
    final owner = scope.auth.userId ?? '';
    _controller =
        widget.controller ??
        PaymentsController(
          owner: owner,
          parking: ParkingService(scope.api),
          catalog: ParkingCatalogService(scope.api),
          vehicles: VehicleService(scope.api, owner),
          payments: PaymentService(scope.api),
          store: SecurePaymentIntentStore(
            '${scope.config.environment.name}:${scope.config.apiBaseUrl}:$owner',
          ),
          dev: scope.config.environment == AppEnvironment.dev,
        );
    _controller!.load(widget.sessionId);
  }

  @override
  void dispose() {
    if (widget.controller == null && widget.extensionController == null) {
      _controller?.dispose();
    }
    super.dispose();
  }

  Future<void> _confirm() async {
    await _controller!.confirm();
    if (mounted && _controller!.phase == PaymentPhase.ready) {
      setState(() => _reviewing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final extension = widget.extensionController;
    if (extension != null) return _extensionCheckout(extension);
    return ListenableBuilder(
      listenable: _controller!,
      builder: (context, _) {
        final c = _controller!;
        final q = c.quote;
        final p = c.payment;
        return PopScope(
          canPop: !c.busy,
          child: AppPage(
            showNavigation: !c.busy,
            title: c.activated
                ? 'Estacionamiento confirmado'
                : c.phase == PaymentPhase.result
                ? 'Resultado de pago'
                : c.phase == PaymentPhase.processing
                ? 'Procesando pago'
                : _reviewing
                ? 'Resumen del estacionamiento'
                : 'Resumen del estacionamiento',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (c.phase == PaymentPhase.loading ||
                    c.phase == PaymentPhase.initial)
                  const SkeletonList(count: 2)
                else ...[
                  if (c.message != null)
                    Semantics(liveRegion: true, child: Text(c.message!)),
                  if (c.phase == PaymentPhase.selection) ...[
                    Text(
                      'Solicitudes pendientes de pago',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    if (c.choices.isEmpty)
                      const EmptyState(
                        message: 'No tienes solicitudes pendientes de pago.',
                      )
                    else
                      for (final s in c.choices)
                        Padding(
                          padding: const EdgeInsets.only(top: AppSpace.md),
                          child: AppCard(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Text('Solicitud ${s.id}'),
                                Text(
                                  'Duración: ${s.expectedEndAt.difference(s.startedAt).inMinutes} min',
                                ),
                                SecondaryButton(
                                  label: 'Revisar pago',
                                  onPressed: c.busy
                                      ? null
                                      : () => c.select(s.id),
                                ),
                              ],
                            ),
                          ),
                        ),
                  ] else if (c.phase == PaymentPhase.error)
                    ErrorState(
                      message: c.message ?? 'No pudimos preparar el pago.',
                      onRetry: () => c.load(widget.sessionId),
                    )
                  else if (c.phase == PaymentPhase.uncertain) ...[
                    const SizedBox(height: AppSpace.md),
                    Text(
                      'Resultado por confirmar',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const Text(
                      'No realices otro pago para esta solicitud. Puedes volver y consultar desde Inicio. Si todavía no hay una referencia, recuperar puede completar la solicitud original que no había sido recibida; no crea un intento distinto.',
                    ),
                    const SizedBox(height: AppSpace.md),
                    if (c.intent != null)
                      AsyncButton(
                        label: c.intent!.paymentId == null
                            ? 'Recuperar misma solicitud'
                            : 'Comprobar resultado',
                        processingLabel: 'Comprobando...',
                        onPressed: c.check,
                      ),
                  ] else if (c.phase == PaymentPhase.processing) ...[
                    const SizedBox(height: 64),
                    const Center(
                      child: SizedBox(
                        width: 64,
                        height: 64,
                        child: CircularProgressIndicator(strokeWidth: 5),
                      ),
                    ),
                    const SizedBox(height: AppSpace.lg),
                    const Text(
                      'Procesando tu pago...',
                      textAlign: TextAlign.center,
                    ),
                    const Text(
                      'Por favor espera unos segundos.',
                      textAlign: TextAlign.center,
                    ),
                    const Text(
                      'No cierres la aplicación.',
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: AppSpace.lg),
                    const InfoCard(
                      'Una vez aprobado el pago, se iniciará tu estacionamiento automáticamente.',
                    ),
                  ] else if (c.phase == PaymentPhase.result && c.activated) ...[
                    const SizedBox(height: AppSpace.lg),
                    const SuccessMark(),
                    const SizedBox(height: AppSpace.lg),
                    Text(
                      'Vehículo estacionado correctamente',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: AppSpace.lg),
                    OperationalCard(
                      child: Column(
                        children: [
                          SummaryRow(
                            'Espacio',
                            c.spaceCode ?? 'Espacio confirmado',
                          ),
                          SummaryRow(
                            'Vehículo',
                            c.vehicle == null
                                ? 'Vehículo confirmado'
                                : visualPlate(c.vehicle!.plate),
                          ),
                          SummaryRow(
                            'Tiempo contratado',
                            durationLabel(
                              c.session!.expectedEndAt
                                  .difference(c.session!.startedAt)
                                  .inMinutes,
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (p?.provider == 'SANDBOX_STUB')
                      Text(
                        'Pago de prueba DEV. Sin transacción bancaria real.',
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    PrimaryButton(
                      label: 'Aceptar',
                      onPressed: () {
                        final confirmed = AppScope.of(context).parkingConfirmed;
                        if (confirmed != null) confirmed.value++;
                        final navigator = Navigator.of(context);
                        bool foundHome = false;
                        navigator.popUntil((route) {
                          foundHome = route.settings.name == AppRoute.home.path;
                          return foundHome || route.isFirst;
                        });
                        if (!foundHome) {
                          navigator.pushNamedAndRemoveUntil(
                            AppRoute.home.path,
                            (_) => false,
                          );
                        }
                      },
                    ),
                  ] else if (c.phase == PaymentPhase.result) ...[
                    Semantics(
                      liveRegion: true,
                      child: Text(
                        c.resultTitle,
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                    ),
                    const SizedBox(height: AppSpace.sm),
                    Text(c.resultMessage),
                    const SizedBox(height: AppSpace.lg),
                    if (p != null)
                      AppCard(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Icon(
                              p.status == PaymentStatus.approved
                                  ? Icons.check_circle_outline
                                  : Icons.info_outline,
                              semanticLabel: c.resultTitle,
                            ),
                            const SizedBox(height: AppSpace.sm),
                            Text(
                              'Importe registrado: ${p.currency} ${p.amount}',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),

                            if (p.provider == 'SANDBOX_STUB')
                              const Text(
                                'Pago de prueba DEV. Sin transacción bancaria real.',
                              ),
                          ],
                        ),
                      ),
                    const SizedBox(height: AppSpace.md),
                    if (p?.waiting == true)
                      AsyncButton(
                        label: 'Comprobar resultado',
                        processingLabel: 'Comprobando...',
                        onPressed: c.check,
                      ),
                    if (p != null && !p.waiting)
                      SecondaryButton(
                        label: 'Ver solicitudes pendientes',
                        onPressed: c.busy ? null : c.pendingRequests,
                      ),
                    if (c.canRetry)
                      SecondaryButton(
                        label: 'Revisar otro intento',
                        onPressed: () async {
                          await c.reviewRetry();
                          if (mounted) setState(() => _reviewing = false);
                        },
                      ),
                  ] else if (q != null && c.session != null) ...[
                    if (!_reviewing && c.dev)
                      OperationalCard(
                        onTap: c.busy ? null : c.chooseMethod,
                        color: c.methodSelected
                            ? AppColors.parkingHint
                            : AppColors.surface,
                        child: Row(
                          children: [
                            Icon(
                              c.methodSelected
                                  ? Icons.radio_button_checked
                                  : Icons.radio_button_unchecked,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                c.methodSelected
                                    ? 'Método DEV seleccionado'
                                    : 'Seleccionar método DEV',
                              ),
                            ),
                          ],
                        ),
                      ),
                    if (!c.dev)
                      const InfoCard(
                        'Los métodos de pago aún no están habilitados para este ambiente.',
                      ),
                    OperationalCard(
                      child: Column(
                        children: [
                          SummaryRow('Zona', c.zoneName ?? ''),
                          SummaryRow('Espacio', c.spaceCode ?? ''),
                          SummaryRow('Vehículo', visualPlate(c.vehicle!.plate)),
                          SummaryRow('Tiempo', durationLabel(q.minutes!)),
                          const Divider(height: 24),
                          SummaryRow(
                            'Total',
                            displayMoney(q.currency, q.amount),
                            important: true,
                          ),
                        ],
                      ),
                    ),
                    const InfoCard(
                      'Pago procesado mediante el proveedor configurado.',
                      icon: Icons.lock_outline,
                    ),
                    if (c.dev)
                      Text(
                        'Pago de prueba DEV. Sin transacción bancaria real.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    const SizedBox(height: AppSpace.lg),
                    if (_reviewing) ...[
                      const Text(
                        'Confirmar envía una solicitud de pago. El estacionamiento se activa únicamente si el sistema confirma la aprobación.',
                      ),
                      const SizedBox(height: AppSpace.md),
                      if (c.canConfirm || c.phase == PaymentPhase.processing)
                        AsyncButton(
                          label: 'Pagar ${displayMoney(q.currency, q.amount)}',
                          processingLabel: 'Procesando pago...',
                          onPressed: _confirm,
                        ),
                      SecondaryButton(
                        label: 'Revisar datos',
                        onPressed: c.busy
                            ? null
                            : () => setState(() => _reviewing = false),
                      ),
                    ] else
                      PrimaryButton(
                        label: 'Continuar',
                        onPressed: c.canConfirm
                            ? () => setState(() => _reviewing = true)
                            : null,
                      ),
                  ],
                  if (c.busy && c.phase != PaymentPhase.processing)
                    const LinearProgressIndicator(),
                  const SizedBox(height: AppSpace.lg),
                  if (!c.busy && !c.activated)
                    SecondaryButton(
                      label: 'Volver',
                      onPressed: () => Navigator.of(context).pop(),
                    ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _extensionCheckout(ActiveParkingController c) => ListenableBuilder(
    listenable: c,
    builder: (context, _) {
      final minutes = c.extensionMinutes;
      final amount = c.extensionAmount;
      final currency = c.extensionCurrency;
      final p = c.payment;
      final done = c.extensionConfirmed;
      return PopScope(
        canPop: !c.busy,
        child: AppPage(
          showNavigation: !c.busy,
          title: done ? 'Extensión confirmada' : 'Pago de extensión',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (done) ...[
                const SuccessMark(),
                Text(
                  'Tiempo extendido correctamente',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                if (c.selected != null)
                  OperationalCard(
                    child: Column(
                      children: [
                        SummaryRow(
                          'Nuevo vencimiento',
                          MaterialLocalizations.of(context).formatTimeOfDay(
                            TimeOfDay.fromDateTime(
                              c.selected!.expectedEndAt.toLocal(),
                            ),
                          ),
                        ),
                        SummaryRow('Tiempo agregado', '${minutes ?? ''} min'),
                        SummaryRow(
                          'Importe aprobado',
                          displayMoney(p?.currency, p?.amount),
                        ),
                      ],
                    ),
                  ),
                if (p?.provider == 'SANDBOX_STUB')
                  const InfoCard(
                    'Pago de prueba DEV. Sin transacción bancaria real.',
                  ),
                PrimaryButton(
                  label: 'Volver al estacionamiento',
                  onPressed: () => Navigator.pop(context),
                ),
              ] else if (c.intent == null && p == null)
                ErrorState(
                  message:
                      c.message ?? 'No pudimos preparar el pago de extensión.',
                  onRetry: () async => Navigator.pop(context),
                )
              else ...[
                OperationalCard(
                  child: Column(
                    children: [
                      SummaryRow('Tiempo adicional', '${minutes ?? ''} min'),
                      SummaryRow(
                        'Total',
                        displayMoney(currency, amount),
                        important: true,
                      ),
                    ],
                  ),
                ),
                if (c.dev)
                  const InfoCard(
                    'Método de pago de prueba DEV. Sin transacción bancaria real.',
                  ),
                const InfoCard(
                  'El estacionamiento solo se ampliará cuando el sistema confirme el pago aprobado.',
                ),
                if (c.message != null)
                  ErrorState(message: c.message!, onRetry: c.checkExtension),
                if (p == null && !_reviewing)
                  PrimaryButton(
                    label: 'Continuar',
                    onPressed: !c.dev || c.busy
                        ? null
                        : () => setState(() => _reviewing = true),
                  ),
                if (_reviewing && p == null) ...[
                  const Text(
                    'Confirma el pago de prueba para enviar la operación al proveedor configurado.',
                  ),
                  AsyncButton(
                    label: 'Pagar ${displayMoney(currency, amount)}',
                    processingLabel: 'Procesando pago...',
                    onPressed: c.startExtensionPayment,
                  ),
                  SecondaryButton(
                    label: 'Revisar datos',
                    onPressed: c.busy
                        ? null
                        : () => setState(() => _reviewing = false),
                  ),
                ],
                if (p != null) ...[
                  Text(switch (p.status) {
                    PaymentStatus.approved => 'Pago aprobado. Confirmando la extensión en el estacionamiento...',
                    PaymentStatus.declined =>
                      'Pago rechazado. El tiempo no se amplió.',
                    PaymentStatus.failed =>
                      'No se completó el pago. El tiempo no se amplió.',
                    _ => 'Pago pendiente de confirmación. El tiempo aún no se amplió.',
                  }),
                  if (p.waiting)
                    AsyncButton(
                      label: 'Comprobar resultado',
                      onPressed: c.checkExtension,
                    ),
                ],
                if (c.dev)
                  const Text(
                    'Pago de prueba DEV. Sin transacción bancaria real.',
                    textAlign: TextAlign.center,
                  ),
              ],
              if (c.busy) const LinearProgressIndicator(),
              const SizedBox(height: AppSpace.md),
              if (!done && !c.busy)
                SecondaryButton(
                  label: 'Volver',
                  onPressed: () => Navigator.pop(context),
                ),
            ],
          ),
        ),
      );
    },
  );
}
