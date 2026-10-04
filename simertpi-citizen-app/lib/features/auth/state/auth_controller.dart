import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../data/auth_service.dart';

enum AuthPhase { initial, signedOut, processing, authenticated, error }

/// HTTP Basic has no issued token or contractual expiry. Credentials live only
/// in process memory; never serialize this controller or persist passwords.
class AuthController extends ChangeNotifier {
  AuthController(this.gateway);
  final AuthGateway gateway;
  AuthPhase phase = AuthPhase.initial;
  String? message;
  String? _authorization;
  bool _busy = false, _disposed = false;
  int _generation = 0;
  bool get isAuthenticated => _authorization != null;
  Future<Map<String, String>> headers() async =>
      _authorization == null ? {} : {'Authorization': _authorization!};

  Future<void> restore() async {
    if (phase != AuthPhase.initial) return;
    // No persistable credential exists in the backend contract. Restart means
    // signed out; do not manufacture a token, TTL or reusable cookie.
    phase = AuthPhase.signedOut;
    _notify();
  }

  Future<bool> login(String username, String password) async {
    if (_busy || _disposed) return false;
    if (username.trim().isEmpty ||
        password.trim().isEmpty ||
        username.contains(':') ||
        username.contains(RegExp(r'[\r\n]'))) {
      message = 'Ingresa tu usuario y contraseña.';
      phase = AuthPhase.error;
      _notify();
      return false;
    }
    _busy = true;
    final generation = ++_generation;
    _authorization = null;
    message = null;
    phase = AuthPhase.processing;
    _notify();
    try {
      await gateway.verifyCitizen(username.trim(), password);
      if (_disposed || generation != _generation) {
        return false;
      }
      _authorization = basicAuthorization(username.trim(), password);
      phase = AuthPhase.authenticated;
      return true;
    } catch (error) {
      if (_disposed || generation != _generation) {
        return false;
      }
      message = authErrorMessage(error);
      phase = AuthPhase.error;
      return false;
    } finally {
      _busy = false;
      if (!_disposed && generation == _generation) _notify();
    }
  }

  void expireIfMatches(String? authorization) {
    if (authorization != null && authorization == _authorization) {
      logout(expired: true);
    }
  }

  void logout({bool expired = false}) {
    ++_generation;
    _authorization = null;
    phase = AuthPhase.signedOut;
    message = expired ? 'Tu sesión ya no es válida. Ingresa nuevamente.' : null;
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _authorization = null;
    super.dispose();
  }
}

String authErrorMessage(Object error, {bool registration = false}) {
  if (error is! AppFailure) return 'No fue posible completar la operación.';
  if (registration && error.outcomeUnknown) {
    return 'No pudimos confirmar la creación de la cuenta. Intenta ingresar antes de repetir el registro.';
  }
  return switch (error.kind) {
    FailureKind.unauthorized => 'No pudimos verificar tu usuario y contraseña.',
    FailureKind.forbidden =>
      'Esta cuenta no tiene acceso a la aplicación ciudadana.',
    FailureKind.conflict => 'El usuario o correo ya está registrado.',
    FailureKind.invalidRequest => 'Revisa los datos ingresados.',
    _ => error.message,
  };
}
