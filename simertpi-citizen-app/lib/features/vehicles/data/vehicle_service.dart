import '../../../core/network/api_client.dart';
import '../../../core/errors/app_failure.dart';

class CitizenVehicle {
  const CitizenVehicle({
    required this.id,
    required this.userId,
    required this.plate,
    this.brand,
    this.model,
    this.color,
    required this.active,
  });
  final String id, userId, plate;
  final String? brand, model, color;
  final bool active;
  String get description =>
      [brand, model].whereType<String>().where((v) => v.isNotEmpty).join(' ');
  factory CitizenVehicle.fromJson(Object? json) {
    if (json is! Map ||
        json['id'] is! String ||
        json['userId'] is! String ||
        json['plate'] is! String ||
        json['active'] is! bool) {
      throw const AppFailure(FailureKind.unknown);
    }
    return CitizenVehicle(
      id: json['id'] as String,
      userId: json['userId'] as String,
      plate: json['plate'] as String,
      active: json['active'] as bool,
      brand: json['brand'] as String?,
      model: json['model'] as String?,
      color: json['color'] as String?,
    );
  }
}

class VehicleInput {
  const VehicleInput(this.plate, {this.brand, this.model, this.color});
  final String plate;
  final String? brand, model, color;
  Map<String, Object?> toJson(String owner) => {
    'userId': owner,
    'plate': plate.trim().toUpperCase(),
    'brand': brand,
    'model': model,
    'color': color,
  };
}

abstract interface class VehicleGateway {
  Future<List<CitizenVehicle>> list();
  Future<CitizenVehicle> create(VehicleInput input);
  Future<void> deactivate(String id);
}

class VehicleService implements VehicleGateway {
  const VehicleService(this.api, this.owner);
  final ApiClient? api;
  final String? owner;
  ApiClient get _api =>
      api ?? (throw const AppFailure(FailureKind.unavailable));
  String get _owner =>
      owner ?? (throw const AppFailure(FailureKind.unauthorized));
  @override
  Future<List<CitizenVehicle>> list() async {
    final response = await _api.request(
      ApiMethod.get,
      'vehicles/user/${Uri.encodeComponent(_owner)}',
    );
    if (response.body is! List) throw const AppFailure(FailureKind.unknown);
    return (response.body as List).map(CitizenVehicle.fromJson).toList();
  }

  @override
  Future<void> deactivate(String id) async {
    final response = await _api.request(
      ApiMethod.put,
      'vehicles/${Uri.encodeComponent(id)}/deactivation',
    );
    if (response.statusCode != 200 ||
        CitizenVehicle.fromJson(response.body).active) {
      throw const AppFailure(FailureKind.unknown);
    }
  }

  @override
  Future<CitizenVehicle> create(VehicleInput input) async {
    final response = await _api.request(
      ApiMethod.post,
      'vehicles',
      body: input.toJson(_owner),
    );
    if (response.statusCode != 201) throw const AppFailure(FailureKind.unknown);
    return CitizenVehicle.fromJson(response.body);
  }
}

String vehicleError(Object error) {
  if (error is! AppFailure) return 'No pudimos cargar tus vehículos.';
  return switch (error.kind) {
    FailureKind.conflict => 'Esta placa ya está registrada.',
    FailureKind.invalidRequest => 'Revisa los datos del vehículo.',
    FailureKind.forbidden =>
      'No tienes autorización para consultar este vehículo.',
    FailureKind.unauthorized =>
      'Tu sesión ya no es válida. Ingresa nuevamente.',
    _ => error.message,
  };
}
