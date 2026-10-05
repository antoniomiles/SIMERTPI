import 'package:flutter/foundation.dart';

import '../data/parking_catalog.dart';

enum DiscoveryPhase { initial, loading, success, empty, error }

class DiscoveryController extends ChangeNotifier {
  DiscoveryController(this.gateway);
  final ParkingCatalogGateway gateway;
  DiscoveryPhase phase = DiscoveryPhase.initial;
  ParkingCatalog? catalog;
  String? zoneId, message;
  IdentifiedSpace? identified;
  bool refreshing = false,
      resolving = false,
      _loading = false,
      _disposed = false;
  List<CatalogSpace> get spaces => catalog?.inZone(zoneId) ?? [];
  Future<void> load() async {
    if (_loading || _disposed) return;
    _loading = true;
    refreshing = catalog != null;
    if (!refreshing) phase = DiscoveryPhase.loading;
    message = null;
    identified = null;
    notifyListeners();
    try {
      final result = await gateway.load();
      if (_disposed) return;
      catalog = result;
      if (!result.zones.any((z) => z.id == zoneId)) zoneId = null;
      phase = result.spaces.isEmpty && result.zones.isEmpty
          ? DiscoveryPhase.empty
          : DiscoveryPhase.success;
    } catch (error) {
      if (_disposed) return;
      message = discoveryError(error);
      // Preserve previous content, but make stale status explicit and disable selection.
      phase = catalog == null ? DiscoveryPhase.error : DiscoveryPhase.success;
    } finally {
      _loading = false;
      refreshing = false;
      if (!_disposed) notifyListeners();
    }
  }

  void selectZone(String? id) {
    if (_disposed) return;
    zoneId = id;
    notifyListeners();
  }

  Future<void> resolve(String value, {required bool qr}) async {
    if (resolving || _disposed) return;
    resolving = true;
    identified = null;
    message = null;
    notifyListeners();
    try {
      final result = await gateway.identify(value, qr: qr);
      if (!_disposed) {
        identified = result;
        catalog = result.catalog;
        if (!result.catalog.zones.any((z) => z.id == zoneId)) zoneId = null;
        phase = DiscoveryPhase.success;
      }
    } catch (error) {
      if (!_disposed) message = discoveryError(error);
    } finally {
      resolving = false;
      if (!_disposed) notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
