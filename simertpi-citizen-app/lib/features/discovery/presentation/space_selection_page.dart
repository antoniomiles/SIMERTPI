import 'package:flutter/material.dart';

import '../../../app/router/app_router.dart';
import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/parking_catalog.dart';
import 'space_status.dart';
import '../../../core/widgets/operational_ui.dart';

class SpaceSelectionPage extends StatelessWidget {
  const SpaceSelectionPage({super.key, required this.result});
  final IdentifiedSpace result;
  @override
  Widget build(BuildContext context) => AppPage(
    showNavigation: true,
    title: 'Detalle del espacio',
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OperationalCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                result.space.code,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(result.catalog.streetOf(result.space)?.name ?? ''),
              const SizedBox(height: AppSpace.lg),
              SpaceStatus(space: result.space, showRemaining: false),
              if (result.space.remainingSeconds != null) ...[
                const SizedBox(height: AppSpace.md),
                const Text("Tiempo contratado restante"),
                Text(
                  "${(result.space.remainingSeconds! + 59) ~/ 60} minutos",
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ],
              if (!result.selectable &&
                  result.space.operationalStatus != 'DISABLED')
                const Text('Sigue ocupado'),
            ],
          ),
        ),
        InfoCard(
          result.selectable
              ? 'La disponibilidad se verificará nuevamente al continuar.'
              : result.space.operationalStatus == 'DISABLED'
              ? 'Este espacio no está habilitado actualmente.'
              : 'Este espacio sigue ocupado. Aún no puedes seleccionarlo.',
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
