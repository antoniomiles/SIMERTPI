import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../app/router/app_router.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../data/parking_catalog.dart';
import '../state/discovery_controller.dart';
import 'qr_camera.dart';

typedef QrCameraBuilder = Widget Function(
  ValueChanged<String> onRead,
  VoidCallback retry,
);

class QrScanPage extends StatefulWidget {
  const QrScanPage({super.key, this.controller, this.cameraBuilder});
  final DiscoveryController? controller;
  final QrCameraBuilder? cameraBuilder;
  @override
  State<QrScanPage> createState() => _QrScanPageState();
}

class _QrScanPageState extends State<QrScanPage> {
  DiscoveryController? _controller;
  bool _reading = false;
  int _cameraGeneration = 0;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _controller ??=
        widget.controller ??
        DiscoveryController(ParkingCatalogService(AppScope.of(context).api));
  }

  @override
  void dispose() {
    if (widget.controller == null) _controller?.dispose();
    super.dispose();
  }

  void _retry() => setState(() {
    _reading = false;
    _cameraGeneration++;
  });
  Future<void> _read(String value) async {
    if (_reading) return;
    setState(() => _reading = true);
    final c = _controller!;
    await c.resolve(value, qr: true);
    if (mounted && c.identified != null) {
      await Navigator.pushNamed(
        context,
        AppRoute.space.path,
        arguments: c.identified,
      );
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _controller!,
    builder: (context, _) {
      final c = _controller!;
      return AppPage(
        title: 'Escanear QR',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Apunta la cámara al QR del espacio. Consultaremos su información antes de seleccionarlo.',
            ),
            const SizedBox(height: AppSpace.lg),
            if (!_reading)
              Semantics(
                label: 'Área de escaneo QR',
                child: SizedBox(
                  height: AppSize.mapViewport,
                  child: KeyedSubtree(
                    key: ValueKey(_cameraGeneration),
                    child:
                        widget.cameraBuilder?.call(_read, _retry) ??
                        QrCamera(onRead: _read, onRetry: _retry),
                  ),
                ),
              )
            else ...[
              if (c.resolving) const LinearProgressIndicator(),
              Semantics(
                liveRegion: true,
                child: Text(
                  c.resolving
                      ? 'Consultando el espacio…'
                      : c.message ?? 'Lectura completada.',
                ),
              ),
              const SizedBox(height: AppSpace.md),
              SecondaryButton(
                label: 'Volver a escanear',
                onPressed: c.resolving ? null : _retry,
              ),
            ],
            const SizedBox(height: AppSpace.lg),
            SecondaryButton(
              label: 'Buscar por código',
              onPressed: c.resolving
                  ? null
                  : () => Navigator.pushNamed(context, AppRoute.discovery.path),
            ),
          ],
        ),
      );
    },
  );
}
