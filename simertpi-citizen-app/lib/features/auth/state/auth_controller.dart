import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../../core/logging/app_log.dart';
import '../../../core/logging/biometric_diagnostic.dart';
import '../data/auth_service.dart';
import '../data/session_store.dart';
import '../data/device_security.dart';
import '../data/device_credential.dart';
import 'inactivity_lock.dart';

enum AuthPhase {
  initial,
  signedOut,
  processing,
  authenticated,
  reauthentication,
  error,
}

class AuthController extends ChangeNotifier {
  AuthController(
    this.gateway, {
    required this.store,
    DateTime Function()? clock,
    this.onBeforeLogout,
    this.inactivity,
    this.deviceSecurity,
    this.signingDevice,
    this.credentialGateway,
  }) : _clock = clock ?? DateTime.now;
  final AuthGateway gateway;
  final SessionStore store;
  final InactivityLock? inactivity;
  final DeviceSecurity? deviceSecurity;
  final SigningDevice? signingDevice;
  final CredentialGateway? credentialGateway;
  Future<List<DeviceBinding>> biometricBindings() async =>
      await signingDevice?.bindings() ?? [];
  Future<void> markBiometricInvitation() async {
    if (signingDevice != null) {
      await signingDevice!.markInvitationPresented();
    } else {
      await declineBiometrics();
    }
  }

  BiometricStatus biometricStatus = BiometricStatus.unavailable;
  bool get isLocked =>
      isAuthenticated &&
      (inactivity?.locked == true || phase == AuthPhase.reauthentication);
  bool awaitingInvitation = false;
  void completeLoginPresentation() {
    awaitingInvitation = false;
    _notify();
  }

  bool get requiresPassword => _session?.refreshBlocked == true;
  bool get busy => _busy || isLoggingOut;
  String? get username => _session?.username;
  final DateTime Function() _clock;
  final Future<void> Function({required bool remote, required String? ownerId})?
  onBeforeLogout;
  AuthPhase phase = AuthPhase.initial;
  String? message;
  bool canRetryLogin = false;
  MobileSession? _session;
  bool _busy = false, _disposed = false;
  bool _credentialLogin = false;
  bool _biometricEnableDiagnosticActive = false;
  final Object biometricDiagnosticSource = Object();
  int _generation = 0;
  Future<bool>? _refreshing;
  Future<void>? _storageWork;
  Future<void>? _logoutInProgress;
  String? _previousAuthorization;
  bool get isLoggingOut => _logoutInProgress != null;
  bool get isAuthenticated => _session != null;
  String? get userId => _session?.userId;
  // Serialize writes/deletes: logout always clears after any in-flight write.
  Future<void> _persist(Future<void> Function() work) {
    final next = _storageWork?.then((_) => work()) ?? work();
    _storageWork = next.catchError((Object _) {});
    return next;
  }

  Future<void> _saveSession(MobileSession session, int generation) =>
      _persist(() async {
        if (_disposed || generation != _generation) return;
        await store.write(session);
        if (_disposed || generation != _generation) return;
        try {
          await deviceSecurity?.update(session);
        } catch (_) {
          // A stale biometric copy must never authorize the latest session.
          try {
            await deviceSecurity?.disable(session.userId);
          } catch (_) {}
          biometricStatus = BiometricStatus.unavailable;
        }
      });

  Future<void> refreshBiometricStatus() async {
    final owner = userId;
    if (owner == null || deviceSecurity == null) {
      biometricStatus = BiometricStatus.unavailable;
      return;
    }
    var value = await deviceSecurity!.status(owner);
    if (signingDevice != null && value != BiometricStatus.unavailable) {
      final bindings = await signingDevice!.bindings();
      value = bindings.any((b) => b.owner == owner && b.usable)
          ? BiometricStatus.enabled
          : BiometricStatus.disabled;
    }
    if (userId == owner && !_disposed) {
      biometricStatus = value;
      _notify();
    }
  }

