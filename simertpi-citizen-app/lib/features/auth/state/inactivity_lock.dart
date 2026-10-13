import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter/services.dart';

abstract interface class ActivityStore {
  Future<DateTime?> read(String owner);
  Future<void> write(String owner, DateTime at);
}

class SecureActivityStore implements ActivityStore {
  SecureActivityStore(this.namespace);
  final String namespace;
  final _storage = const FlutterSecureStorage();
  String _key(String owner) => 'simertpi.activity.$namespace.$owner';
  @override
  Future<DateTime?> read(String owner) async =>
      DateTime.tryParse(await _storage.read(key: _key(owner)) ?? '');
  @override
  Future<void> write(String owner, DateTime at) =>
      _storage.write(key: _key(owner), value: at.toUtc().toIso8601String());
}

/// elapsedRealtime includes Android deep sleep. Boot changes invalidate the clock.
class AndroidActivityStore implements ActivityStore {
  AndroidActivityStore(this.namespace);
  final String namespace;
  static const _channel = MethodChannel('simertpi/device_security');
  @override
  Future<DateTime?> read(String owner) async {
    final age = await _channel.invokeMethod<int>('activityAge', {
      'namespace': namespace,
      'owner': owner,
    });
    return age == null
        ? null
        : DateTime.now().toUtc().subtract(Duration(milliseconds: age));
  }

  @override
  Future<void> write(String owner, DateTime at) => _channel.invokeMethod<void>(
    'activityWrite',
    {'namespace': namespace, 'owner': owner, 'at': at.millisecondsSinceEpoch},
  );
}

/// Persisted wall clock covers suspend/restart; backwards clock fails closed.
/// Timer only wakes the UI. HTTP, rebuilds and theme changes never call activity.
class InactivityLock extends ChangeNotifier {
  InactivityLock({DateTime Function()? clock, this.store})
    : _clock = clock ?? DateTime.now;
  static const limit = Duration(minutes: 5);
  final DateTime Function() _clock;
  final ActivityStore? store;
  DateTime? _last;
  String? _owner;
  bool locked = false, _disposed = false;
  Timer? _timer;
  int _generation = 0;
  Future<void> _writes = Future<void>.value();
  DateTime get now => _clock().toUtc();
  Future<void> attach(String owner, {required bool restored}) async {
    final generation = ++_generation;
    _owner = owner;
    if (restored) {
      // Hide before any asynchronous storage read on process restoration.
      locked = true;
      notifyListeners();
      DateTime? saved;
      try {
        saved = await store?.read(owner);
      } catch (_) {
        saved = null;
      }
      if (_disposed || generation != _generation) return;
      _last = saved;
      locked =
          _last == null ||
          now.isBefore(_last!) ||
          now.difference(_last!) >= limit;
      _schedule();
      notifyListeners();
    } else {
      unlock();
    }
  }

  Future<void> resume() async {
    final owner = _owner, generation = _generation;
    if (owner == null || locked) return;
    await flush();
    if (store != null) {
      DateTime? saved;
      try {
        saved = await store!.read(owner);
      } catch (_) {
        saved = null;
      }
      if (_disposed || generation != _generation) return;
      _last = saved;
    }
    check();
  }

  void detach() {
    ++_generation;
    _timer?.cancel();
    _owner = null;
    _last = null;
    locked = false;
    if (!_disposed) notifyListeners();
  }

  void check() {
    if (_owner == null || locked) return;
    if (_last == null ||
        now.isBefore(_last!) ||
        now.difference(_last!) >= limit) {
      lock();
    } else {
      _schedule();
    }
  }

  void activity() {
    check();
    if (_owner == null || locked) return;
    _last = now;
    _save();
    _schedule();
  }

  void lock() {
    if (_owner == null || locked) return;
    locked = true;
    _timer?.cancel();
    notifyListeners();
  }

  void unlock() {
    if (_owner == null) return;
    locked = false;
    _last = now;
    _save();
    _schedule();
    if (!_disposed) notifyListeners();
  }

  void _save() {
    final owner = _owner, at = _last;
    if (owner == null || at == null || store == null) return;
    _writes = _writes.then((_) => store!.write(owner, at)).catchError((
      Object _,
    ) {
      // Persistence failure must not restore an unlocked process later.
    });
  }

  Future<void> flush() => _writes;
  void _schedule() {
    _timer?.cancel();
    if (_disposed || locked || _owner == null || _last == null) return;
    final delay = limit - now.difference(_last!);
    // Do not extend the deadline if wall-clock time changes while awake.
    _timer = Timer(delay.isNegative ? Duration.zero : delay, lock);
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _timer?.cancel();
    super.dispose();
  }
}
