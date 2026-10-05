import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../app/router/app_router.dart';
import '../../../core/config/map_config.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/widgets/app_skeleton.dart';
import '../data/parking_catalog.dart';
import '../state/discovery_controller.dart';
import 'parking_map.dart';

class DiscoveryPage extends StatefulWidget {
  const DiscoveryPage({super.key, this.controller, this.mapConfig});
  final DiscoveryController? controller;
  final MapConfig? mapConfig;
  @override
  State<DiscoveryPage> createState() => _DiscoveryPageState();
}

class _DiscoveryPageState extends State<DiscoveryPage> {
  DiscoveryController? _controller;
  final _code = TextEditingController();
  MapConfig _map = const MapConfig();
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_controller != null) return;
    _controller =
        widget.controller ??
        DiscoveryController(ParkingCatalogService(AppScope.of(context).api));
    try {
      _map = widget.mapConfig ?? MapConfig.fromDefines();
    } on FormatException {
      _map = const MapConfig();
    }
    _controller!.load();
  }

  @override
  void dispose() {
    _code.dispose();
    if (widget.controller == null) _controller?.dispose();
    super.dispose();
  }

  Future<void> _identify(String code) async {
    final c = _controller!;
    if (c.resolving) return;
    await c.resolve(code, qr: false);
    if (!mounted || c.identified == null) return;
    await Navigator.pushNamed(
      context,
      AppRoute.space.path,
      arguments: c.identified,
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _controller!,
    builder: (context, _) {
      final c = _controller!;
      return AppPage(
        title: 'Buscar estacionamiento',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AppTextField(
              label: 'Código del espacio',
              controller: _code,
              enabled: !c.resolving,
            ),
            const SizedBox(height: AppSpace.md),
            AsyncButton(
              label: 'Buscar por código',
              processingLabel: 'Consultando…',
              onPressed: () => _identify(_code.text),
            ),
            const SizedBox(height: AppSpace.lg),
            if (c.phase == DiscoveryPhase.initial ||
                c.phase == DiscoveryPhase.loading)
              const SkeletonList(count: 2)
            else if (c.phase == DiscoveryPhase.error)
              ErrorState(message: c.message!, onRetry: c.load)
            else ...[
              if (c.refreshing || c.resolving) const LinearProgressIndicator(),
              if (c.message != null)
                ErrorState(message: c.message!, onRetry: c.load),
              if (c.message != null && c.catalog != null)
                const Text(
                  'La información anterior no está actualizada. Vuelve a consultar antes de seleccionar.',
                ),
              if (c.phase == DiscoveryPhase.empty)
                const EmptyState(
                  message: 'No hay zonas ni espacios registrados.',
                )
              else ...[
                Text('Zonas', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: AppSpace.sm),
                Wrap(
                  spacing: AppSpace.sm,
                  runSpacing: AppSpace.sm,
                  children: [
                    ChoiceChip(
                      label: const Text('Todas'),
                      selected: c.zoneId == null,
                      onSelected: (_) => c.selectZone(null),
                    ),
                    for (final zone in c.catalog!.zones)
                      ChoiceChip(
                        label: Text(
                          '${zone.name}${zone.active ? '' : ' · Inactiva'}',
                        ),
                        selected: c.zoneId == zone.id,
                        onSelected: (_) => c.selectZone(zone.id),
                      ),
                  ],
                ),
                const SizedBox(height: AppSpace.lg),
                ParkingMap(
                  spaces: c.spaces,
                  catalog: c.catalog!,
                  config: _map,
                  enabled: !c.resolving && !c.refreshing && c.message == null,
                  onSelect: (space) => _identify(space.code),
                ),
                const SizedBox(height: AppSpace.lg),
                Text('Espacios', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: AppSpace.sm),
                const Text(
                  'Habilitado no significa libre. La disponibilidad no está informada.',
                ),
                const SizedBox(height: AppSpace.md),
                if (c.spaces.isEmpty)
                  const EmptyState(
                    message: 'No hay espacios registrados en esta zona.',
                  ),
                for (final space in c.spaces) ...[
                  AppCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          'Espacio ${space.number}',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        Text(space.code),
                        if (c.catalog!.streetOf(space) != null)
                          Text(c.catalog!.streetOf(space)!.name),
                        Text(
                          c.catalog!.selectable(space)
                              ? 'Ubicación habilitada'
                              : 'Ubicación no habilitada',
                        ),
                        if (!space.hasCoordinates)
                          const Text('Sin ubicación cartográfica informada'),
                        const SizedBox(height: AppSpace.sm),
                        SecondaryButton(
                          label: 'Ver espacio ${space.number}',
                          onPressed:
                              c.resolving || c.refreshing || c.message != null
                              ? null
                              : () => _identify(space.code),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpace.md),
                ],
              ],
              SecondaryButton(
                label: 'Actualizar',
                onPressed: c.refreshing || c.resolving ? null : c.load,
              ),
            ],
          ],
        ),
      );
    },
  );
}
