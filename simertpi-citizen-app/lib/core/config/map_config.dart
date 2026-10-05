class MapConfig {
  const MapConfig({this.tileUrl = '', this.attribution = ''});
  final String tileUrl, attribution;
  static const osmTiles = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  static const osmCredit =
      '© OpenStreetMap contributors · ODbL · https://www.openstreetmap.org/copyright';
  static MapConfig fromDefines() => parse(
    const String.fromEnvironment('MAP_TILE_URL'),
    const String.fromEnvironment('MAP_ATTRIBUTION'),
    source: const String.fromEnvironment('MAP_SOURCE', defaultValue: 'custom'),
    environment: const String.fromEnvironment(
      'ENVIRONMENT',
      defaultValue: 'dev',
    ),
  );
  static MapConfig parse(
    String url,
    String attribution, {
    String source = 'custom',
    String environment = 'dev',
  }) {
    if (!{'dev', 'qa', 'uat', 'prod'}.contains(environment) ||
        !{'custom', 'osm-dev'}.contains(source)) {
      throw const FormatException('Invalid map configuration');
    }
    if (source == 'osm-dev') {
      if (environment != 'dev' || url.isNotEmpty || attribution.isNotEmpty) {
        throw const FormatException('OSM development source unavailable');
      }
      return const MapConfig(tileUrl: osmTiles, attribution: osmCredit);
    }
    if (url.isEmpty) return const MapConfig();
    final uri = Uri.tryParse(url);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasFragment ||
        uri.hasQuery ||
        !['{x}', '{y}', '{z}'].every(url.contains) ||
        attribution.trim().isEmpty) {
      throw const FormatException('Invalid map configuration');
    }
    // Public OSM servers are a deliberate DEV option, never a production default.
    if (uri.host == 'openstreetmap.org' ||
        uri.host.endsWith('.openstreetmap.org')) {
      if (environment != 'dev' || url != osmTiles || attribution != osmCredit) {
        throw const FormatException('Use the explicit OSM development source');
      }
    }
    return MapConfig(tileUrl: url, attribution: attribution);
  }

  bool get configured => tileUrl.isNotEmpty && attribution.isNotEmpty;
}
