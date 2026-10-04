import 'package:flutter/foundation.dart';

import '../errors/app_failure.dart';

enum LoadPhase { initial, loading, success, empty, error, refreshing }

class AsyncState<T> {
  const AsyncState({this.phase = LoadPhase.initial, this.data, this.failure});
  final LoadPhase phase;
  final T? data;
  final AppFailure? failure;
}

class AsyncController<T> extends ChangeNotifier {
  AsyncState<T> _state = AsyncState<T>();
  AsyncState<T> get state => _state;
  bool _disposed = false;
  bool _busy = false;
  Future<void> load(
    Future<T> Function() loader, {
    required bool Function(T) isEmpty,
    bool refresh = false,
  }) async {
    if (_busy || _disposed) return;
    _busy = true;
    _state = AsyncState(
      phase: refresh && _state.data != null
          ? LoadPhase.refreshing
          : LoadPhase.loading,
      data: refresh ? _state.data : null,
    );
    notifyListeners();
    try {
      final value = await loader();
      if (!_disposed) {
        _state = AsyncState(
          phase: isEmpty(value) ? LoadPhase.empty : LoadPhase.success,
          data: value,
        );
      }
    } catch (error) {
      if (!_disposed) {
        _state = AsyncState(
          phase: LoadPhase.error,
          data: _state.data,
          failure: error is AppFailure
              ? error
              : const AppFailure(FailureKind.unknown),
        );
      }
    } finally {
      _busy = false;
      if (!_disposed) notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
