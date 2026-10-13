import 'dart:developer' as developer;
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../errors/app_failure.dart';

class AppLog {
  const AppLog();
  static const biometricCollectorKey = #biometricCollector;
  static const bioStages = {
    'BIO_ENABLE_START',
    'BIO_SESSION_RENEW_START',
    'BIO_SESSION_RENEW_OK',
    'BIO_SESSION_RENEW_FAILED',
    'BIO_KEYSTORE_PREPARE_START',
    'BIO_KEYSTORE_PREPARE_OK',
    'BIO_KEYSTORE_PREPARE_FAILED',
    'BIO_REGISTER_CHALLENGE_START',
    'BIO_REGISTER_CHALLENGE_OK',
    'BIO_REGISTER_CHALLENGE_FAILED',
    'BIO_ANDROID_PROMPT_START',
    'BIO_ANDROID_PROMPT_OK',
    'BIO_ANDROID_PROMPT_FAILED',
    'BIO_REGISTER_VERIFY_START',
    'BIO_REGISTER_VERIFY_OK',
    'BIO_REGISTER_VERIFY_FAILED',
    'BIO_LOCAL_BINDING_OK',
    'BIO_LOCAL_BINDING_FAILED',
    'BIO_LOCAL_STATE_START',
    'BIO_LOCAL_STATE_OK',
    'BIO_LOCAL_STATE_FAILED',
    'BIO_ENABLE_COMPLETED',
  };
  static const nativeCodes = {
    'UNAVAILABLE',
    'INVALIDATED',
    'CANCELLED',
    'LOCKOUT',
    'LOCKOUT_PERMANENT',
    'NOT_RECOGNIZED',
    'PREPARE_VALIDATION',
    'BIOMETRIC_COMPATIBILITY',
    'BINDING_EXISTS',
    'KEY_DELETE',
    'KEY_SPEC',
    'KEY_GENERATION',
    'DEVICE_STORAGE',
    'SIGN_INIT_FAILED',
    'PROMPT_START_FAILED',
    'SIGNATURE_FAILED',
    'BINDING_STORAGE',
  };
  static const exceptionClasses = {
    'PlatformException',
    'MissingPluginException',
    'FormatException',
    'TypeError',
    'StateError',
    'AppFailure',
    'IllegalArgumentException',
    'IllegalStateException',
    'InvalidAlgorithmParameterException',
    'KeyStoreException',
    'KeyPermanentlyInvalidatedException',
    'UserNotAuthenticatedException',
    'UnrecoverableKeyException',
    'InvalidKeyException',
    'NoSuchAlgorithmException',
    'ProviderException',
    'SignatureException',
    'IOException',
    'JSONException',
    'SecurityException',
    'SocketException',
    'TimeoutException',
    'HandshakeException',
    'TlsException',
    'HttpException',
  };

  /// Only allowlisted metadata. Never stringify exceptions or accept bodies.
  static String? biometricLine(
    String stage, {
    Object? error,
    int? status,
    String? path,
    String? correlationId,
    String source = 'FLUTTER',
    bool? success,
  }) {
    if (!bioStages.contains(stage) || !{'FLUTTER', 'HTTP'}.contains(source)) {
      return null;
    }
    final parts = ['event=$stage', 'source=$source'];
    if (success != null) parts.add('result=${success ? 'SUCCESS' : 'FAILED'}');
    if (error != null) {
      parts.add(
        'errorType=${error is AppFailure
            ? error.kind.name
            : error is PlatformException
            ? 'PLATFORM'
            : 'EXCEPTION'}',
      );
      final type = error.runtimeType.toString();
      parts.add(
        'exceptionClass=${exceptionClasses.contains(type) ? type : 'OTHER'}',
      );
      if (error is AppFailure) {
        if (exceptionClasses.contains(error.diagnosticExceptionClass)) {
          parts.add('sourceExceptionClass=${error.diagnosticExceptionClass}');
        }
        if (error.code == 'NETWORK_UNREACHABLE') {
          parts.add('errorCode=NETWORK_UNREACHABLE');
        }
        status ??= error.httpStatus;
        correlationId ??= error.correlationId;
      }
      if (error is PlatformException) {
        parts.add(
          'nativeCode=${nativeCodes.contains(error.code) ? error.code : 'OTHER'}',
        );
        final details = error.details;
        if (details is Map) {
          final code = details['nativeCode'], type = details['exceptionClass'];
          if (nativeCodes.contains(code)) parts.add('nativeDetail=$code');
          if (exceptionClasses.contains(type)) {
            parts.add('nativeExceptionClass=$type');
          }
          final androidCode = details['androidCode'];
          if (androidCode is int && androidCode >= 1 && androidCode <= 1000) {
            parts.add('androidCode=$androidCode');
          }
        }
      }
    }
    if (status != null && status >= 100 && status <= 599) {
      parts.add('httpStatus=$status');
    }
    if ({
      '/api/v1/auth/device/register/challenge',
      '/api/v1/auth/device/register/verify',
      '/api/v1/auth/refresh',
    }.contains(path)) {
      parts.add('path=$path');
    }
    if (correlationId != null &&
        RegExp(
          r'^(?:[A-Fa-f0-9]{32}|[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12})$',
        ).hasMatch(correlationId)) {
      parts.add('correlationId=$correlationId');
    }
    return parts.join(' ');
  }

  void biometric(
    String stage, {
    Object? error,
    int? status,
    String? path,
    String? correlationId,
    String source = 'FLUTTER',
    bool? success,
  }) {
    if (!kDebugMode) return;
    try {
      final line = biometricLine(
        stage,
        error: error,
        status: status,
        path: path,
        correlationId: correlationId,
        source: source,
        success: success,
      );
      if (line == null) return;
      final collector = Zone.current[biometricCollectorKey];
      if (collector is void Function(String)) collector(line);
      developer.log(line, name: 'simertpi');
      // dart:developer alone is not guaranteed to be visible through ADB.
      debugPrintSynchronously(line);
    } catch (_) {
      /* Diagnostic failures must never affect authentication. */
    }
  }

  Future<T> biometricStep<T>(String name, Future<T> Function() action) async {
    biometric('${name}_START');
    try {
      final value = await action();
      biometric('${name}_OK');
      return value;
    } catch (error) {
      biometric('${name}_FAILED', error: error);
      rethrow;
    }
  }

  // No arbitrary payload, URI, exception, header or destination is accepted.
  void requestResult({required bool success, required String correlationId}) {
    if (kDebugMode &&
        RegExp(r'^[A-Za-z0-9._:-]{1,128}$').hasMatch(correlationId)) {
      developer.log(
        'event=api_request result=${success ? 'SUCCESS' : 'FAILED'} '
        'correlationId=$correlationId',
        name: 'simertpi',
      );
    }
  }
}
