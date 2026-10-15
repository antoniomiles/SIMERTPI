import 'package:flutter/foundation.dart';

enum MunicipalEnvironment {
  development('development'),
  staging('staging'),
  production('production'),
  unknown('unknown');

  const MunicipalEnvironment(this.label);
  final String label;

  static MunicipalEnvironment parse(String value) =>
      MunicipalEnvironment.values.firstWhere(
        (environment) => environment.label == value,
        orElse: () => MunicipalEnvironment.unknown,
      );
}

@immutable
class MunicipalAppConfig {
  const MunicipalAppConfig({
    required this.environment,
    required this.apiBaseUrl,
  });

  static const _environment = String.fromEnvironment(
    'SIMERTPI_ENV',
    defaultValue: 'development',
  );
  static const _apiBaseUrl = String.fromEnvironment('SIMERTPI_API_BASE_URL');

  factory MunicipalAppConfig.fromEnvironment() => MunicipalAppConfig(
    environment: MunicipalEnvironment.parse(_environment),
    apiBaseUrl: _apiBaseUrl,
  );

  final MunicipalEnvironment environment;
  final String apiBaseUrl;

  Uri? get apiBaseUri {
    final value = apiBaseUrl.trim();
    if (value.isEmpty) return null;
    final uri = Uri.tryParse(value);
    if (uri == null ||
        !uri.hasAuthority ||
        !uri.hasScheme ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment) {
      return null;
    }
    final segments = uri.pathSegments
        .where((segment) => segment.isNotEmpty)
        .toList();
    if (segments.length < 2 ||
        segments[segments.length - 2] != 'api' ||
        segments.last != 'v1') {
      return null;
    }
    if (uri.scheme != 'https' &&
        !(environment == MunicipalEnvironment.development &&
            uri.scheme == 'http' &&
            (uri.host == 'localhost' || uri.host == '127.0.0.1'))) {
      return null;
    }
    return uri;
  }

  bool get isApiConfigured => apiBaseUri != null;

  String get configurationMessage => isApiConfigured
      ? 'Servicio municipal configurado.'
      : 'Backend sin configurar; no se realizarán conexiones.';
}
