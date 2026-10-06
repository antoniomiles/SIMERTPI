import 'package:flutter/material.dart';

import '../theme/app_tokens.dart';
import 'success_celebration.dart';

class OperationalCard extends StatelessWidget {
  const OperationalCard({
    super.key,
    required this.child,
    this.color = AppColors.surface,
    this.border = AppColors.outline,
    this.onTap,
  });
  final Widget child;
  final Color color, border;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Material(
      color: color,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: border, width: .7),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(padding: const EdgeInsets.all(12), child: child),
      ),
    ),
  );
}

class InfoCard extends StatelessWidget {
  const InfoCard(this.text, {super.key, this.icon = Icons.info_outline});
  final String text;
  final IconData icon;
  @override
  Widget build(BuildContext context) => OperationalCard(
    color: AppColors.parkingHint,
    border: Colors.transparent,
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: AppColors.primary),
        const SizedBox(width: 8),
        Expanded(
          child: Text(text, style: Theme.of(context).textTheme.bodyMedium),
        ),
      ],
    ),
  );
}

class SummaryRow extends StatelessWidget {
  const SummaryRow(this.label, this.value, {super.key, this.important = false});
  final String label, value;
  final bool important;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: Text(label)),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.end,
            style: important
                ? Theme.of(context).textTheme.titleLarge
                : Theme.of(context).textTheme.titleSmall,
          ),
        ),
      ],
    ),
  );
}

String durationLabel(int minutes) {
  if (minutes < 60) return '$minutes minutos';
  final hours = minutes ~/ 60;
  final rest = minutes % 60;
  return '$hours ${hours == 1 ? "hora" : "horas"}${rest == 0 ? "" : " $rest min"}';
}

String displayMoney(String? currency, String? amount) => currency == 'USD'
    ? '\$${(amount ?? "").replaceAll(".", ",")}'
    : '${currency ?? ""} ${amount ?? ""}';

class SuccessMark extends StatelessWidget {
  const SuccessMark({super.key});
  @override
  Widget build(BuildContext context) => const SuccessCelebration(
    child: Center(
      child: Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: CircleAvatar(
          radius: 40,
          backgroundColor: AppColors.available,
          child: Icon(
            Icons.check_rounded,
            size: 56,
            color: Colors.white,
            semanticLabel: 'Operación confirmada',
          ),
        ),
      ),
    ),
  );
}