  Future<bool> shouldInviteBiometrics() async {
    if (!isAuthenticated || isLocked || deviceSecurity == null) return false;
    try {
      if (signingDevice != null) {
        return biometricStatus == BiometricStatus.disabled &&
            !await signingDevice!.invitationPresented();
      }
      return await deviceSecurity!.shouldInvite(userId!);
    } catch (_) {
      return false;
    }
  }

  Future<void> declineBiometrics() async {
    if (userId != null) await deviceSecurity?.decline(userId!);
  }

  Future<bool> enableBiometrics() =>
      BiometricDiagnostics.capture(_enableBiometrics, source: biometricDiagnosticSource);

  Future<bool> _enableBiometrics() async {
    if (busy || isLocked || _session == null || deviceSecurity == null) {
      return false;
    }
    _busy = true;
    message = null;
    _notify();
    final generation = _generation;
    Object? biometricDiagnosticError;
    bool biometricDiagnosticSuccess = false;
    _biometricEnableDiagnosticActive = true;
    try {
      // Always verify the backend, even if an access token looks unexpired.
      const bioLog = AppLog();
      bioLog.biometric('BIO_ENABLE_START');
      bioLog.biometric('BIO_SESSION_RENEW_START');
      if (!await renew() || generation != _generation || _session == null) {
        bioLog.biometric('BIO_SESSION_RENEW_FAILED');
        return false;
      }
      bioLog.biometric('BIO_SESSION_RENEW_OK');
      if (signingDevice != null && credentialGateway != null) {
        await _registerCredential(_session!);
      } else {
        await deviceSecurity!.enable(_session!);
      }
      if (generation != _generation || _disposed) return false;
      await bioLog.biometricStep('BIO_LOCAL_STATE', () async {
        await deviceSecurity!.update(_session!);
        await refreshBiometricStatus();
      });
      biometricDiagnosticSuccess = biometricStatus == BiometricStatus.enabled;
      return biometricDiagnosticSuccess;
    } catch (error) {
      biometricDiagnosticError = error;
      message = biometricErrorMessage(error, activating: true);
      if (error is AppFailure &&
          error.kind == FailureKind.unauthorized &&
          _session != null) {
        phase = AuthPhase.reauthentication;
        inactivity?.lock();
        message =
            'Ingresa con tu contraseña para autorizar la activación biométrica';
      }
      return false;
    } finally {
      const AppLog().biometric(
        'BIO_ENABLE_COMPLETED',
        error: biometricDiagnosticError,
        success: biometricDiagnosticSuccess,
      );
      _biometricEnableDiagnosticActive = false;
      if (generation == _generation) _busy = false;
      _notify();
    }
  }

  Future<void> disableBiometrics() async {
    final owner = userId;
    if (busy || isLocked || owner == null || deviceSecurity == null) return;
    _busy = true;
    final generation = _generation;
    _notify();
    try {
      if (signingDevice != null && credentialGateway != null) {
        if (!await renew() || generation != _generation || _session == null) {
          return;
        }
        for (final binding in await signingDevice!.bindings()) {
          if (binding.owner != owner) continue;
          await credentialGateway!.revoke(
            _session!.accessToken,
            binding.credentialId,
          );
          await signingDevice!.removeBinding(owner);
        }
      }
      await deviceSecurity!.disable(owner);
      await deviceSecurity!.decline(owner);
      biometricStatus = BiometricStatus.disabled;
    } catch (_) {
      message = 'No pudimos desactivar la biometría. Inténtalo nuevamente';
    } finally {
      if (generation == _generation) _busy = false;
      _notify();
    }
  }

