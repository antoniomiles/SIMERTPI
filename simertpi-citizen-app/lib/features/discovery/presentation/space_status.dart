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
  const SpaceStatus({super.key, required this.space});
  final CatalogSpace space;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Icon(
            space.operationalStatus == 'AVAILABLE'
                ? Icons.check_circle
                : Icons.info,
            color: spaceColor(space),
          ),
          const SizedBox(width: AppSpace.sm),
          Expanded(
            child: Text(
              spaceStatus(space),
              style: TextStyle(
                color: spaceColor(space),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
      if (space.remainingSeconds != null)
        Text(
          'Tiempo contratado restante: ${(space.remainingSeconds! + 59) ~/ 60} min',
        ),
    ],
  );
}
