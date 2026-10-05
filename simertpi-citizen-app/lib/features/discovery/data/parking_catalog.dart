import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';

const _invalid = AppFailure(FailureKind.unavailable);
String _text(Map<String, dynamic> j, String key) {
  final value = j[key];
  if (value is! String || value.isEmpty) throw _invalid;
  return value;
}

bool _active(Map<String, dynamic> j) {
  if (j['active'] is! bool) throw _invalid;
  return j['active'] as bool;
}

class ParkingZone {
  const ParkingZone(this.id, this.code, this.name, this.active);
  final String id, code, name;
  final bool active;
  factory ParkingZone.fromJson(Map<String, dynamic> j) => ParkingZone(
    _text(j, 'id'),
    _text(j, 'code'),
    _text(j, 'name'),
    _active(j),
  );
}

class ParkingStreet {
  const ParkingStreet(this.id, this.zoneId, this.name, this.active);
  final String id, zoneId, name;
  final bool active;
  factory ParkingStreet.fromJson(Map<String, dynamic> j) => ParkingStreet(
    _text(j, 'id'),
    _text(j, 'zoneId'),
    _text(j, 'name'),
    _active(j),
  );
}

class CatalogSpace {
  const CatalogSpace(
    this.id,
    this.streetId,
    this.code,
    this.qrCode,
    this.number,
    this.active, {
    this.latitude,
    this.longitude,
  });
  final String id, streetId, code, qrCode, number;
  final bool active;
  final double? latitude, longitude;
  bool get hasCoordinates =>
      latitude != null &&
      longitude != null &&
      latitude!.isFinite &&
      longitude!.isFinite &&
      latitude!.abs() <= 85.05112878 &&
      longitude!.abs() <= 180;
  factory CatalogSpace.fromJson(Map<String, dynamic> j) {
    double? number(String key) {
      final value = j[key];
      if (value == null) return null;
      if (value is! num || !value.isFinite) throw _invalid;
      return value.toDouble();
    }

    return CatalogSpace(
      _text(j, 'id'),
      _text(j, 'streetId'),
      _text(j, 'code'),
      _text(j, 'qrCode'),
      _text(j, 'spaceNumber'),
      _active(j),
      latitude: number('latitude'),
      longitude: number('longitude'),
    );
  }
}

class ParkingCatalog {
  ParkingCatalog(
    List<ParkingZone> zones,
    List<ParkingStreet> streets,
    List<CatalogSpace> spaces,
  ) : zones = List.unmodifiable(zones),
      streets = List.unmodifiable(streets),
      spaces = List.unmodifiable(spaces);
  final List<ParkingZone> zones;
  final List<ParkingStreet> streets;
  final List<CatalogSpace> spaces;
  ParkingStreet? streetOf(CatalogSpace space) =>
      streets.where((s) => s.id == space.streetId).firstOrNull;
  ParkingZone? zoneOf(CatalogSpace space) {
    final street = streetOf(space);
    return zones.where((z) => z.id == street?.zoneId).firstOrNull;
  }

  bool selectable(CatalogSpace space) =>
      space.active &&
      streetOf(space)?.active == true &&
      zoneOf(space)?.active == true;
  List<CatalogSpace> inZone(String? id) =>
      spaces.where((s) => id == null || zoneOf(s)?.id == id).toList();
}

class IdentifiedSpace {
  const IdentifiedSpace(this.space, this.catalog);
  final CatalogSpace space;
  final ParkingCatalog catalog;
  bool get selectable => catalog.selectable(space);
}

abstract interface class ParkingCatalogGateway {
  Future<ParkingCatalog> load();
  Future<IdentifiedSpace> identify(String value, {required bool qr});
}

// QR contents remain untrusted opaque identifiers; never a URL or embedded DTO.
bool safeIdentifier(String value, {int maximum = 255}) =>
    value.isNotEmpty &&
    value.trim() == value &&
    value.length <= maximum &&
    !value.contains(RegExp(r'[\x00-\x20\x7f/\\?#{}]')) &&
    !value.contains('..') &&
    !value.contains(':');

class ParkingCatalogService implements ParkingCatalogGateway {
  const ParkingCatalogService(this.api);
  final ApiClient? api;
  Future<Object?> _get(String path) async {
    if (api == null) throw const AppFailure(FailureKind.unavailable);
    return (await api!.request(ApiMethod.get, path)).body;
  }

  List<T> _list<T>(Object? body, T Function(Map<String, dynamic>) parse) {
    if (body is! List) throw _invalid;
    return body.map((item) {
      if (item is! Map<String, dynamic>) throw _invalid;
      return parse(item);
    }).toList();
  }

  @override
  Future<ParkingCatalog> load() async {
    final bodies = await Future.wait([
      _get('zones'),
      _get('streets'),
      _get('parking-spaces'),
    ]);
    return ParkingCatalog(
      _list(bodies[0], ParkingZone.fromJson),
      _list(bodies[1], ParkingStreet.fromJson),
      _list(bodies[2], CatalogSpace.fromJson),
    );
  }

  @override
  Future<IdentifiedSpace> identify(String value, {required bool qr}) async {
    if (!safeIdentifier(value, maximum: qr ? 255 : 50)) {
      throw const AppFailure(FailureKind.invalidRequest);
    }
    final body = await _get(
      'parking-spaces/${qr ? 'qr' : 'code'}/${Uri.encodeComponent(value)}',
    );
    if (body is! Map<String, dynamic>) throw _invalid;
    final space = CatalogSpace.fromJson(body);
    // Validate relationship/active flags from fresh backend catalogs, not QR data.
    final catalog = await load();
    if (catalog.streetOf(space) == null || catalog.zoneOf(space) == null) {
      throw _invalid;
    }
    final fresh = catalog.spaces.where((s) => s.id == space.id).firstOrNull;
    if (fresh == null || (qr ? fresh.qrCode : fresh.code) != value) {
      throw _invalid;
    }
    return IdentifiedSpace(fresh, catalog);
  }
}

String discoveryError(Object error) {
  if (error is AppFailure) {
    if (error.kind == FailureKind.invalidRequest) {
      return 'Ingresa un identificador de espacio válido. No se aceptan enlaces.';
    }
    if (error.kind == FailureKind.rejected) {
      return 'No encontramos ese espacio. Revisa el código.';
    }
    if (error.kind == FailureKind.forbidden) {
      return 'No tienes autorización para consultar este espacio.';
    }
    return error.message;
  }
  return 'No pudimos consultar los espacios. Inténtalo nuevamente.';
}