  Future<bool> unlockPassword(
    String username,
    String password, {
    bool configureBiometrics = false,
  }) async {
    final previous = _session;
    if (busy || _disposed || previous == null || password.trim().isEmpty) {
      return false;
    }
    _busy = true;
    final generation = ++_generation;
    _credentialLogin = true;
    canRetryLogin = false;
    _previousAuthorization = null;
    _refreshing = null;
    message = null;
    _notify();
    try {
      if (previous.refreshBlocked) {
        try {
          await onBeforeLogout
              ?.call(remote: false, ownerId: previous.userId)
              .timeout(const Duration(seconds: 3));
        } catch (_) {
          message = 'No pudimos cerrar de forma segura las notificaciones anteriores.';
          return false;
        }
      }
      final next = (await gateway.login(
        username.trim(),
        password,
      )).copyWith(username: username.trim());
      if (_disposed || generation != _generation) return false;
      if (next.userId != previous.userId) {
        try {
          await gateway.logout(next.refreshToken);
        } catch (_) {}
        message = 'Utiliza la contraseña de la cuenta actual. Para cambiar de cuenta, cierra sesión';
        return false;
      }
      _validateSession(next);
      await _saveSession(next, generation);
      if (_disposed || generation != _generation) return false;
      _session = next;
      phase = AuthPhase.authenticated;
      if (configureBiometrics &&
          signingDevice != null &&
          credentialGateway != null) {
        try {
          await _registerCredential(next);
          await refreshBiometricStatus();
        } catch (error) {
          message = biometricErrorMessage(error, activating: true);
        }
      }
      if (generation != _generation || _disposed) return false;
      inactivity?.unlock();
      try {
        await gateway.logout(previous.refreshToken);
      } catch (_) {}
      return true;
    } catch (error) {
      if (generation == _generation) {
        message = authErrorMessage(error);
        canRetryLogin = _temporaryCredentialError(error);
      }
      return false;
    } finally {
      if (generation == _generation) {
        _busy = false;
        _credentialLogin = false;
      }
      _notify();
    }
  }

  Future<bool> unlockBiometrics() async {
    final previous = _session;
    if (busy ||
        previous == null ||
        requiresPassword ||
        deviceSecurity == null) {
      return false;
    }
    _busy = true;
    message = null;
    _notify();
    final generation = _generation;
    try {
      if (signingDevice != null && credentialGateway != null) {
        final bindings = await signingDevice!.bindings();
        final binding = bindings
            .where((b) => b.owner == previous.userId)
            .firstOrNull;
        if (binding == null) throw const AppFailure(FailureKind.unauthorized);
        // Preserve the existing session. Refresh verifies validity, not new login.
        if (!await renew() || generation != _generation || _session == null) {
          return false;
        }
        final session = _session!;
        final challenge = await credentialGateway!.challenge(
          binding.credentialId,
          access: session.accessToken,
        );
        final proof = await _signedProof(binding, challenge, 'UNLOCK');
        if (generation != _generation || _disposed) return false;
        await credentialGateway!.unlock(session.accessToken, proof);
        if (generation != _generation || _disposed) return false;
        inactivity?.unlock();
        return true;
      }
      final authorized = await deviceSecurity!.unlock(previous.userId);
      if (_disposed || generation != _generation) return false;
      if (authorized.userId != previous.userId ||
          authorized.refreshToken != previous.refreshToken ||
          authorized.refreshBlocked) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      // Native authentication never substitutes for a backend-valid session.
      if (!await renew() || generation != _generation || _session == null) {
        return false;
      }
      inactivity?.unlock();
      return true;
    } catch (error) {
      if (generation == _generation) {
        message = biometricErrorMessage(error);
      }
      await refreshBiometricStatus();
      return false;
    } finally {
      if (generation == _generation) _busy = false;
      _notify();
    }
  }

