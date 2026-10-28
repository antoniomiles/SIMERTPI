import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/features/enforcement/data/inspector_enforcement_api.dart';
import 'package:simertpi_municipal_app/features/enforcement/data/inspector_evidence_picker.dart';
import 'package:simertpi_municipal_app/features/enforcement/presentation/inspector_workspace_page.dart';

const _spaceUuid = '11111111-1111-4111-8111-111111111111';

void main() {
  testWidgets('camera QR result is passed to the real lookup contract', (
    tester,
  ) async {
    final repository = _FakeRepository();
    await tester.pumpWidget(
      _app(
        repository,
        qrScannerBuilder: (context, onDetected) => TextButton(
          key: const Key('test-qr-detected'),
          onPressed: () => onDetected('QR-CAMERA-READ'),
          child: const Text('Simular lectura del lector'),
        ),
      ),
    );
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Código QR').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('inspection-qr-scan')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('test-qr-detected')));
    await tester.pumpAndSettle();
    expect(repository.lookupQr, 'QR-CAMERA-READ');
    expect(find.byKey(const Key('inspection-situation-card')), findsOneWidget);
  });

  testWidgets(
    'inspector can lookup by QR and records an observation only on explicit save',
    (tester) async {
      final repository = _FakeRepository();
      await tester.pumpWidget(_app(repository));
      await tester.tap(find.byKey(const Key('inspection-lookup-kind')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Código QR').last);
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('inspection-lookup-value')),
        'QR-SPACE-1',
      );
      await tester.tap(find.byKey(const Key('inspection-lookup-submit')));
      await tester.pumpAndSettle();
      expect(repository.lookupQr, 'QR-SPACE-1');
      expect(
        find.byKey(const Key('inspection-situation-card')),
        findsOneWidget,
      );
      expect(
        find.textContaining('no determina automáticamente una infracción'),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const Key('tab-inspect')));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('inspection-save')),
        350,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.byKey(const Key('inspection-save')));
      await tester.pumpAndSettle();
      expect(repository.createInspectionCalls, 1);
      expect(repository.lastSpaceId, _spaceUuid);
      expect(
        find.textContaining('Inspección registrada correctamente'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'possible violation requires a saved non-valid observation and stays explicitly review-only',
    (tester) async {
      final repository = _FakeRepository();
      await tester.pumpWidget(_app(repository));
      await tester.tap(find.byKey(const Key('tab-inspect')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('inspection-space-id')),
        _spaceUuid,
      );
      await tester.enterText(
        find.byKey(const Key('inspection-plate')),
        'ABC1234',
      );
      await tester.tap(find.byKey(const Key('inspection-result')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Sesión reportada como vencida').last);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('inspection-save')),
        350,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.byKey(const Key('inspection-save')));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('potential-violation-type')),
        350,
        scrollable: find.byType(Scrollable).last,
      );
      expect(
        find.textContaining('no notifica ni calcula multas'),
        findsOneWidget,
      );
      await tester.enterText(
        find.byKey(const Key('potential-violation-type')),
        'Hecho observado',
      );
      await tester.scrollUntilVisible(
        find.byKey(const Key('potential-violation-submit')),
        350,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.byKey(const Key('potential-violation-submit')));
      await tester.pumpAndSettle();
      expect(find.text('Confirmar registro para revisión'), findsOneWidget);
      await tester.tap(find.byKey(const Key('confirm-potential-violation')));
      await tester.pumpAndSettle();
      expect(repository.createViolationCalls, 1);
      expect(repository.lastViolationType, 'Hecho observado');
      expect(
        find.textContaining('No se notificó ni calculó una multa'),
        findsOneWidget,
      );
      expect(find.textContaining('estado OPEN'), findsOneWidget);
    },
  );

  testWidgets(
    'failed save keeps entered fields and presents a specific service error',
    (tester) async {
      final repository = _FakeRepository()..failNextInspection = true;
      await tester.pumpWidget(_app(repository));
      await tester.tap(find.byKey(const Key('tab-inspect')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('inspection-space-id')),
        _spaceUuid,
      );
      await tester.enterText(
        find.byKey(const Key('inspection-plate')),
        'ABC1234',
      );
      final spaceController = tester
          .widget<TextField>(find.byKey(const Key('inspection-space-id')))
          .controller!;
      final plateController = tester
          .widget<TextField>(find.byKey(const Key('inspection-plate')))
          .controller!;
      await tester.scrollUntilVisible(
        find.byKey(const Key('inspection-save')),
        350,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.byKey(const Key('inspection-save')));
      await tester.pumpAndSettle();
      expect(
        find.text('No se pudo completar la operación (HTTP 503).'),
        findsOneWidget,
      );
      expect(spaceController.text, _spaceUuid);
      expect(plateController.text, 'ABC1234');
      await tester.scrollUntilVisible(
        find.byKey(const Key('inspection-save')),
        350,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.byKey(const Key('inspection-save')));
      await tester.pumpAndSettle();
      expect(repository.createInspectionCalls, 2);
      expect(repository.idempotencyKeys[0], repository.idempotencyKeys[1]);
    },
  );

  testWidgets('captured photo is uploaded only after a saved review record', (
    tester,
  ) async {
    final repository = _FakeRepository();
    final picker = _FakeEvidencePicker();
    await tester.pumpWidget(_app(repository, evidencePicker: picker));
    await tester.tap(find.byKey(const Key('tab-inspect')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('inspection-space-id')),
      _spaceUuid,
    );
    await tester.enterText(
      find.byKey(const Key('inspection-plate')),
      'ABC1234',
    );
    await tester.tap(find.byKey(const Key('inspection-result')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sesión reportada como vencida').last);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('inspection-save')),
      350,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const Key('inspection-save')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('potential-violation-type')),
      350,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.enterText(
      find.byKey(const Key('potential-violation-type')),
      'Hecho observado',
    );
    await tester.scrollUntilVisible(
      find.byKey(const Key('potential-violation-submit')),
      350,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const Key('potential-violation-submit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirm-potential-violation')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('evidence-capture-photo')),
      350,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const Key('evidence-capture-photo')));
    await tester.pumpAndSettle();
    expect(picker.calls, 1);
    await tester.scrollUntilVisible(
      find.byKey(const Key('evidence-upload')),
      350,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const Key('evidence-upload')));
    await tester.pumpAndSettle();
    expect(repository.uploadEvidenceCalls, 1);
    expect(repository.lastEvidenceViolationId, 'violation-1');
    expect(
      find.textContaining('Evidencia cargada correctamente'),
      findsOneWidget,
    );
  });
}

