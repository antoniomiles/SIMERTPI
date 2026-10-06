import '../../vehicles/presentation/vehicle_plate.dart';
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
import '../../parking/data/parking_contract.dart';
import '../../vehicles/data/vehicle_service.dart';
import '../data/payment_contract.dart';
import '../data/payment_intent_store.dart';
import '../state/payments_controller.dart';

class PaymentsPage extends StatefulWidget {
  const PaymentsPage({super.key, this.sessionId, this.controller});
  final String? sessionId;
  final PaymentsController? controller;
  @override
  State<PaymentsPage> createState() => _PaymentsPageState();
}

class _PaymentsPageState extends State<PaymentsPage> {
  PaymentsController? _controller;
  bool _reviewing = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_controller != null) return;
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
    if (widget.controller == null) _controller?.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    await _controller!.confirm();
    if (mounted && _controller!.phase == PaymentPhase.ready) {
      setState(() => _reviewing = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _controller!,
    builder: (context, _) {
      final c = _controller!;
      final q = c.quote;
      final p = c.payment;
      return PopScope(
        canPop: !c.busy,
        child: AppPage(
          title: c.phase == PaymentPhase.result
              ? 'Resultado de pago'
              : _reviewing
              ? 'Confirmar pago'
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
                                onPressed: c.busy ? null : () => c.select(s.id),
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
                  const Center(child: CircularProgressIndicator()),
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
                  const Text(
                    'El estacionamiento se iniciará únicamente cuando el pago sea confirmado.',
                  ),
                ] else if (c.phase == PaymentPhase.result && c.activated) ...[
                  const Icon(
                    Icons.check_circle,
                    color: AppColors.available,
                    size: 64,
                    semanticLabel: 'Estacionamiento confirmado',
                  ),
                  const SizedBox(height: AppSpace.lg),
                  Text(
                    'Vehículo estacionado correctamente',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: AppSpace.lg),
                  Text('Espacio: ${c.spaceCode ?? "Espacio confirmado"}'),
                  Text(
                    'Vehículo: ${c.vehicle == null ? "Vehículo confirmado" : visualPlate(c.vehicle!.plate)}',
                  ),
                  Text(
                    'Tiempo: ${c.session!.expectedEndAt.difference(c.session!.startedAt).inMinutes} min',
                  ),
                  if (p?.provider == 'SANDBOX_STUB')
                    const Text(
                      'Pago de prueba DEV. Sin transacción bancaria real.',
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
                          Text('Referencia SIMERTPI: ${p.id}'),
                          Text('Solicitud: ${p.sessionId}'),
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
                  Text(
                    _reviewing
                        ? 'Revisa el resumen antes de confirmar.'
                        : 'Selecciona cómo tramitar el pago.',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: AppSpace.lg),
                  if (!_reviewing) ...[
                    if (c.dev)
                      AppCard(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            const Text('Solicitud de pago DEV'),
                            const Text(
                              'Método técnico de prueba. Requiere un proveedor configurado en el backend; no es un pago bancario.',
                            ),
                            const SizedBox(height: AppSpace.sm),
                            Semantics(
                              selected: c.methodSelected,
                              child: SecondaryButton(
                                label: c.methodSelected
                                    ? 'Método DEV seleccionado'
                                    : 'Seleccionar método DEV',
                                onPressed: c.busy ? null : c.chooseMethod,
                              ),
                            ),
                          ],
                        ),
                      )
                    else
                      const EmptyState(
                        message: 'Los métodos de pago aún no están habilitados para este ambiente.',
                      ),
                    const SizedBox(height: AppSpace.lg),
                  ],
                  AppCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text('Zona: ${c.zoneName ?? ""}'),
                        Text('Espacio: ${c.spaceCode}'),
                        Text('Vehículo: ${c.vehicle!.plate}'),
                        Text('Duración: ${q.minutes} min'),
                        const SizedBox(height: AppSpace.md),
                        Text(
                          'Importe estimado: ${q.currency} ${q.amount}',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const Text(
                          'Cotización del sistema. El backend valida el importe definitivo al procesar el pago.',
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpace.lg),
                  if (_reviewing) ...[
                    const Text(
                      'Confirmar envía una solicitud de pago. El estacionamiento se activa únicamente si el sistema confirma la aprobación.',
                    ),
                    const SizedBox(height: AppSpace.md),
                    if (c.canConfirm || c.phase == PaymentPhase.processing)
                      AsyncButton(
                        label: 'Pagar ${q.currency} ${q.amount}',
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
