import 'parking_map_marker.dart';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../../../core/config/map_config.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/parking_catalog.dart';

class ParkingMap extends StatefulWidget {
  const ParkingMap({
    super.key,
    this.tileProvider,
    required this.spaces,
    required this.config,
    required this.catalog,
    required this.onSelect,
    required this.enabled,
  });
  final TileProvider? tileProvider;
  final List<CatalogSpace> spaces;
  final MapConfig config;
  final ParkingCatalog catalog;
  final ValueChanged<CatalogSpace> onSelect;
  final bool enabled;
  @override
  State<ParkingMap> createState() => _ParkingMapState();
}

class _ParkingMapState extends State<ParkingMap> {
  bool _tileFailure = false;
  @override
  void didUpdateWidget(ParkingMap oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.config.tileUrl != widget.config.tileUrl) _tileFailure = false;
  }

  @override
  Widget build(BuildContext context) {
    // Only show citizen-discoverable operational states on the map. The list
    // remains complete and continues to identify occupied spaces.
    final located = widget.spaces
        .where(
          (s) =>
              s.hasCoordinates &&
              const {'AVAILABLE', 'ENDING_SOON'}.contains(s.operationalStatus),
        )
        .toList();
    if (!widget.config.configured) {
      return const Text(
        'Vista de mapa no disponible. Puedes elegir un espacio en la lista.',
      );
    }
    final hasDiscoverable = widget.spaces.any(
      (s) => const {'AVAILABLE', 'ENDING_SOON'}.contains(s.operationalStatus),
    );
    if (!hasDiscoverable) {
      return const Text(
        'No hay espacios disponibles para mostrar en el mapa. Puedes consultar el listado.',
      );
    }
    if (located.isEmpty) {
      return const Text(
        'Estos espacios no tienen una ubicación cartográfica informada.',
      );
    }
    final points = located
        .map((s) => LatLng(s.latitude!, s.longitude!))
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_tileFailure)
          Semantics(
            liveRegion: true,
            child: Text(
              'No pudimos cargar la cartografía. Usa la lista de espacios.',
            ),
          ),
        Container(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppSize.radius),
            border: Border.all(color: AppColors.outline),
            boxShadow: const [
              BoxShadow(
                color: Color(0x120D2745),
                blurRadius: 14,
                offset: Offset(0, 5),
              ),
            ],
          ),
          clipBehavior: Clip.antiAlias,
          child: SizedBox(
            height: AppSize.mapViewport,
            child: Stack(
              children: [
                FlutterMap(
                  key: ValueKey(
                    located
                        .map((s) => '${s.id}:${s.latitude}:${s.longitude}')
                        .join('|'),
                  ),
                  options: MapOptions(
                    initialCenter: points.first,
                    initialZoom: 16,
                    initialCameraFit: points.length > 1
                        ? CameraFit.coordinates(
                            coordinates: points,
                            padding: const EdgeInsets.all(AppSpace.xl),
                            maxZoom: 17,
                          )
                        : null,
                  ),
                  children: [
                    TileLayer(
                      tileProvider: widget.tileProvider,
                      urlTemplate: widget.config.tileUrl,
                      userAgentPackageName:
                          'ec.gob.simertpi.simertpi_citizen_app',
                      errorTileCallback: (_, _, _) {
                        if (!_tileFailure) {
                          WidgetsBinding.instance.addPostFrameCallback((_) {
                            if (mounted) setState(() => _tileFailure = true);
                          });
                        }
                      },
                    ),
                    MarkerLayer(
                      markers: [
                        for (final space in located)
                          Marker(
                            point: LatLng(space.latitude!, space.longitude!),
                            width: AppSize.touchTarget,
                            height: 56,
                            child: ParkingMapMarker(
                              space: space,
                              onTap: widget.enabled
                                  ? () => widget.onSelect(space)
                                  : null,
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
                Positioned(
                  left: AppSpace.sm,
                  top: AppSpace.sm,
                  child: _MapLegend(),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  child: ColoredBox(
                    color: AppColors.surface,
                    child: Padding(
                      padding: const EdgeInsets.all(AppSpace.sm),
                      child: Semantics(
                        label: 'Atribución cartográfica',
                        child: Text(
                          widget.config.attribution,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _MapLegend extends StatelessWidget {
  @override
  Widget build(BuildContext context) => Material(
    color: AppColors.surface.withValues(alpha: 0.96),
    elevation: 2,
    borderRadius: BorderRadius.circular(12),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      child: Wrap(
        spacing: 12,
        runSpacing: 6,
        children: const [
          _LegendItem(color: AppColors.available, label: 'Disponible'),
          _LegendItem(
            color: AppColors.endingSoonMarker,
            label: 'Próximo a finalizar',
          ),
        ],
      ),
    ),
  );
}

class _LegendItem extends StatelessWidget {
  const _LegendItem({required this.color, required this.label});
  final Color color;
  final String label;
  @override
  Widget build(BuildContext context) => Semantics(
    label: label,
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 12,
          height: 12,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 5),
        Text(label, style: Theme.of(context).textTheme.labelSmall),
      ],
    ),
  );
}
