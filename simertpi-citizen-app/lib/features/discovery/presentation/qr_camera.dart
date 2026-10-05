import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../../core/widgets/app_feedback.dart';
import '../../../core/theme/app_tokens.dart';

String cameraErrorMessage(MobileScannerErrorCode code) =>
    code == MobileScannerErrorCode.permissionDenied
    ? 'Permite el acceso a la cámara para escanear. Si el permiso está bloqueado, habilítalo en los ajustes del dispositivo o busca por código.'
    : 'No pudimos abrir la cámara. Puedes reintentar o buscar el espacio por código.';

class QrCamera extends StatefulWidget {
  const QrCamera({super.key, required this.onRead, required this.onRetry});
  final ValueChanged<String> onRead;
  final VoidCallback onRetry;
  @override
  State<QrCamera> createState() => _QrCameraState();
}

class _QrCameraState extends State<QrCamera> {
  final _scanner = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
    returnImage: false,
  );
  bool _readError = false;
  @override
  void dispose() {
    unawaited(_scanner.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _readError
      ? SingleChildScrollView(
          padding: const EdgeInsets.all(AppSpace.md),
          child: ErrorState(
            message: 'No pudimos leer el QR. Inténtalo nuevamente.',
            onRetry: widget.onRetry,
          ),
        )
      : MobileScanner(
          controller: _scanner,
          onDetect: (capture) {
            final values = capture.barcodes
                .map((b) => b.rawValue)
                .whereType<String>();
            if (values.isNotEmpty) widget.onRead(values.first);
          },
          onDetectError: (_, _) {
            if (mounted) setState(() => _readError = true);
          }, // Never log QR payloads or native exception details.
          errorBuilder: (context, error) => ColoredBox(
            color: AppColors.canvas,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(AppSpace.md),
              child: ErrorState(
                message: cameraErrorMessage(error.errorCode),
                onRetry: widget.onRetry,
              ),
            ),
          ),
        );
}