  Future<Map<String, dynamic>> _signedProof(
    DeviceBinding binding,
    Map<String, dynamic> challenge,
    String purpose,
  ) async {
    final payload = challenge['payload'] as String;
    final parts = payload.split('\n');
    if (parts.length != 6 ||
        parts[0] != 'SIMERTPI-DEVICE-V1' ||
        parts[1] != purpose ||
        parts[2] != binding.owner ||
        parts[3] != binding.credentialId ||
        parts[4] != challenge['challengeId'] ||
        challenge['credentialId'] != binding.credentialId ||
        !DateTime.parse(challenge['expiresAt'] as String)
            .isAfter(_clock().toUtc())) {
      throw const AppFailure(FailureKind.unauthorized);
    }
    final signature = await signingDevice!.sign(binding.owner, payload);
    return {
      'credentialId': binding.credentialId,
      'challengeId': challenge['challengeId'],
      'signature': signature,
    };
  }

  Future<void> _registerCredential(MobileSession session) async {
    final generation = _generation;
    for (final existing in await signingDevice!.bindings()) {
      if (existing.owner != session.userId) continue;
      if (existing.usable) return;
      await credentialGateway!.revoke(
        session.accessToken,
        existing.credentialId,
      );
      await signingDevice!.removeBinding(existing.owner);
    }
    const bioLog = AppLog();
    final key = await bioLog.biometricStep(
      'BIO_KEYSTORE_PREPARE',
      () => signingDevice!.prepare(session.userId),
    );
    final challenge = await bioLog.biometricStep(
      'BIO_REGISTER_CHALLENGE',
      () => credentialGateway!.registration(session.accessToken, key),
    );
    final binding = DeviceBinding(
      session.userId,
      session.username!,
      challenge['credentialId'] as String,
      key['deviceId']!,
    );
    final proof = await bioLog.biometricStep(
      'BIO_ANDROID_PROMPT',
      () => _signedProof(binding, challenge, 'REGISTER'),
    );
    if (generation != _generation || _disposed) {
      throw const AppFailure(FailureKind.unauthorized);
    }
    await bioLog.biometricStep(
      'BIO_REGISTER_VERIFY',
      () => credentialGateway!.register(session.accessToken, proof),
    );
    if (generation != _generation || _disposed) {
      try {
        await credentialGateway!.revoke(
          session.accessToken,
          binding.credentialId,
        );
      } catch (_) {}
      return;
    }
    try {
      await signingDevice!.commitBinding(binding);
      bioLog.biometric('BIO_LOCAL_BINDING_OK');
      await signingDevice!.markInvitationPresented();
    } catch (error) {
      bioLog.biometric('BIO_LOCAL_BINDING_FAILED', error: error);
      rethrow;
    }
  }

  Future<bool> loginBiometrics(DeviceBinding binding) async {
    if (isLocked) return binding.owner == userId && await unlockBiometrics();
    if (busy ||
        _disposed ||
        isAuthenticated ||
        signingDevice == null ||
        credentialGateway == null) {
      return false;
    }
    _busy = true;
    _credentialLogin = true;
    final generation = ++_generation;
    message = null;
    _notify();
    MobileSession? issued;
    try {
      await onBeforeLogout
          ?.call(remote: false, ownerId: null)
          .timeout(const Duration(seconds: 3));
      final known = await signingDevice!.bindings();
      if (!known.any(
        (b) =>
            b.owner == binding.owner && b.credentialId == binding.credentialId,
      )) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      final challenge = await credentialGateway!.challenge(
        binding.credentialId,
      );
      final proof = await _signedProof(binding, challenge, 'LOGIN');
      if (generation != _generation || _disposed) return false;
      issued = (await credentialGateway!.authenticate(proof))
          .copyWith(username: binding.username);
      if (issued.userId != binding.owner) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      _validateSession(issued);
      if (generation != _generation || _disposed) {
        try {
          await gateway.logout(issued.refreshToken);
        } catch (_) {}
        return false;
      }
      await _saveSession(issued, generation);
      if (generation != _generation || _disposed) return false;
      _session = issued;
      phase = AuthPhase.authenticated;
      awaitingInvitation = false;
      await inactivity?.attach(issued.userId, restored: false);
      await refreshBiometricStatus();
      return true;
    } catch (error) {
      if (issued != null) {
        try {
          await gateway.logout(issued.refreshToken);
        } catch (_) {}
      }
      if (generation == _generation) {
        message = error is AppFailure
            ? error.kind == FailureKind.unauthorized
                  ? 'No pudimos verificar tu autorización biométrica. Ingresa con tu contraseña o inténtalo más tarde'
                  : authErrorMessage(error)
            : biometricErrorMessage(error);
        phase = AuthPhase.signedOut;
      }
      return false;
    } finally {
      if (generation == _generation) {
        _busy = false;
        _credentialLogin = false;
      }
      _notify();
    }
  }

