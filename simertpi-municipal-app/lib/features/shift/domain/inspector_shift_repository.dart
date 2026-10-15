import 'inspector_shift.dart';

/// App-domain port only; no HTTP adapter or backend endpoint exists yet.
abstract interface class InspectorShiftRepository {
  Future<InspectorShift?> currentShift();
  Future<InspectorShift> startShift();
  Future<InspectorShift> closeShift();
}