Widget _app(
  _FakeRepository repository, {
  InspectorEvidencePicker? evidencePicker,
  InspectorQrScannerBuilder? qrScannerBuilder,
}) => MaterialApp(
  home: InspectorWorkspacePage(
    inspectorId: '22222222-2222-4222-8222-222222222222',
    repository: repository,
    onSessionExpired: () {},
    evidencePicker: evidencePicker,
    qrScannerBuilder: qrScannerBuilder,
  ),
);

class _FakeRepository implements InspectorEnforcementRepository {
  String? lookupQr;
  String? lastSpaceId;
  String? lastViolationType;
  String? lastEvidenceViolationId;
  String? lastEvidenceKey;
  int createInspectionCalls = 0;
  int createViolationCalls = 0;
  int uploadEvidenceCalls = 0;
  bool failNextInspection = false;
  final idempotencyKeys = <String?>[];

  @override
  Future<InspectionSituation> lookup({
    String? plate,
    String? qrCode,
    String? parkingSpaceId,
  }) async {
    lookupQr = qrCode;
    return const InspectionSituation(
      parkingSpaceId: _spaceUuid,
      parkingSpaceCode: 'E-01',
      parkingSpaceQrCode: 'QR-SPACE-1',
      plate: 'ABC1234',
      parkingSessionId: 'session-1',
      sessionStatus: 'EXPIRED',
      startedAt: null,
      expectedEndAt: null,
      endedAt: null,
    );
  }

  @override
  Future<InspectionRecord> createInspection({
    required String parkingSpaceId,
    String? parkingSessionId,
    String? vehicleId,
    String? observedPlate,
    required String result,
    String? notes,
    required DateTime observedAt,
    String? idempotencyKey,
  }) async {
    createInspectionCalls++;
    lastSpaceId = parkingSpaceId;
    idempotencyKeys.add(idempotencyKey);
    if (failNextInspection) {
      failNextInspection = false;
      throw const InspectorApiException(
        'No se pudo completar la operación (HTTP 503).',
        statusCode: 503,
      );
    }
    return InspectionRecord(
      id: 'inspection-1',
      parkingSpaceId: parkingSpaceId,
      parkingSessionId: parkingSessionId,
      vehicleId: vehicleId,
      observedPlate: observedPlate,
      result: result,
      observedAt: observedAt,
      notes: notes,
    );
  }

  @override
  Future<ViolationRecord> createPotentialViolation({
    required String inspectionId,
    required String observedType,
    String? notes,
    required DateTime occurredAt,
    String? idempotencyKey,
  }) async {
    createViolationCalls++;
    lastViolationType = observedType;
    return ViolationRecord(
      id: 'violation-1',
      inspectionId: inspectionId,
      status: 'OPEN',
      fineAmount: 0,
      plate: 'ABC1234',
      violationType: observedType,
    );
  }

  @override
  Future<List<InspectionRecord>> inspectionsByInspector(
    String inspectorId,
  ) async => const [];
  @override
  Future<List<ViolationRecord>> violationsByInspector(
    String inspectorId,
  ) async => const [];

  @override
  Future<EvidenceRecord> uploadPhotoEvidence({
    required String violationId,
    required PhotoEvidenceDraft draft,
    String? idempotencyKey,
  }) async {
    uploadEvidenceCalls++;
    lastEvidenceViolationId = violationId;
    lastEvidenceKey = idempotencyKey;
    return EvidenceRecord(id: 'evidence-1', violationId: violationId);
  }
}

class _FakeEvidencePicker implements InspectorEvidencePicker {
  int calls = 0;

  @override
  Future<PhotoEvidenceDraft?> capturePhoto() async {
    calls++;
    return PhotoEvidenceDraft.validate(
      filename: 'capture.jpg',
      bytes: [0xff, 0xd8, 0xff, 0x01],
    );
  }
}
