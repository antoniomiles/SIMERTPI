import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../data/auth_service.dart';
import '../data/session_store.dart';

enum AuthPhase { initial, signedOut, processing, authenticated, error }

class AuthController extends ChangeNotifier {
  AuthController(
    this.gateway, {
    required this.store,
    DateTime Function()? clock,
  }) : _clock = clock ?? DateTime.now;
  final AuthGateway gateway;
  final SessionStore store;
  final DateTime Function() _clock;
  AuthPhase phase = AuthPhase.initial;
  String? message;
  MobileSession? _session;
  bool _busy = false, _disposed = false;
  int _generation = 0;
  Future<bool>? _refreshing;
  Future<void>? _storageWork;
  bool get isAuthenticated => _session != null;
  String? get userId => _session?.userId;
  // Serialize writes/deletes: logout always clears after any in-flight write.
  Future<void> _persist(Future<void> Function() work) {
    final next = _storageWork?.then((_) => work()) ?? work();
    _storageWork = next.catchError((Object _) {});
    return next;
  }

  Future<Map<String, String>> headers() async {
    final current = _session;
    if (current == null) return {};
    if (!current.accessExpiresAt.isAfter(_clock().toUtc())) {
      if (!await renew()) throw const AppFailure(FailureKind.unauthorized);
    }
    return _session == null
        ? {}
        : {'Authorization': 'Bearer ${_session!.accessToken}'};
  }

  Future<void> restore() async {
    if (phase != AuthPhase.initial || _busy) return;
    _busy = true;
    final generation = _generation;
    try {
      final saved = await store.read();
      if (_disposed || generation != _generation) return;
      if (saved == null) {
        phase = AuthPhase.signedOut;
        return;
      }
      if (!saved.refreshExpiresAt.isAfter(_clock().toUtc())) {
        await _persist(store.clear);
        phase = AuthPhase.signedOut;
        return;
      }
      _session = saved;
      if (!saved.accessExpiresAt.isAfter(_clock().toUtc())) {
        await renew();
      } else {
        phase = AuthPhase.authenticated;
      }
    } catch (error) {
      _session = null;
      phase = AuthPhase.signedOut;
      message = 'No pudimos restaurar tu sesión. Ingresa nuevamente.';
      try {
        await _persist(store.clear);
      } catch (_) {
        /* Fail closed; never log secure values. */
      }
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<bool> login(String username, String password) async {
    if (_busy || _disposed) return false;
    if (username.trim().isEmpty || password.trim().isEmpty) {
      message = 'Ingresa tu usuario y contraseña.';
      phase = AuthPhase.error;
      _notify();
      return false;
    }
    _busy = true;
    final generation = ++_generation;
    phase = AuthPhase.processing;
    message = null;
    _session = null;
    _notify();
    try {
      final session = await gateway.login(username.trim(), password);
      if (_disposed || generation != _generation) return false;
      if (!session.accessExpiresAt.isAfter(_clock().toUtc()) ||
          !session.refreshExpiresAt.isAfter(_clock().toUtc())) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      await _persist(() => store.write(session));
      if (_disposed || generation != _generation) return false;
      _session = session;
      phase = AuthPhase.authenticated;
      return true;
    } catch (error) {
      if (_disposed || generation != _generation) return false;
      _session = null;
      phase = AuthPhase.error;
      message = authErrorMessage(error);
      return false;
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<bool> renew({String? failedAuthorization}) async {
    if (_disposed || _session == null) return false;
    if (failedAuthorization != null &&
        failedAuthorization != 'Bearer ${_session!.accessToken}') {
      return true;
    }
    final pending = _refreshing;
    if (pending != null) return pending;
    final task = _renew();
    _refreshing = task;
    try {
      return await task;
    } finally {
      if (identical(_refreshing, task)) _refreshing = null;
    }
  }

  Future<bool> _renew() async {
    final previous = _session!;
    final generation = _generation;
    try {
      if (!previous.refreshExpiresAt.isAfter(_clock().toUtc())) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      final next = await gateway.refresh(previous.refreshToken);
      if (_disposed || generation != _generation) return false;
      await _persist(() => store.write(next));
      if (_disposed || generation != _generation) return false;
      _session = next;
      phase = AuthPhase.authenticated;
      _notify();
      return true;
    } catch (error) {
      // Unknown refresh outcome must not blindly reuse the consumed token.
      if (!_disposed && generation == _generation) {
        await logout(expired: true, remote: false);
      }
      return false;
    }
  }

  Future<bool> recoverUnauthorized(String? authorization) async =>
      renew(failedAuthorization: authorization);
  Future<void> logout({bool expired = false, bool remote = true}) async {
    final old = _session;
    ++_generation;
    _session = null;
    phase = AuthPhase.signedOut;
    message = expired ? 'Tu sesión ya no es válida. Ingresa nuevamente.' : null;
    _notify();
    try {
      await _persist(store.clear);
    } catch (_) {
      message =
          'No pudimos limpiar el almacenamiento seguro. Contacta con soporte.';
      _notify();
    }
    if (remote && old != null) {
      try {
        await gateway.logout(old.refreshToken);
      } catch (_) {
        message = 'Sesión cerrada en este dispositivo. No pudimos confirmar el cierre remoto.';
        _notify();
      }
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _session = null;
    super.dispose();
  }
}

String authErrorMessage(Object error, {bool registration = false}) {
  if (error is! AppFailure) return 'No fue posible completar la operación.';
  if (registration && error.outcomeUnknown) {
    return 'No pudimos confirmar la creación de la cuenta. Intenta ingresar antes de repetir el registro.';
  }
  return switch (error.kind) {
    FailureKind.unauthorized =>
      registration
          ? 'No pudimos completar el registro. Inténtalo nuevamente o contacta con soporte.'
          : 'No pudimos verificar tu usuario y contraseña.',
    FailureKind.forbidden =>
      'Esta cuenta no tiene acceso a la aplicación ciudadana.',
    FailureKind.conflict => 'El usuario o correo ya está registrado.',
    FailureKind.invalidRequest => 'Revisa los datos ingresados.',
    _ => error.message,
  };
}
