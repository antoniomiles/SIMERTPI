import 'package:flutter/material.dart';

import '../../../app/router/app_router.dart';

import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/parking_catalog.dart';

class SpaceSelectionPage extends StatefulWidget {
  const SpaceSelectionPage({super.key, required this.result});
  final IdentifiedSpace result;
  @override
  State<SpaceSelectionPage> createState() => _SpaceSelectionPageState();
}

class _SpaceSelectionPageState extends State<SpaceSelectionPage> {
  bool _selected = false;
  @override
  Widget build(BuildContext context) {
    final result = widget.result;
    return AppPage(
      title: _selected ? 'Espacio seleccionado' : 'Detalle del espacio',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'Espacio ${result.space.number}',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          const SizedBox(height: AppSpace.md),
          Text(result.space.code),
          Text(result.catalog.streetOf(result.space)?.name ?? ''),
          Text(result.catalog.zoneOf(result.space)?.name ?? ''),
          const SizedBox(height: AppSpace.lg),
          Text(
            result.selectable
                ? 'Ubicación habilitada'
                : 'Ubicación no habilitada',
          ),
          const SizedBox(height: AppSpace.md),
          const Text(
            'La disponibilidad no está informada. Esta selección no reserva el espacio ni inicia un estacionamiento.',
          ),
          const SizedBox(height: AppSpace.lg),
          if (!_selected)
            PrimaryButton(
              label: 'Seleccionar espacio',
              onPressed: result.selectable
                  ? () => setState(() => _selected = true)
                  : null,
            ),
          if (_selected && result.selectable)
            PrimaryButton(
              label: 'Continuar con este espacio',
              onPressed: () => Navigator.pushNamed(
                context,
                AppRoute.parking.path,
                arguments: result,
              ),
            ),
          if (_selected)
            Semantics(
              liveRegion: true,
              child: Text(
                'Espacio seleccionado. Consulta las reglas y elige tu vehículo para continuar.',
              ),
            ),
        ],
      ),
    );
  }
}
