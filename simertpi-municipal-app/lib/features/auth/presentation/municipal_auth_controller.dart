import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/municipal_auth_failure.dart';
import '../domain/municipal_auth_repository.dart';
import '../domain/municipal_session.dart';

class MunicipalAuthController extends ChangeNotifier {
  MunicipalAuthController(this._repository, {DateTime Function()? clock})
    : _clock = clock ?? DateTime.now;

  final MunicipalAuthRepository _repository;
  final DateTime Function() _clock;
  MunicipalAuthStatus _status = MunicipalAuthStatus.unconfigured;
  MunicipalSession? _session;
  MunicipalAuthFailure? _failure;
  bool _restoreFailed = false;
  bool _disposed = false;
  Future<void>? _initialization;
  Future<void>? _renewal;
  Timer? _refreshTimer;

  MunicipalAuthStatus get status => _status;
  MunicipalSession? get session => _session;
  MunicipalAuthFailure? get failure => _failure;
  bool get canRetryRestore => _restoreFailed;
  bool get isBusy =>
      _status == MunicipalAuthStatus.authenticating ||
      _status == MunicipalAuthStatus.unconfigured;

  Future<void> initialize() => _initialization ??= _restore();

  Future<void> retryRestore() {
    _initialization = null;
    return initialize();
  }

  Future<void> _restore() async {
    _status = MunicipalAuthStatus.unconfigured;
    _failure = null;
    _restoreFailed = false;
    _notify();
    try {
      _session = await _repository.restoreSession();
      _status = _session == null
          ? MunicipalAuthStatus.signedOut
          : MunicipalAuthStatus.authenticated;
      if (_session != null) _scheduleRefresh(_session!);
    } on MunicipalAuthException catch (error) {
      _session = null;
      _failure = error.failure;
      _status = MunicipalAuthStatus.failed;
      _restoreFailed = true;
    } catch (_) {
      _session = null;
      _failure = const MunicipalAuthFailure(
        MunicipalAuthFailureKind.unavailable,
      );
      _status = MunicipalAuthStatus.failed;
      _restoreFailed = true;
    }
    _notify();
  }

  Future<void> signIn({
    required String username,
    required String password,
  }) async {
    if (_status == MunicipalAuthStatus.authenticating) return;
    _status = MunicipalAuthStatus.authenticating;
    _failure = null;
    _restoreFailed = false;
    _notify();
    try {
      _session = await _repository.authenticate(
        username: username,
        password: password,
      );
      _status = MunicipalAuthStatus.authenticated;
      _scheduleRefresh(_session!);
      _initialization = Future<void>.value();
    } on MunicipalAuthException catch (error) {
      _session = null;
      _failure = error.failure;
      _status = MunicipalAuthStatus.failed;
    } catch (_) {
      _session = null;
      _failure = const MunicipalAuthFailure(
        MunicipalAuthFailureKind.unavailable,
      );
      _status = MunicipalAuthStatus.failed;
    }
    _notify();
  }

  Future<void> signOut() async {
    if (_status == MunicipalAuthStatus.authenticating) return;
    _refreshTimer?.cancel();
    _refreshTimer = null;
    _status = MunicipalAuthStatus.authenticating;
    _failure = null;
    _notify();
    try {
      await _repository.signOut();
    } on MunicipalAuthException catch (error) {
      _failure = error.failure;
    } catch (_) {
      _failure = const MunicipalAuthFailure(
        MunicipalAuthFailureKind.unavailable,
      );
    }
    _session = null;
    _status = MunicipalAuthStatus.signedOut;
    _restoreFailed = false;
    _initialization = Future<void>.value();
    _notify();
  }

  Future<void> onResume() async {
    if (_status == MunicipalAuthStatus.authenticated) {
      await _renewIfNeeded();
    }
  }

  Future<void> _renewIfNeeded() {
    final running = _renewal;
    if (running != null) return running;
    final session = _session;
    if (_status != MunicipalAuthStatus.authenticated ||
        session == null ||
        session.accessExpiresAt.isAfter(
          _clock().toUtc().add(const Duration(seconds: 30)),
        )) {
      return Future<void>.value();
    }
    final future = _renewSession();
    _renewal = future;
    return future.whenComplete(() {
      if (identical(_renewal, future)) _renewal = null;
    });
  }

  Future<void> _renewSession() async {
    try {
      final renewed = await _repository.renewSession();
      if (_status != MunicipalAuthStatus.authenticated) return;
      _session = renewed;
      _failure = null;
      _scheduleRefresh(renewed);
    } on MunicipalAuthException catch (error) {
      if (_status == MunicipalAuthStatus.authenticated) {
        await _handleRenewalFailure(error.failure);
      }
    } catch (_) {
      if (_status == MunicipalAuthStatus.authenticated) {
        await _handleRenewalFailure(
          const MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
        );
      }
    }
    _notify();
  }

  Future<void> _handleRenewalFailure(MunicipalAuthFailure failure) async {
    _failure = failure;
    final session = _session;
    final expired =
        session == null || !session.accessExpiresAt.isAfter(_clock().toUtc());
    if (expired ||
        failure.kind == MunicipalAuthFailureKind.unauthenticated ||
        failure.kind == MunicipalAuthFailureKind.forbidden ||
        failure.kind == MunicipalAuthFailureKind.secureStorage) {
      _refreshTimer?.cancel();
      _session = null;
      _status = MunicipalAuthStatus.failed;
      try {
        await _repository.signOut();
      } catch (_) {
        // The protected area remains closed even if local storage reports an error.
      }
      return;
    }
    final remaining = session.accessExpiresAt.difference(_clock().toUtc());
    final retryDelay = remaining < const Duration(seconds: 15)
        ? (remaining.isNegative ? Duration.zero : remaining)
        : const Duration(seconds: 15);
    _refreshTimer?.cancel();
    _refreshTimer = Timer(retryDelay, () => unawaited(_renewIfNeeded()));
  }

  void _scheduleRefresh(MunicipalSession session) {
    _refreshTimer?.cancel();
    final delay = session.accessExpiresAt
        .subtract(const Duration(seconds: 30))
        .difference(_clock().toUtc());
    _refreshTimer = Timer(
      delay.isNegative ? Duration.zero : delay,
      () => unawaited(_renewIfNeeded()),
    );
  }

  @override
  void dispose() {
    _disposed = true;
    _refreshTimer?.cancel();
    super.dispose();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }
}
