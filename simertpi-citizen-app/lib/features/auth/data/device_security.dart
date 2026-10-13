import 'dart:convert';

import 'package:flutter/services.dart';

import 'session_store.dart';

enum BiometricStatus { enabled, disabled, unavailable }

abstract interface class DeviceSecurity {
  Future<BiometricStatus> status(String owner);
  Future<bool> shouldInvite(String owner);
  Future<void> decline(String owner);
  Future<void> enable(MobileSession session);
  Future<void> update(MobileSession session);
  Future<MobileSession> unlock(String owner);
  Future<void> disable(String owner);
  Future<void> protect(bool locked);
  Future<bool> verifySession(MobileSession session);
  Future<void> invalidateSession(String owner);
}

/// Android authorizes private-key decryption, not a Dart boolean. No password.
class AndroidDeviceSecurity implements DeviceSecurity {
  AndroidDeviceSecurity(this.namespace);
  final String namespace;
  static const channel = MethodChannel('simertpi/device_security');
  Future<T?> _call<T>(String method, [Map<String, Object?> args = const {}]) =>
      channel.invokeMethod<T>(method, {'namespace': namespace, ...args});
  @override
  Future<BiometricStatus> status(String owner) async {
    try {
      final value = await _call<String>('status', {'owner': owner});
      return BiometricStatus.values.firstWhere(
        (s) => s.name == value,
        orElse: () => BiometricStatus.unavailable,
      );
    } on PlatformException {
      return BiometricStatus.unavailable;
    } on MissingPluginException {
      return BiometricStatus.unavailable;
    }
  }

  @override
  Future<bool> shouldInvite(String owner) async =>
      await status(owner) == BiometricStatus.disabled &&
      await _call<bool>('shouldInvite', {'owner': owner}) == true;
  @override
  Future<void> decline(String owner) async {
    await _call<void>('decline', {'owner': owner});
  }

  @override
  Future<void> enable(MobileSession session) async {
    await _call<void>('enable', {
      'owner': session.userId,
      'session': jsonEncode(session.toJson()),
    });
  }

  @override
  Future<void> update(MobileSession session) async {
    await _call<void>('update', {
      'owner': session.userId,
      'session': jsonEncode(session.toJson()),
    });
  }

  @override
  Future<MobileSession> unlock(String owner) async => MobileSession.fromJson(
    jsonDecode((await _call<String>('unlock', {'owner': owner}))!),
  );
  @override
  Future<void> disable(String owner) async {
    await _call<void>('disable', {'owner': owner});
  }

  @override
  Future<bool> verifySession(MobileSession session) async =>
      await _call<bool>('verifySession', {
        'owner': session.userId,
        'session': jsonEncode(session.toJson()),
      }) ==
      true;
  @override
  Future<void> invalidateSession(String owner) async {
    await _call<void>('invalidateSession', {'owner': owner});
  }

  @override
  Future<void> protect(bool locked) async {
    await _call<void>('protect', {'locked': locked});
  }
}

String biometricErrorMessage(Object error, {bool activating = false}) {
  final code = error is PlatformException ? error.code : '';
  return switch (code) {
    'LOCKOUT' => 'Por seguridad, Android ha bloqueado temporalmente la biometría. Utiliza tu contraseña',
    'LOCKOUT_PERMANENT' => 'Por seguridad, Android ha bloqueado la biometría. Desbloquea el teléfono con su PIN y utiliza tu contraseña',
    'NOT_RECOGNIZED' => 'No pudimos verificar tu identidad. Inténtalo nuevamente o utiliza tu contraseña',
    _ when activating => 'No pudimos activar el ingreso biométrico. Puedes intentarlo más tarde desde tu perfil',
    _ => 'No se puede utilizar la biometría en este momento. Ingresa con tu contraseña',
  };
}
