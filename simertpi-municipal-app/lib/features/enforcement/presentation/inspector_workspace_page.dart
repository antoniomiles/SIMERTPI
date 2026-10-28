import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../data/inspector_enforcement_api.dart';
import '../data/inspector_evidence_picker.dart';
import 'inspector_qr_scanner.dart';

typedef InspectorQrScannerBuilder = Widget Function(
  BuildContext context,
  ValueChanged<String> onDetected,
);

class InspectorWorkspacePage extends StatefulWidget {
  const InspectorWorkspacePage({
    super.key,
    required this.inspectorId,
    required this.repository,
    required this.onSessionExpired,
    this.evidencePicker,
    this.qrScannerBuilder,
  });

  final String inspectorId;
  final InspectorEnforcementRepository repository;
  final VoidCallback onSessionExpired;
  final InspectorEvidencePicker? evidencePicker;
  final InspectorQrScannerBuilder? qrScannerBuilder;

  @override
  State<InspectorWorkspacePage> createState() => _InspectorWorkspacePageState();
}

class _InspectorWorkspacePageState extends State<InspectorWorkspacePage> {
  final _lookupInput = TextEditingController();
  final _spaceId = TextEditingController();
  final _plate = TextEditingController();
  final _inspectionNotes = TextEditingController();
  final _violationType = TextEditingController();
  final _violationNotes = TextEditingController();
  String _lookupKind = 'plate';
  String _inspectionResult = 'VALID';
  InspectionSituation? _situation;
  InspectionRecord? _inspection;
  ViolationRecord? _violation;
  String? _error;
  String? _success;
  bool _lookingUp = false;
  bool _savingInspection = false;
  bool _savingViolation = false;
  bool _capturingEvidence = false;
  bool _uploadingEvidence = false;
  PhotoEvidenceDraft? _evidenceDraft;
  EvidenceRecord? _evidenceRecord;
  String? _evidenceError;
  String? _evidenceSuccess;
  String? _inspectionIdempotencyKey;
  String? _violationIdempotencyKey;
  String? _evidenceIdempotencyKey;
  Future<List<Object>>? _historyFuture;

  @override
  void dispose() {
    _lookupInput.dispose();
    _spaceId.dispose();
    _plate.dispose();
    _inspectionNotes.dispose();
    _violationType.dispose();
    _violationNotes.dispose();
    super.dispose();
  }

