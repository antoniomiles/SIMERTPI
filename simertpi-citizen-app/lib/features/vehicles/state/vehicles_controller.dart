import 'package:flutter/foundation.dart';

import '../data/vehicle_service.dart';

enum VehiclesPhase { initial, loading, success, empty, error }

class VehiclesController extends ChangeNotifier {
  VehiclesController(this.gateway);
  final VehicleGateway gateway;
  VehiclesPhase phase = VehiclesPhase.initial;
  List<CitizenVehicle> items = const [];
  String? message;
  bool refreshing = false,
      processing = false,
      _loading = false,
      _disposed = false;
  Future<void> load() async {
    if (_loading || _disposed) return;
    _loading = true;
    refreshing = phase == VehiclesPhase.success || phase == VehiclesPhase.empty;
    if (!refreshing) phase = VehiclesPhase.loading;
    message = null;
    notifyListeners();
    try {
      final loaded = await gateway.list();
      if (_disposed) return;
      items = List.unmodifiable(loaded);
      phase = items.isEmpty ? VehiclesPhase.empty : VehiclesPhase.success;
    } catch (error) {
      if (_disposed) return;
      message = vehicleError(error);
      if (!refreshing) phase = VehiclesPhase.error;
    } finally {
      _loading = false;
      refreshing = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<bool> create(VehicleInput input) async {
    if (processing || _disposed) return false;
    processing = true;
    message = null;
    notifyListeners();
    try {
      final created = await gateway.create(input);
      if (_disposed) return false;
      items = List.unmodifiable([
        ...items.where((v) => v.id != created.id),
        created,
      ]);
      phase = VehiclesPhase.success;
      return true;
    } catch (error) {
      if (!_disposed) message = vehicleError(error);
      return false;
    } finally {
      processing = false;
      if (!_disposed) notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
