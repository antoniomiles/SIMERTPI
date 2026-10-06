import 'package:flutter/material.dart';

import '../../../app/router/app_router.dart';
import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/parking_catalog.dart';
import 'space_status.dart';

class SpaceSelectionPage extends StatelessWidget {
  const SpaceSelectionPage({super.key, required this.result});
  final IdentifiedSpace result;
  @override
  Widget build(BuildContext context) => AppPage(
    title: 'Detalle del espacio',
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          result.space.code,
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        Text(result.catalog.streetOf(result.space)?.name ?? ''),
        Text(result.catalog.zoneOf(result.space)?.name ?? ''),
        const SizedBox(height: AppSpace.lg),
        SpaceStatus(space: result.space),
        const SizedBox(height: AppSpace.lg),
        if (!result.selectable)
          Text(
            result.space.operationalStatus == 'DISABLED'
                ? 'Este espacio no está habilitado actualmente.'
                : 'Este espacio sigue ocupado o no está disponible. Aún no puedes seleccionarlo.',
          )
        else
          const Text(
            'La disponibilidad se volverá a verificar al preparar la solicitud.',
          ),
        const SizedBox(height: AppSpace.lg),
        PrimaryButton(
          label: result.selectable ? 'Seleccionar espacio' : 'No disponible',
          onPressed: result.selectable
              ? () => Navigator.pushNamed(
                  context,
                  AppRoute.parking.path,
                  arguments: result,
                )
              : null,
        ),
      ],
    ),
  );
}
