import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../core/errors/app_failure.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/operational_ui.dart';
import 'activity_service.dart';
import 'activity_controller.dart';

String activityDate(Object? value) {
  final date = DateTime.tryParse(value?.toString() ?? '')?.toLocal();
  if (date == null) return 'No disponible';
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(date.day)}/${two(date.month)}/${date.year} ${two(date.hour)}:${two(date.minute)}';
}

String historyStatus(Object? value) =>
    value == 'COMPLETED' ? 'Finalizado' : 'Cancelado';
String paidLabel(Object? value) {
  final amounts = value is List ? value : const [];
  if (amounts.isEmpty) return 'Sin pagos aprobados';
  return amounts
      .map(
        (row) =>
            displayMoney(row['currency'] as String?, row['amount'] as String?),
      )
      .join(' · ');
}

class ActivityListPage extends StatefulWidget {
  const ActivityListPage({
    super.key,
    required this.resource,
    this.gateway,
    this.onRead,
    this.refreshToken = 0,
  });
  final String resource;
  final ActivityGateway? gateway;
  final VoidCallback? onRead;
  final int refreshToken;
  @override
  State<ActivityListPage> createState() => _ActivityListPageState();
}

class _ActivityListPageState extends State<ActivityListPage>
    with WidgetsBindingObserver {
  ActivityController? controller;
  bool opening = false;
  bool get history => widget.resource == 'history';
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    controller ??= ActivityController(
      widget.gateway ?? ActivityService(AppScope.of(context).api),
      widget.resource,
    )..load();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didUpdateWidget(ActivityListPage old) {
    super.didUpdateWidget(old);
    if (old.refreshToken != widget.refreshToken) controller?.load();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) controller?.load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    controller?.dispose();
    super.dispose();
  }

  Future<void> open(CitizenData row) async {
    if (opening) return;
    opening = true;
    try {
      final data = await controller!.open(row['id'] as String);
      if (!mounted) return;
      widget.onRead?.call();
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => ActivityDetailPage(history: history, data: data),
        ),
      );
    } catch (e) {
      if (mounted) {
        AppSnackbar.show(
          context,
          e is AppFailure
              ? e.message
              : const AppFailure(FailureKind.unknown).message,
        );
      }
    } finally {
      opening = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(history ? 'Historial' : 'Notificaciones')),
    body: SafeArea(
      child: ListenableBuilder(
        listenable: controller!,
        builder: (context, _) {
          final c = controller!;
          return RefreshIndicator(
            onRefresh: () => c.load(),
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              children: [
                if (c.loading)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.all(24),
                      child: CircularProgressIndicator(),
                    ),
                  )
                else if (c.items.isEmpty && c.error == null)
                  EmptyState(
                    message: history
                        ? 'Aún no tienes estacionamientos finalizados.'
                        : 'No tienes notificaciones.',
                  ),
                if (c.error != null)
                  ErrorState(
                    message: c.error!,
                    onRetry: () => c.load(more: c.items.isNotEmpty),
                  ),
                if (!c.loading)
                  for (final row in c.items)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: OperationalCard(
                        onTap: () => open(row),
                        child: Semantics(
                          button: true,
                          label: history
                              ? 'Ver estacionamiento'
                              : '${row['readAt'] == null ? 'No leída' : 'Leída'}. ${row['title']}',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: history
                                ? [
                                    Text(
                                      activityDate(row['startedAt']),
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodySmall,
                                    ),
                                    Text(
                                      row['plate'] as String? ?? '',
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium,
                                    ),
                                    Text(
                                      '${row['street']} · ${row['spaceCode']}',
                                    ),
                                    Text(
                                      '${row['contractedMinutes']} min contratados',
                                    ),
                                    Text(paidLabel(row['paidAmounts'])),
                                    Text(historyStatus(row['status'])),
                                  ]
                                : [
                                    Row(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Icon(
                                          row['readAt'] == null
                                              ? Icons.mark_email_unread_outlined
                                              : Icons.drafts_outlined,
                                        ),
                                        const SizedBox(width: 8),
                                        Expanded(
                                          child: Text(
                                            row['title'] as String? ?? '',
                                            style: Theme.of(context)
                                                .textTheme
                                                .titleMedium,
                                          ),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 8),
                                    Text(row['message'] as String? ?? ''),
                                    const SizedBox(height: 8),
                                    Text(
                                      activityDate(row['createdAt']),
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodySmall,
                                    ),
                                    Text(
                                      row['readAt'] == null
                                          ? 'No leída'
                                          : 'Leída',
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodySmall,
                                    ),
                                  ],
                          ),
                        ),
                      ),
                    ),
                if (c.loadingMore)
                  const Center(child: CircularProgressIndicator())
                else if (c.hasMore && !c.loading)
                  OutlinedButton(
                    onPressed: () => c.load(more: true),
                    child: const Text('Cargar más'),
                  ),
              ],
            ),
          );
        },
      ),
    ),
  );
}

class ActivityDetailPage extends StatelessWidget {
  const ActivityDetailPage({
    super.key,
    required this.history,
    required this.data,
  });
  final bool history;
  final CitizenData data;
  @override
  Widget build(BuildContext context) {
    final row = history ? ActivityService.object(data['session']) : data;
    return Scaffold(
      appBar: AppBar(
        title: Text(history ? 'Detalle del estacionamiento' : 'Notificación'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: OperationalCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: history
                  ? [
                      SummaryRow('Placa', row['plate'] as String? ?? ''),
                      SummaryRow('Zona', row['zone'] as String? ?? ''),
                      SummaryRow('Calle', row['street'] as String? ?? ''),
                      SummaryRow('Espacio', row['spaceCode'] as String? ?? ''),
                      SummaryRow('Inicio', activityDate(row['startedAt'])),
                      SummaryRow(
                        'Fin contratado',
                        activityDate(row['expectedEndAt']),
                      ),
                      SummaryRow('Cierre', activityDate(row['endedAt'])),
                      SummaryRow(
                        'Tiempo contratado',
                        '${row['contractedMinutes']} min',
                      ),
                      if (row['occupiedMinutes'] != null)
                        SummaryRow(
                          'Tiempo de ocupación',
                          '${row['occupiedMinutes']} min',
                        ),
                      const Divider(),
                      Text(
                        'Extensiones aprobadas',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      if ((data['extensions'] as List).isEmpty)
                        const Text('Sin extensiones aprobadas'),
                      for (final extension in data['extensions'] as List)
                        SummaryRow(
                          '${extension['minutes']} min',
                          activityDate(extension['approvedAt']),
                        ),
                      const Divider(),
                      SummaryRow(
                        'Importe pagado',
                        paidLabel(row['paidAmounts']),
                      ),
                      if ((row['paidAmounts'] as List).isNotEmpty)
                        SummaryRow(
                          'Moneda',
                          (row['paidAmounts'] as List)
                              .map((p) => p['currency'])
                              .join(' / '),
                        ),
                      SummaryRow('Estado', historyStatus(row['status'])),
                    ]
                  : [
                      Text(
                        row['title'] as String? ?? '',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 16),
                      Text(row['message'] as String? ?? ''),
                      const SizedBox(height: 16),
                      Text(activityDate(row['createdAt'])),
                    ],
            ),
          ),
        ),
      ),
    );
  }
}
