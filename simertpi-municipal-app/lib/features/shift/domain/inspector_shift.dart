import 'package:flutter/foundation.dart';

enum InspectorShiftStatus { open, closed }

/// Conceptual model only: the backend currently has no shift resource/API.
@immutable
class InspectorShift {
  InspectorShift({
    required this.id,
    required this.inspectorId,
    required this.startedAt,
    this.endedAt,
  }) {
    if (id.trim().isEmpty || inspectorId.trim().isEmpty) {
      throw ArgumentError('A shift requires an id and inspector id.');
    }
    if (endedAt != null && endedAt!.isBefore(startedAt)) {
      throw ArgumentError('A shift cannot end before it starts.');
    }
  }

  final String id;
  final String inspectorId;
  final DateTime startedAt;
  final DateTime? endedAt;

  InspectorShiftStatus get status =>
      endedAt == null ? InspectorShiftStatus.open : InspectorShiftStatus.closed;
}