  Future<void> _lookup() async {
    if (_lookingUp) return;
    final value = _lookupInput.text.trim();
    if (value.isEmpty) {
      setState(
        () => _error =
            'Ingrese la placa, el contenido del QR o el ID del espacio.',
      );
      return;
    }
    setState(() {
      _lookingUp = true;
      _error = null;
      _success = null;
    });
    try {
      final result = await widget.repository.lookup(
        plate: _lookupKind == 'plate' ? value : null,
        qrCode: _lookupKind == 'qr' ? value : null,
        parkingSpaceId: _lookupKind == 'space' ? value : null,
      );
      if (!mounted) return;
      setState(() {
        _situation = result;
        _inspection = null;
        _violation = null;
        _inspectionIdempotencyKey = null;
        _violationIdempotencyKey = null;
        if (result.parkingSpaceId != null) {
          _spaceId.text = result.parkingSpaceId!;
        }
        if (result.plate != null) {
          _plate.text = result.plate!;
        }
        _success = 'Consulta completada. La situación corresponde al momento consultado.';
      });
    } on InspectorApiException catch (e) {
      if (e.statusCode == 401) widget.onSessionExpired();
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = 'No se pudo interpretar la respuesta del servicio.',
        );
      }
    } finally {
      if (mounted) setState(() => _lookingUp = false);
    }
  }

  Future<void> _scanQr() async {
    final value = await showDialog<String>(
      context: context,
      builder: (dialogContext) => Dialog(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  const Expanded(child: Text('Leer QR del espacio')),
                  IconButton(
                    tooltip: 'Cerrar lector',
                    onPressed: () => Navigator.of(dialogContext).pop(),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
              (widget.qrScannerBuilder ??
                  (context, onDetected) => InspectorQrScanner(
                    onDetected: onDetected,
                  ))(dialogContext, (payload) {
                if (Navigator.of(dialogContext).canPop()) {
                  Navigator.of(dialogContext).pop(payload);
                }
              }),
            ],
          ),
        ),
      ),
    );
    if (value == null || !mounted) return;
    setState(() {
      _lookupKind = 'qr';
      _lookupInput.text = value;
      _situation = null;
      _inspection = null;
      _violation = null;
      _spaceId.clear();
      _plate.clear();
      _inspectionIdempotencyKey = null;
      _violationIdempotencyKey = null;
    });
    await _lookup();
  }

  Future<void> _captureEvidence() async {
    if (_capturingEvidence || _uploadingEvidence) return;
    if (_violation == null) return;
    setState(() {
      _capturingEvidence = true;
      _evidenceError = null;
      _evidenceSuccess = null;
    });
    try {
      final draft =
          await (widget.evidencePicker ?? CameraInspectorEvidencePicker())
              .capturePhoto();
      if (!mounted || draft == null) return;
      setState(() {
        _evidenceDraft = draft;
        _evidenceRecord = null;
        _evidenceIdempotencyKey = null;
      });
    } on InspectorApiException catch (error) {
      if (mounted) setState(() => _evidenceError = error.message);
    } catch (_) {
      if (mounted) {
        setState(
          () => _evidenceError = 'No se pudo capturar la foto. Revise el permiso de cámara e inténtelo nuevamente.',
        );
      }
    } finally {
      if (mounted) setState(() => _capturingEvidence = false);
    }
  }

  Future<void> _uploadEvidence() async {
    if (_uploadingEvidence || _capturingEvidence) return;
    final violation = _violation;
    final draft = _evidenceDraft;
    if (violation == null || draft == null) return;
    setState(() {
      _uploadingEvidence = true;
      _evidenceError = null;
      _evidenceSuccess = null;
    });
    _evidenceIdempotencyKey ??= newInspectorIdempotencyKey();
    try {
      final record = await widget.repository.uploadPhotoEvidence(
        violationId: violation.id,
        draft: draft,
        idempotencyKey: _evidenceIdempotencyKey,
      );
      if (!mounted) return;
      setState(() {
        _evidenceRecord = record;
        _evidenceDraft = null;
        _evidenceIdempotencyKey = null;
        _evidenceSuccess = 'Evidencia cargada correctamente (${record.id}).';
      });
    } on InspectorApiException catch (error) {
      if (error.statusCode == 401) widget.onSessionExpired();
      if (mounted) setState(() => _evidenceError = error.message);
    } catch (_) {
      if (mounted) {
        setState(
          () => _evidenceError = 'No se pudo cargar la evidencia. La foto se conserva para reintentar.',
        );
      }
    } finally {
      if (mounted) setState(() => _uploadingEvidence = false);
    }
  }

  Future<void> _createInspection() async {
    if (_savingInspection) return;
    final space = _spaceId.text.trim();
    final plate = _plate.text.trim();
    if (!_isUuid(space)) {
      setState(
        () => _error = 'Consulte un espacio por QR o ingrese su identificador antes de registrar la inspección.',
      );
      return;
    }
    final situation = _situation;
    final matchesSpace = situation?.parkingSpaceId == space;
    final matchesPlate =
        plate.isEmpty ||
        situation?.plate?.trim().toUpperCase() == plate.toUpperCase();
    final matchedSituation = matchesSpace && matchesPlate ? situation : null;
    if (plate.isEmpty && matchedSituation?.vehicleId == null) {
      setState(
        () => _error = 'Identifique el vehículo con su placa o deje seleccionada una situación consultada.',
      );
      return;
    }
    setState(() {
      _savingInspection = true;
      _error = null;
      _success = null;
    });
    _inspectionIdempotencyKey ??= newInspectorIdempotencyKey();
    try {
      final record = await widget.repository.createInspection(
        parkingSpaceId: space,
        parkingSessionId: matchedSituation?.parkingSessionId,
        vehicleId: matchedSituation?.vehicleId,
        observedPlate: plate.isEmpty ? matchedSituation?.plate : plate,
        result: _inspectionResult,
        notes: _inspectionNotes.text,
        observedAt: DateTime.now().toUtc(),
        idempotencyKey: _inspectionIdempotencyKey,
      );
      if (!mounted) return;
      setState(() {
        _inspection = record;
        _inspectionIdempotencyKey = null;
        _success = 'Inspección registrada correctamente (${record.id}).';
        _historyFuture = null;
      });
    } on InspectorApiException catch (e) {
      if (e.statusCode == 401) widget.onSessionExpired();
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = 'No se pudo guardar la inspección. Se conservaron los datos del formulario.',
        );
      }
    } finally {
      if (mounted) setState(() => _savingInspection = false);
    }
  }

  Future<void> _createPotentialViolation() async {
    if (_savingViolation) return;
    final inspection = _inspection;
    final type = _violationType.text.trim();
    if (inspection == null) {
      setState(() => _error = 'Primero registre una inspección.');
      return;
    }
    if (inspection.result == 'VALID') {
      setState(
        () => _error = 'Una inspección marcada como válida no admite un registro de posible infracción.',
      );
      return;
    }
    if (type.isEmpty || type.length > 50) {
      setState(
        () => _error = 'Describa brevemente el hecho observado (máximo 50 caracteres). No hay un catálogo legal configurado.',
      );
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Confirmar registro para revisión'),
        content: Text(
          'Se guardará el hecho observado “$type” vinculado a la inspección. Quedará en estado OPEN para revisión administrativa. Esta acción no calcula, notifica ni cobra una multa.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Volver'),
          ),
          FilledButton(
            key: const Key('confirm-potential-violation'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Registrar para revisión'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _savingViolation = true;
      _error = null;
      _success = null;
    });
    _violationIdempotencyKey ??= newInspectorIdempotencyKey();
    try {
      final record = await widget.repository.createPotentialViolation(
        inspectionId: inspection.id,
        observedType: type,
        notes: _violationNotes.text,
        occurredAt: inspection.observedAt,
        idempotencyKey: _violationIdempotencyKey,
      );
      if (!mounted) return;
      setState(() {
        _violation = record;
        _violationIdempotencyKey = null;
        _historyFuture = null;
        _success =
            'Registro sujeto a revisión creado (${record.id}). No se notificó ni calculó una multa.';
      });
    } on InspectorApiException catch (e) {
      if (e.statusCode == 401) widget.onSessionExpired();
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = 'No se pudo guardar el registro. Se conservaron los datos ingresados.',
        );
      }
    } finally {
      if (mounted) setState(() => _savingViolation = false);
    }
  }

  Future<List<Object>> _loadHistory() => _historyFuture ??= Future.wait([
    widget.repository.inspectionsByInspector(widget.inspectorId),
    widget.repository.violationsByInspector(widget.inspectorId),
  ]);

  @override
  Widget build(BuildContext context) => DefaultTabController(
    length: 3,
    child: Scaffold(
      appBar: AppBar(
        title: const Text('Inspecciones municipales'),
        bottom: const TabBar(
          tabs: [
            Tab(
              key: Key('tab-lookup'),
              icon: Icon(Icons.search),
              text: 'Consultar',
            ),
            Tab(
              key: Key('tab-inspect'),
              icon: Icon(Icons.fact_check_outlined),
              text: 'Inspeccionar',
            ),
            Tab(
              key: Key('tab-history'),
              icon: Icon(Icons.history),
              text: 'Historial',
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          if (_error != null) _notice(_error!, isError: true),
          if (_success != null) _notice(_success!, isError: false),
          Expanded(
            child: TabBarView(
              children: [
                _lookupView(context),
                _inspectionView(context),
                _historyView(context),
              ],
            ),
          ),
        ],
      ),
    ),
  );

  Widget _lookupView(BuildContext context) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      _sectionTitle(
        context,
        'Consultar situación de estacionamiento',
        'Use una sola fuente para consultar el estado actual devuelto por el servidor.',
      ),
      const SizedBox(height: 16),
      DropdownButtonFormField<String>(
        key: const Key('inspection-lookup-kind'),
        initialValue: _lookupKind,
        decoration: const InputDecoration(
          labelText: 'Buscar por',
          border: OutlineInputBorder(),
        ),
        items: const [
          DropdownMenuItem(value: 'plate', child: Text('Placa')),
          DropdownMenuItem(value: 'qr', child: Text('Código QR')),
          DropdownMenuItem(value: 'space', child: Text('ID del espacio')),
        ],
        onChanged: (value) => setState(() {
          _lookupKind = value ?? 'plate';
          _lookupInput.clear();
          _situation = null;
          _inspection = null;
          _violation = null;
          _spaceId.clear();
          _plate.clear();
          _error = null;
        }),
      ),
      const SizedBox(height: 12),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: TextField(
              key: const Key('inspection-lookup-value'),
              controller: _lookupInput,
              textCapitalization: _lookupKind == 'plate'
                  ? TextCapitalization.characters
                  : TextCapitalization.none,
              decoration: InputDecoration(
                labelText: _lookupKind == 'plate'
                    ? 'Placa'
                    : _lookupKind == 'qr'
                    ? 'Contenido del QR'
                    : 'Identificador UUID del espacio',
                helperText: _lookupKind == 'qr'
                    ? 'Puede escribir el contenido o leerlo con la cámara.'
                    : null,
                border: const OutlineInputBorder(),
              ),
              onChanged: (_) => setState(() {
                _situation = null;
                _inspection = null;
                _violation = null;
                _spaceId.clear();
                _plate.clear();
                _inspectionIdempotencyKey = null;
                _violationIdempotencyKey = null;
                _error = null;
              }),
              onSubmitted: (_) => _lookup(),
            ),
          ),
          if (_lookupKind == 'qr') ...[
            const SizedBox(width: 8),
            IconButton.filledTonal(
              key: const Key('inspection-qr-scan'),
              tooltip: 'Leer QR con la cámara',
              onPressed: _lookingUp ? null : _scanQr,
              icon: const Icon(Icons.qr_code_scanner),
            ),
          ],
        ],
      ),
      const SizedBox(height: 12),
      FilledButton.icon(
        key: const Key('inspection-lookup-submit'),
        onPressed: _lookingUp ? null : _lookup,
        icon: _lookingUp
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.search),
        label: Text(_lookingUp ? 'Consultando…' : 'Consultar'),
      ),
      if (_situation != null) ...[
        const SizedBox(height: 20),
        _situationCard(context, _situation!),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const Key('go-to-inspection-tab'),
          onPressed: () => DefaultTabController.of(context).animateTo(1),
          icon: const Icon(Icons.fact_check_outlined),
          label: const Text('Registrar observación'),
        ),
      ],
    ],
  );

  Widget _inspectionView(BuildContext context) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      _sectionTitle(
        context,
        'Registrar inspección',
        'Registre lo observado en sitio. La selección del resultado no emite una multa automáticamente.',
      ),
      const SizedBox(height: 16),
      TextField(
        key: const Key('inspection-space-id'),
        controller: _spaceId,
        decoration: const InputDecoration(
          labelText: 'ID del espacio *',
          helperText: 'Consulte el espacio por QR para completar este dato.',
          border: OutlineInputBorder(),
        ),
        onChanged: (_) => _inspectionIdempotencyKey = null,
      ),
      const SizedBox(height: 12),
      TextField(
        key: const Key('inspection-plate'),
        controller: _plate,
        maxLength: 10,
        textCapitalization: TextCapitalization.characters,
        decoration: const InputDecoration(
          labelText: 'Placa observada',
          border: OutlineInputBorder(),
        ),
        onChanged: (_) => _inspectionIdempotencyKey = null,
      ),
      const SizedBox(height: 12),
      DropdownButtonFormField<String>(
        key: const Key('inspection-result'),
        initialValue: _inspectionResult,
        decoration: const InputDecoration(
          labelText: 'Resultado de la observación *',
          border: OutlineInputBorder(),
        ),
        items: const [
          DropdownMenuItem(
            value: 'VALID',
            child: Text('Observación sin hallazgo'),
          ),
          DropdownMenuItem(
            value: 'EXPIRED',
            child: Text('Sesión reportada como vencida'),
          ),
          DropdownMenuItem(
            value: 'NO_PAYMENT',
            child: Text('Sin pago observado'),
          ),
          DropdownMenuItem(
            value: 'VIOLATION',
            child: Text('Otro hecho observado'),
          ),
        ],
        onChanged: (value) => setState(() {
          _inspectionResult = value ?? 'VALID';
          _inspectionIdempotencyKey = null;
        }),
      ),
      const SizedBox(height: 12),
      TextField(
        key: const Key('inspection-notes'),
        controller: _inspectionNotes,
        maxLength: 500,
        maxLines: 3,
        decoration: const InputDecoration(
          labelText: 'Notas de observación',
          border: OutlineInputBorder(),
        ),
        onChanged: (_) => _inspectionIdempotencyKey = null,
      ),
      const SizedBox(height: 6),
      _infoCard(
        context,
        'La consulta de situación es informativa y puede cambiar. No se genera una sanción por el vencimiento de una sesión.',
      ),
      const SizedBox(height: 12),
      FilledButton.icon(
        key: const Key('inspection-save'),
        onPressed: _savingInspection ? null : _createInspection,
        icon: _savingInspection
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.save_outlined),
        label: Text(
          _savingInspection ? 'Guardando inspección…' : 'Guardar inspección',
        ),
      ),
      if (_inspection != null) ...[
        const SizedBox(height: 18),
        _infoCard(
          context,
          'Inspección ${_inspection!.id} registrada como ${_resultLabel(_inspection!.result)}.',
        ),
        if (_inspection!.result != 'VALID' && _violation == null) ...[
          const SizedBox(height: 16),
          _sectionTitle(
            context,
            'Registrar posible infracción',
            'Acción explícita. El registro queda OPEN para revisión administrativa; no notifica ni calcula multas.',
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('potential-violation-type'),
            controller: _violationType,
            maxLength: 50,
            decoration: const InputDecoration(
              labelText: 'Hecho observado *',
              helperText: 'Texto libre; no existe catálogo normativo vigente configurado.',
              border: OutlineInputBorder(),
            ),
            onChanged: (_) => _violationIdempotencyKey = null,
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('potential-violation-notes'),
            controller: _violationNotes,
            maxLength: 500,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'Detalle para revisión',
              border: OutlineInputBorder(),
            ),
            onChanged: (_) => _violationIdempotencyKey = null,
          ),
          const SizedBox(height: 12),
          FilledButton.tonalIcon(
            key: const Key('potential-violation-submit'),
            onPressed: _savingViolation ? null : _createPotentialViolation,
            icon: _savingViolation
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.flag_outlined),
            label: Text(
              _savingViolation ? 'Registrando…' : 'Registrar para revisión',
            ),
          ),
        ],
        if (_violation != null) ...[
          const SizedBox(height: 12),
          _infoCard(
            context,
            'Registro ${_violation!.id} — estado ${_violation!.status}. Monto no aplicado por esta aplicación.',
          ),
          const SizedBox(height: 12),
          _evidenceAvailability(context),
        ],
      ],
    ],
  );

  Widget _historyView(BuildContext context) => FutureBuilder<List<Object>>(
    future: _loadHistory(),
    builder: (context, snapshot) {
      if (snapshot.connectionState == ConnectionState.waiting) {
        return const Center(child: CircularProgressIndicator());
      }
      if (snapshot.hasError) {
        final error = snapshot.error;
        if (error is InspectorApiException && error.statusCode == 401) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) widget.onSessionExpired();
          });
        }
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text('No se pudo cargar el historial.'),
            if (error is InspectorApiException) Text(error.message),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () => setState(() => _historyFuture = null),
              icon: const Icon(Icons.refresh),
              label: const Text('Reintentar'),
            ),
          ],
        );
      }
      final inspections = snapshot.data![0] as List<InspectionRecord>;
      final violations = snapshot.data![1] as List<ViolationRecord>;
      if (inspections.isEmpty && violations.isEmpty) {
        return const Center(
          child: Text('Todavía no hay inspecciones registradas.'),
        );
      }
      return ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _sectionTitle(
            context,
            'Mis registros',
            'Historial del inspector autenticado.',
          ),
          for (final inspection in inspections)
            Card(
              key: ValueKey('inspection-${inspection.id}'),
              child: ListTile(
                leading: const CircleAvatar(
                  child: Icon(Icons.fact_check_outlined),
                ),
                title: Text('Inspección · ${_resultLabel(inspection.result)}'),
                subtitle: Text(
                  '${inspection.observedPlate ?? 'Sin placa'} · ${inspection.observedAt.toLocal()}\nEspacio ${inspection.parkingSpaceId}',
                ),
                isThreeLine: true,
              ),
            ),
          for (final violation in violations)
            Card(
              key: ValueKey('violation-${violation.id}'),
              child: ListTile(
                leading: const CircleAvatar(child: Icon(Icons.flag_outlined)),
                title: Text('Registro sujeto a revisión · ${violation.status}'),
                subtitle: Text(
                  '${violation.violationType ?? 'Hecho observado'} · ${violation.plate ?? 'Sin placa'}\nID ${violation.id}',
                ),
                isThreeLine: true,
              ),
            ),
          OutlinedButton.icon(
            onPressed: () => setState(() => _historyFuture = null),
            icon: const Icon(Icons.refresh),
            label: const Text('Actualizar historial'),
          ),
        ],
      );
    },
  );

  Widget _situationCard(BuildContext context, InspectionSituation situation) =>
      Card(
        key: const Key('inspection-situation-card'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Situación consultada',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const Divider(),
              _detailRow('Placa', situation.plate ?? 'No identificada'),
              _detailRow(
                'Espacio',
                situation.parkingSpaceCode ??
                    situation.parkingSpaceId ??
                    'No identificado',
              ),
              _detailRow('QR', situation.parkingSpaceQrCode ?? 'No disponible'),
              _detailRow(
                'Estado de sesión',
                _sessionStatus(situation.sessionStatus),
              ),
              if (situation.startedAt != null)
                _detailRow('Inicio', situation.startedAt!.toLocal().toString()),
              if (situation.expectedEndAt != null)
                _detailRow(
                  'Fin previsto',
                  situation.expectedEndAt!.toLocal().toString(),
                ),
              if (situation.endedAt != null)
                _detailRow(
                  'Finalización registrada',
                  situation.endedAt!.toLocal().toString(),
                ),
              if (situation.parkingSpaceId == null)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                    'Esta consulta no resolvió un espacio. Para registrar inspección, consulte por QR o proporcione el ID del espacio.',
                  ),
                ),
              const Padding(
                padding: EdgeInsets.only(top: 10),
                child: Text(
                  'Dato informativo del backend. El resultado no determina automáticamente una infracción ni una sanción.',
                ),
              ),
            ],
          ),
        ),
      );

  Widget _evidenceAvailability(BuildContext context) => Card(
    color: Theme.of(context).colorScheme.surfaceContainerHighest,
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Evidencia fotográfica',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          const SizedBox(height: 6),
          const Text(
            'Capture una foto para adjuntarla a este registro. Se reduce antes de enviarla; solo se acepta JPEG o PNG de hasta 10 MB. No se usa la galería.',
          ),
          if (_evidenceDraft case final draft?) ...[
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Image.memory(
                Uint8List.fromList(draft.bytes),
                height: 180,
                width: double.infinity,
                fit: BoxFit.contain,
                errorBuilder: (_, _, _) => const SizedBox.shrink(),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              '${draft.filename} · ${(draft.bytes.length / 1024).ceil()} KB',
            ),
          ],
          if (_evidenceError != null) ...[
            const SizedBox(height: 8),
            Semantics(liveRegion: true, child: Text(_evidenceError!)),
          ],
          if (_evidenceSuccess != null) ...[
            const SizedBox(height: 8),
            Semantics(liveRegion: true, child: Text(_evidenceSuccess!)),
          ],
          if (_evidenceRecord == null) ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  key: const Key('evidence-capture-photo'),
                  onPressed: _capturingEvidence || _uploadingEvidence
                      ? null
                      : _captureEvidence,
                  icon: _capturingEvidence
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.photo_camera_outlined),
                  label: Text(
                    _capturingEvidence ? 'Abriendo cámara…' : 'Tomar foto',
                  ),
                ),
                if (_evidenceDraft != null)
                  FilledButton.icon(
                    key: const Key('evidence-upload'),
                    onPressed: _uploadingEvidence ? null : _uploadEvidence,
                    icon: _uploadingEvidence
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.cloud_upload_outlined),
                    label: Text(
                      _uploadingEvidence ? 'Cargando…' : 'Adjuntar evidencia',
                    ),
                  ),
                if (_evidenceDraft != null)
                  TextButton(
                    onPressed: _uploadingEvidence
                        ? null
                        : () => setState(() {
                            _evidenceDraft = null;
                            _evidenceIdempotencyKey = null;
                            _evidenceError = null;
                          }),
                    child: const Text('Descartar foto'),
                  ),
              ],
            ),
          ],
        ],
      ),
    ),
  );

  Widget _sectionTitle(BuildContext context, String title, String subtitle) =>
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(subtitle, style: Theme.of(context).textTheme.bodyMedium),
        ],
      );
  Widget _infoCard(BuildContext context, String text) => Card(
    color: Theme.of(context).colorScheme.primaryContainer,
    child: Padding(padding: const EdgeInsets.all(14), child: Text(text)),
  );
  Widget _detailRow(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 145,
          child: Text(
            label,
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        ),
        Expanded(child: Text(value)),
      ],
    ),
  );
  Widget _notice(String message, {required bool isError}) => Material(
    color: isError
        ? Theme.of(context).colorScheme.errorContainer
        : Theme.of(context).colorScheme.primaryContainer,
    child: Semantics(
      liveRegion: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            Icon(isError ? Icons.error_outline : Icons.check_circle_outline),
            const SizedBox(width: 8),
            Expanded(child: Text(message)),
            IconButton(
              tooltip: 'Cerrar mensaje',
              onPressed: () => setState(() {
                if (isError) {
                  _error = null;
                } else {
                  _success = null;
                }
              }),
              icon: const Icon(Icons.close),
            ),
          ],
        ),
      ),
    ),
  );
  String _resultLabel(String value) => switch (value) {
    'VALID' => 'Sin hallazgo',
    'EXPIRED' => 'Sesión reportada como vencida',
    'NO_PAYMENT' => 'Sin pago observado',
    'VIOLATION' => 'Otro hecho observado',
    _ => 'Resultado no reconocido',
  };
  String _sessionStatus(String? value) => switch (value) {
    'ACTIVE' => 'Activa (según backend)',
    'EXPIRED' => 'Vencida (según backend)',
    'MAX_TIME_REACHED' => 'Tiempo máximo alcanzado',
    'PAYMENT_PENDING' => 'Pago pendiente',
    null => 'Sin sesión activa encontrada',
    _ => 'Estado informado: $value',
  };

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  ).hasMatch(value);
}
