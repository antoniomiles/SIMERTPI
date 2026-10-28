import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

class InspectorQrScanner extends StatefulWidget {
  const InspectorQrScanner({super.key, required this.onDetected});

  final ValueChanged<String> onDetected;

  @override
  State<InspectorQrScanner> createState() => _InspectorQrScannerState();
}

class _InspectorQrScannerState extends State<InspectorQrScanner> {
  late final MobileScannerController _controller = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
    returnImage: false,
    detectionSpeed: DetectionSpeed.noDuplicates,
  );
  bool _completed = false;

  @override
  void dispose() {
    unawaited(_controller.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      const Text('Enfoque el código QR del espacio. No se guarda una imagen.'),
      const SizedBox(height: 12),
      SizedBox(
        height: 320,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: MobileScanner(
            controller: _controller,
            onDetect: (capture) {
              if (_completed) return;
              final value = capture.barcodes
                  .map((barcode) => barcode.rawValue?.trim())
                  .whereType<String>()
                  .firstWhere((value) => value.isNotEmpty, orElse: () => '');
              if (value.isEmpty) return;
              _completed = true;
              widget.onDetected(value);
            },
            onDetectError: (_, _) {
              // Camera/native errors are intentionally not logged; the scanner
              // errorBuilder provides a safe recovery message.
            },
            errorBuilder: (context, error) => Center(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Text(
                  error.errorCode == MobileScannerErrorCode.permissionDenied
                      ? 'Permita el acceso a la cámara para leer el QR. También puede cerrarlo y escribir el código.'
                      : 'No se pudo abrir la cámara. Cierre esta ventana e ingrese el código QR manualmente.',
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          ),
        ),
      ),
    ],
  );
}
