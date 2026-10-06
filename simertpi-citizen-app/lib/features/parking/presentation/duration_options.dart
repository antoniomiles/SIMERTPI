import 'package:flutter/material.dart';

import '../../../core/widgets/operational_ui.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/parking_contract.dart';

class DurationOptions extends StatefulWidget {
  const DurationOptions({
    super.key,
    required this.load,
    required this.selected,
    required this.onSelected,
    this.enabled = true,
  });
  final Future<List<ParkingRules>> Function() load;
  final int? selected;
  final ValueChanged<ParkingRules> onSelected;
  final bool enabled;
  @override
  State<DurationOptions> createState() => _DurationOptionsState();
}

class _DurationOptionsState extends State<DurationOptions> {
  late Future<List<ParkingRules>> options;
  @override
  void initState() {
    super.initState();
    options = widget.load();
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<List<ParkingRules>>(
    future: options,
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return Column(
          children: [
            const InfoCard('No pudimos consultar los tiempos.'),
            TextButton(
              onPressed: () => setState(() => options = widget.load()),
              child: const Text('Reintentar'),
            ),
          ],
        );
      }
      if (!snapshot.hasData) {
        return const Center(child: CircularProgressIndicator());
      }
      final values = snapshot.data!;
      if (values.isEmpty) {
        return const InfoCard(
          'No hay tiempos disponibles con las condiciones actuales.',
        );
      }
      return Column(
        children: [
          for (final q in values)
            OperationalCard(
              color: widget.selected == q.minutes
                  ? AppColors.parkingHint
                  : AppColors.surface,
              border: widget.selected == q.minutes
                  ? AppColors.action
                  : AppColors.outline,
              onTap: widget.enabled ? () => widget.onSelected(q) : null,
              child: Semantics(
                selected: widget.selected == q.minutes,
                button: true,
                label:
                    '${durationLabel(q.minutes!)} ${displayMoney(q.currency, q.amount)}',
                child: Row(
                  children: [
                    Icon(
                      widget.selected == q.minutes
                          ? Icons.radio_button_checked
                          : Icons.radio_button_unchecked,
                      color: widget.selected == q.minutes
                          ? AppColors.action
                          : AppColors.muted,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        durationLabel(q.minutes!),
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                    ),
                    Text(
                      displayMoney(q.currency, q.amount),
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ],
                ),
              ),
            ),
        ],
      );
    },
  );
}
