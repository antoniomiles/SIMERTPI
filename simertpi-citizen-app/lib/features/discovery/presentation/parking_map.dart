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
    final located = widget.spaces.where((s) => s.hasCoordinates).toList();
    if (!widget.config.configured) {
      return const Text(
        'Vista de mapa no disponible. Puedes elegir un espacio en la lista.',
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
        SizedBox(
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
                          height: AppSize.touchTarget,
                          child: Semantics(
                            label:
                                'Espacio ${space.number}, ${widget.catalog.selectable(space) ? 'habilitado' : 'no habilitado'}',
                            child: IconButton.filled(
                              tooltip: 'Espacio ${space.number}',
                              onPressed: widget.enabled
                                  ? () => widget.onSelect(space)
                                  : null,
                              style: IconButton.styleFrom(
                                backgroundColor:
                                    widget.catalog.selectable(space)
                                    ? AppColors.primary
                                    : AppColors.input,
                                foregroundColor:
                                    widget.catalog.selectable(space)
                                    ? AppColors.surface
                                    : AppColors.muted,
                              ),
                              icon: Icon(
                                widget.catalog.selectable(space)
                                    ? Icons.local_parking
                                    : Icons.block,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ],
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
      ],
    );
  }
}
