import 'dart:async';

import 'package:flutter/foundation.dart';

import '../config/app_config.dart';
import '../errors/app_failure.dart';
import 'app_log.dart';

bool biometricDiagnosticEnabled(
  AppEnvironment environment, {
  bool debug = kDebugMode,
}) => debug && environment == AppEnvironment.dev;

/// Memory only. Contains exclusively fields already sanitized by AppLog.
class BiometricDiagnostic {
  BiometricDiagnostic(this.attemptedAt, {this.source});
  // Ephemeral UI ownership token, never an account/device identifier or session.
  final Object? source;
  final DateTime attemptedAt;
  String? lastCompleted, failedStage, activeStage;
  final Map<String, String> _failure = {};

  void observe(String line) {
    final values = <String, String>{};
    for (final part in line.split(' ')) {
      final split = part.indexOf('=');
      if (split > 0) {
        final key = part.substring(0, split), value = part.substring(split + 1);
        final allowed = switch (key) {
          'event' => AppLog.bioStages.contains(value),
          'result' => {'FAILED', 'SUCCESS'}.contains(value),
          'errorType' => {
            'PLATFORM',
            'EXCEPTION',
            ...FailureKind.values.map((kind) => kind.name),
          }.contains(value),
          'exceptionClass' ||
          'sourceExceptionClass' ||
          'nativeExceptionClass' =>
            AppLog.exceptionClasses.contains(value) || value == 'OTHER',
          'nativeCode' || 'nativeDetail' =>
            AppLog.nativeCodes.contains(value) || value == 'OTHER',
          'httpStatus' =>
            (int.tryParse(value) ?? 0) >= 100 &&
                (int.tryParse(value) ?? 0) <= 599,
          'androidCode' =>
            (int.tryParse(value) ?? 0) >= 1 &&
                (int.tryParse(value) ?? 0) <= 1000,
          'path' => {
            '/api/v1/auth/device/register/challenge',
            '/api/v1/auth/device/register/verify',
            '/api/v1/auth/refresh',
          }.contains(value),
          'correlationId' => RegExp(
            r'^(?:[A-Fa-f0-9]{32}|[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12})$',
          ).hasMatch(value),
          _ => false,
        };
        if (allowed) values[key] = value;
      }
    }
    final stage = values['event'];
    if (!AppLog.bioStages.contains(stage)) return;
    if (stage!.endsWith('_START')) activeStage = stage;
    if (failedStage == null && stage.endsWith('_OK')) {
      lastCompleted = stage;
      activeStage = null;
    }
    if (stage.endsWith('_FAILED') && failedStage == null) {
      failedStage = stage;
      _failure.addAll(values);
    }
    // Completion can fail during local persistence outside a wrapped step.
    if (stage == 'BIO_ENABLE_COMPLETED' &&
        values['result'] == 'FAILED' &&
        (failedStage != null || values.containsKey('errorType'))) {
      failedStage ??= activeStage == null || activeStage == 'BIO_ENABLE_START'
          ? 'BIO_ENABLE_COMPLETED'
          : activeStage?.replaceFirst('_START', '_FAILED');
      for (final entry in values.entries) {
        _failure.putIfAbsent(entry.key, () => entry.value);
      }
    }
  }

  String get text {
    String value(String key) => _failure[key] ?? 'No disponible';
    final exception =
        _failure['nativeExceptionClass'] ??
        _failure['sourceExceptionClass'] ??
        _failure['exceptionClass'];
    return 'Diagnóstico biométrico — DEV\n\nEstado: FALLIDO\n'
        'Última etapa completada: ${lastCompleted ?? 'No disponible'}\n'
        'Etapa fallida: ${failedStage ?? 'No disponible'}\n'
        'Tipo de error: ${value('errorType')}\n'
        'Código HTTP: ${value('httpStatus')}\n'
        'Ruta API: ${value('path')}\n'
        'Código Android: ${value('androidCode')}\n'
        'Código nativo: ${value('nativeCode')}\n'
        'Detalle nativo: ${value('nativeDetail')}\n'
        'Clase de excepción: ${exception ?? 'No disponible'}\n'
        'Correlation ID: ${value('correlationId')}\n'
        'Fecha y hora: ${attemptedAt.toLocal().toIso8601String()}';
  }
}

class BiometricDiagnostics {
  static final pending = ValueNotifier<BiometricDiagnostic?>(null);

  static Future<bool> capture(
    Future<bool> Function() action, {
    Object? source,
  }) async {
    if (!biometricDiagnosticEnabled(AppConfig.fromDefines().environment)) {
      return action();
    }
    final report = BiometricDiagnostic(DateTime.now(), source: source);
    final result = await runZoned(
      action,
      zoneValues: {AppLog.biometricCollectorKey: report.observe},
    );
    if (!result && report.failedStage != null) pending.value = report;
    return result;
  }
}
