import 'package:flutter/foundation.dart';

import '../../core/errors/app_failure.dart';
import 'activity_service.dart';

class ActivityController extends ChangeNotifier {
  ActivityController(this.gateway, this.resource);
  final ActivityGateway gateway;
  final String resource;
  List<CitizenData> items = [];
  bool loading = false, loadingMore = false, hasMore = false;
  String? error;
  int _generation = 0;
  int _nextOffset = 0;
  bool _disposed = false;
  Future<void> load({bool more = false}) async {
    if (_disposed || (more && (loading || loadingMore || !hasMore))) return;
    final generation = more ? _generation : ++_generation;
    if (more) {
      loadingMore = true;
    } else {
      loading = true;
      loadingMore = false;
    }
    error = null;
    notifyListeners();
    try {
      final offset = more ? _nextOffset : 0;
      final page = await gateway.list(resource, offset);
      if (_disposed || generation != _generation) return;
      final combined = more ? [...items, ...page.items] : page.items;
      items = {for (final row in combined) row['id']: row}.values.toList();
      _nextOffset = offset + page.items.length;
      hasMore = page.hasMore;
    } catch (e) {
      if (_disposed || generation != _generation) return;
      error = e is AppFailure
          ? e.message
          : const AppFailure(FailureKind.unknown).message;
    } finally {
      if (!_disposed && generation == _generation) {
        loading = false;
        loadingMore = false;
        notifyListeners();
      }
    }
  }

  Future<CitizenData> open(String id) async {
    if (resource != 'inbox') return gateway.detail(resource, id);
    final result = await gateway.read(id);
    if (!_disposed) {
      items = [for (final row in items) row['id'] == id ? result : row];
      notifyListeners();
    }
    return result;
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    super.dispose();
  }
}