  void _validateSession(MobileSession session) {
    if (!session.accessExpiresAt.isAfter(_clock().toUtc()) ||
        !session.refreshExpiresAt.isAfter(_clock().toUtc())) {
      throw const AppFailure(FailureKind.unauthorized);
    }
  }

  Future<Map<String, String>> headers() async {
    final current = _session;
    final generation = _generation;
    if (current == null) return {};
    if (!current.accessExpiresAt.isAfter(_clock().toUtc())) {
      if (!await renew()) {
        throw AppFailure(
          FailureKind.unavailable,
          messageOverride:
              message ?? 'Ingresa con tu contraseña para recuperar la sesión',
        );
      }
    }
    if (generation != _generation ||
        _disposed ||
        _session?.userId != current.userId) {
      throw const AppFailure(FailureKind.unauthorized);
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
        try {
          await onBeforeLogout
              ?.call(remote: false, ownerId: null)
              .timeout(const Duration(seconds: 3));
        } catch (_) {
          message = 'No pudimos cerrar de forma segura las notificaciones anteriores.';
        }
        phase = AuthPhase.signedOut;
        return;
      }
      if (deviceSecurity != null) {
        var trustedDevice = false;
        try {
          trustedDevice = await deviceSecurity!.verifySession(saved);
        } catch (_) {}
        if (!trustedDevice) {
          // Migration/new device or logout tombstone: full login, never refresh.
          // Retain encrypted material; unavailable local proof is not revocation.
          phase = AuthPhase.signedOut;
          message = 'Ingresa con tu usuario y contraseña para verificar este dispositivo';
          return;
        }
      }
      if (!saved.refreshExpiresAt.isAfter(_clock().toUtc())) {
        try {
          await onBeforeLogout
              ?.call(remote: false, ownerId: saved.userId)
              .timeout(const Duration(seconds: 3));
          await _persist(() async {
            try {
              await deviceSecurity?.invalidateSession(saved.userId);
            } finally {
              await store.clear();
            }
          });
        } catch (_) {
          // Preserve the prior owner for safe cleanup retry before another login.
          message = 'No pudimos cerrar de forma segura las notificaciones anteriores.';
        }
        phase = AuthPhase.signedOut;
        return;
      }
      _session = saved;
      await inactivity?.attach(saved.userId, restored: true);
      await refreshBiometricStatus();
      if (saved.refreshBlocked) {
        phase = AuthPhase.reauthentication;
        message = 'Ingresa con tu contraseña para recuperar la sesión de forma segura';
      } else if (!saved.accessExpiresAt.isAfter(_clock().toUtc())) {
        await renew();
      } else {
        phase = AuthPhase.authenticated;
      }
    } catch (error) {
      _session = null;
      phase = AuthPhase.signedOut;
      message = 'No pudimos restaurar tu sesión. Ingresa nuevamente.';
      try {
        await onBeforeLogout
            ?.call(remote: false, ownerId: null)
            .timeout(const Duration(seconds: 3));
        await _persist(store.clear);
      } catch (_) {
        message =
            'No pudimos cerrar de forma segura las notificaciones anteriores.';
      }
    } finally {
      if (generation == _generation) _busy = false;
      _notify();
    }
  }

  Future<bool> login(String username, String password) async {
    if (isLocked) return unlockPassword(username, password);
    if (busy || _disposed) return false;
    if (username.trim().isEmpty || password.trim().isEmpty) {
      message = 'Ingresa tu usuario y contraseña.';
      phase = AuthPhase.error;
      _notify();
      return false;
    }
    _busy = true;
    final generation = ++_generation;
    _credentialLogin = true;
    canRetryLogin = false;
    final previous = _session;
    phase = AuthPhase.processing;
    _previousAuthorization = null;
    message = null;
    _notify();
    try {
      try {
        // Clear stale native authorization before every login, including the
        // first login after restoration found no usable secure session.
        await onBeforeLogout
            ?.call(remote: false, ownerId: previous?.userId)
            .timeout(const Duration(seconds: 3));
      } catch (_) {
        if (!_disposed && generation == _generation) {
          _session = previous;
          phase = previous == null
              ? AuthPhase.signedOut
              : AuthPhase.authenticated;
          message = 'No pudimos cerrar de forma segura las notificaciones anteriores.';
        }
        return false;
      }
      await _persist(store.clear);
      if (_disposed || generation != _generation) return false;
      _session = null;
      final session = (await gateway.login(
        username.trim(),
        password,
      )).copyWith(username: username.trim());
      if (_disposed || generation != _generation) return false;
      if (!session.accessExpiresAt.isAfter(_clock().toUtc()) ||
          !session.refreshExpiresAt.isAfter(_clock().toUtc())) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      await _saveSession(session, generation);
      if (_disposed || generation != _generation) return false;
      _session = session;
      phase = AuthPhase.authenticated;
      awaitingInvitation = deviceSecurity != null;
      await inactivity?.attach(session.userId, restored: false);
      await refreshBiometricStatus();
      return true;
    } catch (error) {
      if (_disposed || generation != _generation) return false;
      _session = null;
      phase = AuthPhase.error;
      message = authErrorMessage(error);
      canRetryLogin = _temporaryCredentialError(error);
      return false;
    } finally {
      if (generation == _generation) {
        _busy = false;
        _credentialLogin = false;
      }
      _notify();
    }
  }

  Future<bool> renew({String? failedAuthorization}) async {
    if (_disposed || _credentialLogin || isLoggingOut || _session == null) {
      return false;
    }
    if (failedAuthorization != null &&
        failedAuthorization != 'Bearer ${_session!.accessToken}') {
      // Do not replay a previous account's request using the new account's token.
      return _previousAuthorization == failedAuthorization;
    }
    final pending = _refreshing;
    if (pending != null) return pending;
    if (requiresPassword) {
      phase = AuthPhase.reauthentication;
      message ??=
          'Ingresa con tu contraseña para recuperar la sesión de forma segura';
      _notify();
      return false;
    }
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
      // Journal BEFORE transmission. Never reuse this token after lost response,
      // storage error or process death (server revokes on refresh reuse).
      final sending = previous.copyWith(refreshBlocked: true);
      await _saveSession(sending, generation);
      if (_disposed || generation != _generation) return false;
      _session = sending;
      final next = (await gateway.refresh(previous.refreshToken))
          .copyWith(username: previous.username);
      if (_disposed || generation != _generation) return false;
      if (next.userId != previous.userId) {
        throw const AppFailure(FailureKind.unauthorized);
      }
      _validateSession(next);
      await _saveSession(next, generation);
      if (_disposed || generation != _generation) return false;
      _session = next;
      _previousAuthorization = 'Bearer ${previous.accessToken}';
      phase = AuthPhase.authenticated;
      _notify();
      return true;
    } catch (error) {
      if (_biometricEnableDiagnosticActive) {
        const AppLog().biometric(
          'BIO_SESSION_RENEW_FAILED',
          error: error,
          path: '/api/v1/auth/refresh',
        );
      }
      if (!_disposed && generation == _generation) {
        if (error is AppFailure && error.kind == FailureKind.unauthorized) {
          await logout(expired: true, remote: false);
        } else {
          _session = previous.copyWith(refreshBlocked: true);
          phase = AuthPhase.reauthentication;
          message =
              '${authErrorMessage(error)}. Ingresa con tu contraseña para recuperar la sesión de forma segura';
          inactivity?.lock();
          _notify();
        }
      }
      return false;
    }
  }

  Future<bool> recoverUnauthorized(String? authorization) async =>
      renew(failedAuthorization: authorization);
  Future<void> rejectAuthorization(String? authorization) async {
    if (authorization == 'Bearer ${_session?.accessToken}' && !isLoggingOut) {
      await logout(expired: true, remote: false);
    }
  }

  Future<void> logout({bool expired = false, bool remote = true}) {
    final running = _logoutInProgress;
    if (running != null) return running;
    final task = _performLogout(expired: expired, remote: remote);
    _logoutInProgress = task;
    return task.whenComplete(() {
      if (identical(_logoutInProgress, task)) _logoutInProgress = null;
      _notify();
    });
  }

  Future<void> _performLogout({
    required bool expired,
    required bool remote,
  }) async {
    ++_generation; // Invalidate late refresh/login before asynchronous PUSH cleanup.
    _busy = false;
    _credentialLogin = false;
    canRetryLogin = false;
    _previousAuthorization = null;
    final old = _session;
    if (old != null) {
      try {
        // Device-only tombstone survives secure-store deletion failure/transfer.
        await deviceSecurity?.invalidateSession(old.userId);
        await onBeforeLogout
            ?.call(remote: remote, ownerId: old.userId)
            .timeout(const Duration(seconds: 3));
      } catch (_) {
        // Keep the current account/session in place. In particular, never let
        // another account log in while native FCM authorization may remain open.
        message = 'No pudimos cerrar de forma segura las notificaciones de este dispositivo.';
        _session = old.copyWith(refreshBlocked: true);
        phase = AuthPhase.reauthentication;
        inactivity?.lock();
        try {
          await _persist(() => store.write(_session!));
        } catch (_) {}
        _notify();
        return;
      }
    }
    ++_generation;
    _session = null;
    awaitingInvitation = false;
    inactivity?.detach();
    biometricStatus = BiometricStatus.disabled;
    phase = AuthPhase.signedOut;
    message = expired
        ? 'Tu sesión ha finalizado. Ingresa nuevamente para continuar'
        : null;
    _notify();
    try {
      await _persist(() async {
        try {
          if (old != null) await deviceSecurity?.disable(old.userId);
        } finally {
          await store.clear();
        }
      });
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
          : 'Usuario o contraseña incorrectos. Verifica tus datos',
    FailureKind.forbidden => 'No tienes autorización para realizar esta acción',
    FailureKind.offline => 'No tienes conexión a internet. Verifica tu conexión e inténtalo nuevamente',
    FailureKind.timeout =>
      'La conexión está tardando más de lo esperado. Inténtalo nuevamente',
    FailureKind.unavailable => 'SIMERTPI no está disponible en este momento. Inténtalo nuevamente en unos minutos',
    FailureKind.unknown when error.code == 'NETWORK_UNREACHABLE' => 'No tienes conexión a internet. Verifica tu conexión e inténtalo nuevamente',
    FailureKind.conflict => 'El usuario o correo ya está registrado.',
    FailureKind.invalidRequest => 'Revisa los datos ingresados.',
    _ => error.message,
  };
}

bool _temporaryCredentialError(Object error) =>
    error is AppFailure &&
    ({
          FailureKind.offline,
          FailureKind.timeout,
          FailureKind.unavailable,
        }.contains(error.kind) ||
        error.code == 'NETWORK_UNREACHABLE');
