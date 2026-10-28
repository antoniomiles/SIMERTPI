import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

import 'inspector_enforcement_api.dart';

abstract interface class InspectorEvidencePicker {
  Future<PhotoEvidenceDraft?> capturePhoto();
}

/// Camera-only capture. No gallery selection is exposed for inspection evidence.
class CameraInspectorEvidencePicker implements InspectorEvidencePicker {
  CameraInspectorEvidencePicker({ImagePicker? picker})
    : _picker = picker ?? ImagePicker();

  final ImagePicker _picker;

  @override
  Future<PhotoEvidenceDraft?> capturePhoto() async {
    final XFile? image;
    try {
      image = await _picker.pickImage(
        source: ImageSource.camera,
        maxWidth: 1600,
        maxHeight: 1600,
        imageQuality: 82,
      );
    } on PlatformException catch (error) {
      // Avoid exposing plugin-specific text that can vary across OS versions.
      final message = switch (error.code) {
        'camera_access_denied' || 'camera_access_denied_without_prompt' =>
          'Permita el acceso a la cámara para capturar la evidencia y vuelva a intentarlo.',
        'no_available_camera' =>
          'Este dispositivo no tiene una cámara disponible para capturar evidencia.',
        'no_valid_image_uri' || 'no_valid_media_uri' =>
          'No se pudo leer la foto capturada. Vuelva a tomarla.',
        _ => 'No se pudo abrir la cámara. Revise el permiso e inténtelo nuevamente.',
      };
      throw InspectorApiException(message);
    }
    if (image == null) return null;
    final length = await image.length();
    if (length == 0) {
      throw const InspectorApiException('La foto capturada está vacía.');
    }
    if (length > 10 * 1024 * 1024) {
      throw const InspectorApiException(
        'La foto supera el límite de 10 MB. Vuelva a capturarla más pequeña.',
      );
    }
    final bytes = await image.readAsBytes();
    return PhotoEvidenceDraft.validate(
      filename: image.name,
      bytes: bytes,
      capturedAt: DateTime.now().toUtc(),
    );
  }
}
