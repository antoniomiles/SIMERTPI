import 'package:flutter/material.dart';

import '../../../core/theme/app_tokens.dart';
import '../data/parking_catalog.dart';

String spaceStatus(CatalogSpace s) => switch (s.operationalStatus) {
  'AVAILABLE' => 'Disponible',
  'ENDING_SOON' => 'Próximo a finalizar',
  'OCCUPIED' => 'Ocupado',
  'DISABLED' => 'No habilitado',
  _ => 'No disponible',
};
Color spaceColor(CatalogSpace s) => switch (s.operationalStatus) {
  'AVAILABLE' => AppColors.available,
  'ENDING_SOON' => AppColors.endingSoon,
  'OCCUPIED' => AppColors.occupied,
  _ => AppColors.muted,
};

class SpaceStatus extends StatelessWidget {
  const SpaceStatus({
    super.key,
    required this.space,
    this.showRemaining = true,
  });
  final CatalogSpace space;
  final bool showRemaining;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: switch (space.operationalStatus) {
            'AVAILABLE' => AppColors.successSurface,
            'ENDING_SOON' => AppColors.warningSurface,
            'OCCUPIED' => AppColors.dangerSurface,
            _ => AppColors.input,
          },
          borderRadius: BorderRadius.circular(6),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              space.operationalStatus == 'AVAILABLE'
                  ? Icons.check_circle
                  : Icons.info_outline,
              color: spaceColor(space),
              size: 16,
            ),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                spaceStatus(space),
                style: Theme.of(context).textTheme.labelMedium
                    ?.copyWith(color: spaceColor(space)),
              ),
            ),
          ],
        ),
      ),
      if (showRemaining && space.remainingSeconds != null)
        Text(
          'Tiempo contratado restante: ${(space.remainingSeconds! + 59) ~/ 60} min',
        ),
    ],
  );
}
