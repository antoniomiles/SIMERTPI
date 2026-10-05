enum AppEnvironment { dev, qa, uat, prod }

class AppConfig {
  const AppConfig({required this.environment, this.apiBaseUrl});
  final AppEnvironment environment;
  final Uri? apiBaseUrl;

  factory AppConfig.fromDefines() => AppConfig.parse(
    environment: const String.fromEnvironment(
      'ENVIRONMENT',
      defaultValue: 'dev',
    ),
    apiBaseUrl: const String.fromEnvironment('API_BASE_URL'),
  );

  factory AppConfig.parse({
    required String environment,
    String apiBaseUrl = '',
  }) {
    final selected = AppEnvironment.values.where((e) => e.name == environment);
    if (selected.isEmpty) {
      throw const FormatException('Unsupported environment');
    }
    final env = selected.first;
    if (apiBaseUrl.isEmpty) {
      if (env != AppEnvironment.dev) {
        throw const FormatException('API_BASE_URL required');
      }
      return AppConfig(environment: env);
    }
    final uri = Uri.tryParse(apiBaseUrl);
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        !{'http', 'https'}.contains(uri.scheme) ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (env != AppEnvironment.dev && uri.scheme != 'https')) {
      throw const FormatException('Invalid API_BASE_URL');
    }
    // A deployment origin is not an API base. Preserve explicitly supplied paths
    // (including reverse-proxy prefixes), and resolve only an empty/root path.
    final apiUri = uri.path.isEmpty || uri.path == '/'
        ? uri.replace(path: '/api/v1')
        : uri;
    return AppConfig(environment: env, apiBaseUrl: apiUri);
  }
}
