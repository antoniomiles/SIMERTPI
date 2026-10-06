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
import 'space_status.dart';

class DiscoveryPage extends StatefulWidget {
  const DiscoveryPage({
    super.key,
    this.controller,
    this.mapConfig,
    this.initialMap = false,
    this.refreshToken = 0,
  });
  final DiscoveryController? controller;
  final MapConfig? mapConfig;
  final bool initialMap;
  final int refreshToken;
  @override
  State<DiscoveryPage> createState() => _DiscoveryPageState();
}

class _DiscoveryPageState extends State<DiscoveryPage>
    with WidgetsBindingObserver {
  DiscoveryController? _controller;
  final _code = TextEditingController();
  MapConfig _map = const MapConfig();
  late bool map;
  @override
  void initState() {
    super.initState();
    map = widget.initialMap;
    WidgetsBinding.instance.addObserver(this);
  }

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
  void didUpdateWidget(DiscoveryPage old) {
    super.didUpdateWidget(old);
    if (old.refreshToken != widget.refreshToken) _controller?.load();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _controller?.load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _code.dispose();
    if (widget.controller == null) _controller?.dispose();
    super.dispose();
  }

  Future<void> _identify(String value) async {
    final c = _controller!;
    if (c.resolving) return;
    await c.resolve(value, qr: false);
    if (!mounted || c.identified == null) return;
    final result = c.identified!;
    var select = false;
    if (map) {
      final action = await showModalBottomSheet<String>(
        context: context,
        isScrollControlled: true,
        builder: (context) => SafeArea(
          child: SingleChildScrollView(
            padding: AppSpace.page,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  result.space.code,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                Text(result.catalog.streetOf(result.space)?.name ?? ''),
                SpaceStatus(space: result.space),
                PrimaryButton(
                  label: result.selectable
                      ? 'Seleccionar espacio'
                      : 'No disponible',
                  onPressed: result.selectable
                      ? () => Navigator.pop(context, 'select')
                      : null,
                ),
                SecondaryButton(
                  label: 'Ver detalle',
                  onPressed: () => Navigator.pop(context, 'detail'),
                ),
              ],
            ),
          ),
        ),
      );
      if (action == null) return;
      select = action == 'select';
    }
    if (!mounted) return;
    await Navigator.pushNamed(
      context,
      select && result.selectable ? AppRoute.parking.path : AppRoute.space.path,
      arguments: result,
    );
    await c.load();
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
            Wrap(
              spacing: AppSpace.sm,
              children: [
                TextButton.icon(
                  onPressed: () => setState(() => map = false),
                  icon: const Icon(Icons.list),
                  label: const Text('Lista'),
                ),
                TextButton.icon(
                  onPressed: () => setState(() => map = true),
                  icon: const Icon(Icons.map),
                  label: const Text('Mapa'),
                ),
              ],
            ),
            AppTextField(
              label: 'Código del espacio',
              controller: _code,
              enabled: !c.resolving,
            ),
            const SizedBox(height: AppSpace.sm),
            AsyncButton(
              label: 'Buscar por código',
              onPressed: () => _identify(_code.text),
            ),
            const SizedBox(height: AppSpace.md),
            if (c.phase == DiscoveryPhase.loading ||
                c.phase == DiscoveryPhase.initial)
              const SkeletonList(count: 2)
            else if (c.phase == DiscoveryPhase.error)
              ErrorState(message: c.message!, onRetry: c.load)
            else ...[
              if (c.refreshing || c.resolving) const LinearProgressIndicator(),
              if (c.message != null) ...[
                ErrorState(message: c.message!, onRetry: c.load),
                if (c.catalog != null)
                  const Text(
                    'La disponibilidad no está confirmada. Actualiza antes de seleccionar.',
                  ),
              ],
              if (c.phase == DiscoveryPhase.empty)
                const EmptyState(
                  message: 'No hay zonas ni espacios registrados.',
                )
              else ...[
                if (c.zoneId != null)
                  TextButton.icon(
                    onPressed: () {
                      c.selectZone(null);
                      c.load();
                    },
                    icon: const Icon(Icons.arrow_back),
                    label: const Text('Todas las zonas'),
                  ),
                if (c.zoneId != null)
                  Text(
                    c.catalog!.zones.firstWhere((z) => z.id == c.zoneId).name,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                if (map)
                  ParkingMap(
                    spaces: c.spaces,
                    catalog: c.catalog!,
                    config: _map,
                    enabled: !c.resolving && !c.refreshing && c.message == null,
                    onSelect: (s) => _identify(s.code),
                  )
                else if (c.zoneId == null) ...[
                  Text('Zonas', style: Theme.of(context).textTheme.titleLarge),
                  for (final zone in c.catalog!.zones)
                    Padding(
                      padding: const EdgeInsets.only(bottom: AppSpace.md),
                      child: AppCard(
                        child: ListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(zone.name),
                          subtitle: Text(
                            '${c.catalog!.inZone(zone.id).length} espacios',
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () {
                            c.selectZone(zone.id);
                            c.load();
                          },
                        ),
                      ),
                    ),
                ] else ...[
                  if (c.spaces.isEmpty)
                    const EmptyState(
                      message: 'No hay espacios registrados en esta zona.',
                    ),
                  for (final space in c.spaces)
                    Padding(
                      padding: const EdgeInsets.only(bottom: AppSpace.md),
                      child: AppCard(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text(
                              space.code,
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                            Text(c.catalog!.streetOf(space)?.name ?? ''),
                            SpaceStatus(space: space),
                            SecondaryButton(
                              label: 'Ver espacio ${space.number}',
                              onPressed:
                                  c.resolving ||
                                      c.refreshing ||
                                      c.message != null
                                  ? null
                                  : () => _identify(space.code),
                            ),
                          ],
                        ),
                      ),
                    ),
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
