import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker/image_picker.dart';
import 'package:simertpi_municipal_app/features/enforcement/data/inspector_evidence_picker.dart';
import 'package:simertpi_municipal_app/features/enforcement/data/inspector_enforcement_api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('captures only from camera with bounded dimensions and validates JPEG', () async {
    final picker = _FakeImagePicker()
      ..result = XFile.fromData(
        Uint8List.fromList([0xff, 0xd8, 0xff, 0x01]),
        name: 'capture.jpg',
      );
    final result = await CameraInspectorEvidencePicker(picker: picker).capturePhoto();

    expect(picker.source, ImageSource.camera);
    expect(picker.maxWidth, 1600);
    expect(picker.maxHeight, 1600);
    expect(picker.imageQuality, 82);
    expect(result?.filename, 'capture.jpg');
    expect(result?.contentType, 'image/jpeg');
    expect(result?.bytes, [0xff, 0xd8, 0xff, 0x01]);
    expect(result?.capturedAt.isUtc, isTrue);
  });

  test('camera cancellation is not presented as an error', () async {
    final picker = _FakeImagePicker();
    expect(await CameraInspectorEvidencePicker(picker: picker).capturePhoto(), isNull);
  });

  test('permission denial maps to actionable Spanish without plugin details', () async {
    final picker = _FakeImagePicker()
      ..failure = PlatformException(
        code: 'camera_access_denied',
        message: 'platform-private-detail',
      );
    await expectLater(
      CameraInspectorEvidencePicker(picker: picker).capturePhoto(),
      throwsA(
        isA<InspectorApiException>()
            .having((error) => error.message, 'message', contains('Permita el acceso'))
            .having((error) => error.message, 'message', isNot(contains('platform-private-detail'))),
      ),
    );
  });

  test('missing camera and unreadable image have distinct recovery messages', () async {
    final noCamera = _FakeImagePicker()
      ..failure = PlatformException(code: 'no_available_camera');
    await expectLater(
      CameraInspectorEvidencePicker(picker: noCamera).capturePhoto(),
      throwsA(
        isA<InspectorApiException>().having(
          (error) => error.message,
          'message',
          contains('no tiene una cámara disponible'),
        ),
      ),
    );

    final unreadable = _FakeImagePicker()
      ..failure = PlatformException(code: 'no_valid_image_uri');
    await expectLater(
      CameraInspectorEvidencePicker(picker: unreadable).capturePhoto(),
      throwsA(
        isA<InspectorApiException>().having(
          (error) => error.message,
          'message',
          contains('No se pudo leer la foto'),
        ),
      ),
    );
  });
}

class _FakeImagePicker extends ImagePicker {
  XFile? result;
  PlatformException? failure;
  ImageSource? source;
  double? maxWidth;
  double? maxHeight;
  int? imageQuality;

  @override
  Future<XFile?> pickImage({
    required ImageSource source,
    double? maxWidth,
    double? maxHeight,
    int? imageQuality,
    CameraDevice preferredCameraDevice = CameraDevice.rear,
    bool requestFullMetadata = true,
  }) async {
    this.source = source;
    this.maxWidth = maxWidth;
    this.maxHeight = maxHeight;
    this.imageQuality = imageQuality;
    if (failure case final error?) throw error;
    return result;
  }
}
