import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../discovery/data/parking_catalog.dart';
import '../../vehicles/data/vehicle_service.dart';
import '../data/parking_contract.dart';
import '../data/parking_intent_store.dart';

enum ParkingPhase {
  initial,
  loading,
  ready,
  processing,
  error,
  conflict,
  uncertain,
  created,
}

class ParkingController extends ChangeNotifier {
  ParkingController({
    required this.space,
    required this.owner,
    required this.catalog,
    required this.vehicles,
    required this.parking,
    required this.store,
  });
  IdentifiedSpace space;
  final String owner;
  final ParkingCatalogGateway catalog;
  final VehicleGateway vehicles;
  final ParkingGateway parking;
  final ParkingIntentStore store;
  ParkingPhase phase = ParkingPhase.initial;
  List<CitizenVehicle> items = [];
  Set<String> occupiedVehicles = {};
  static const occupying = {
    'PENDING_PAYMENT',
    'ACTIVE',
    'EXTENDED',
    'EXPIRED',
    'MAX_TIME_REACHED',
  };
  Future<void> refreshVehicles() async {
    final sessions = await parking.mine(owner);
    if (sessions.any((s) => s.owner != owner)) throw invalidParking;
    occupiedVehicles = sessions
        .where((s) => occupying.contains(s.status))
        .map((s) => s.vehicleId)
        .toSet();
  }

  String? vehicleId, message;
  int? minutes;
  ParkingRules? quote;
  ParkingReceipt? receipt;
  ParkingIntent? _intent;
  bool _busy = false, _disposed = false;
  bool get busy => _busy;
  bool get canSubmit =>
      !busy &&
      phase == ParkingPhase.ready &&
      space.selectable &&
      vehicleId != null &&
      !occupiedVehicles.contains(vehicleId) &&
      quote?.quoted == true &&
      minutes == quote?.minutes;
  Future<List<ParkingRules>> durationOptions() async =>
      parking is DurationOptionsGateway
      ? (parking as DurationOptionsGateway).options(space.space.id)
      : [if (quote?.quoted == true) quote!];
  void selectDuration(ParkingRules value) {
    if (busy ||
        _intent != null ||
        value.spaceId != space.space.id ||
        !value.quoted) {
      return;
    }
    quote = value;
    minutes = value.minutes;
    _notify();
  }

  void changedDuration(String text) {
    if (busy || _intent != null) return;
    minutes = int.tryParse(text);
    notifyListeners();
  }

  void choose(String id) {
    if (busy || _intent != null) return;
    if (!occupiedVehicles.contains(id) && items.any((v) => v.id == id)) {
      vehicleId = id;
    }
    notifyListeners();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> load() async {
    if (_busy || _disposed) return;
    _busy = true;
    phase = ParkingPhase.loading;
    message = null;
    _notify();
    try {
      _intent = await store.read();
      if (_intent != null) {
        if (_intent!.owner != owner || _intent!.spaceId != space.space.id) {
          throw invalidParking;
        }
        phase = ParkingPhase.uncertain;
        return;
      }
      final results = await Future.wait<Object>([
        catalog.identify(space.space.code, qr: false),
        vehicles.list(),
        parking.rules(space.space.id),
      ]);
      if (_disposed) return;
      final freshSpace = results[0] as IdentifiedSpace;
      if (freshSpace.space.id != space.space.id) {
        throw const AppFailure(FailureKind.conflict);
      }
      space = freshSpace;
      final all = results[1] as List<CitizenVehicle>;
      if (all.any((v) => v.userId != owner)) throw invalidParking;
      items = all.where((v) => v.active).toList();
      await refreshVehicles();
      if (!items.any((v) => v.id == vehicleId) ||
          occupiedVehicles.contains(vehicleId)) {
        final free = items
            .where((v) => !occupiedVehicles.contains(v.id))
            .toList();
        vehicleId = free.length == 1 ? free.single.id : null;
      }
      quote = results[2] as ParkingRules;
      minutes = minutes ?? quote!.minimum;
      if (space.selectable && quote!.operational && minutes != null) {
        quote = await parking.rules(space.space.id, minutes);
      }
      if (_disposed) return;
      phase = ParkingPhase.ready;
      if (!space.selectable) {
        message = space.space.operationalStatus == 'DISABLED'
            ? 'Este espacio no está habilitado actualmente.'
            : 'Este espacio ya no está disponible. Selecciona otro estacionamiento.';
      } else if (!quote!.operational) {
        message = ruleMessage(quote!.reason);
      }
    } catch (e) {
      if (!_disposed) {
        phase = ParkingPhase.error;
        quote = null;
        message = e is AppFailure && e.code == 'VEHICLE_OCCUPIED'
            ? 'Este vehículo ya tiene un estacionamiento en curso.'
            : e is AppFailure && e.kind == FailureKind.conflict
            ? 'Este espacio ya no está disponible. Selecciona otro estacionamiento.'
            : parkingError(e);
        if (e is AppFailure && e.kind == FailureKind.conflict) {
          try {
            space = await catalog.identify(space.space.code, qr: false);
            await refreshVehicles();
          } catch (_) {}
        }
      }
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> estimate() async {
    if (_busy || _disposed || _intent != null) return;
    if (minutes == null ||
        quote?.minimum == null ||
        quote?.maximum == null ||
        minutes! < quote!.minimum! ||
        minutes! > quote!.maximum!) {
      message = 'Ingresa una duración dentro del rango permitido.';
      _notify();
      return;
    }
    _busy = true;
    message = null;
    _notify();
    try {
      final fresh = await parking.rules(space.space.id, minutes);
      if (_disposed) return;
      quote = fresh;
      phase = ParkingPhase.ready;
      if (!fresh.operational) message = ruleMessage(fresh.reason);
    } catch (e) {
      if (!_disposed) {
        quote = null;
        phase = ParkingPhase.error;
        message = e is AppFailure && e.code == 'VEHICLE_OCCUPIED'
            ? 'Este vehículo ya tiene un estacionamiento en curso.'
            : e is AppFailure && e.kind == FailureKind.conflict
            ? 'Este espacio ya no está disponible. Selecciona otro estacionamiento.'
            : parkingError(e);
        if (e is AppFailure && e.kind == FailureKind.conflict) {
          try {
            space = await catalog.identify(space.space.code, qr: false);
            await refreshVehicles();
          } catch (_) {}
        }
      }
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> create() async {
    if (!canSubmit || _disposed) return;
    _busy = true;
    phase = ParkingPhase.processing;
    message = null;
    _notify();
    var sent = false;
    try {
      await refreshVehicles();
      if (occupiedVehicles.contains(vehicleId)) {
        vehicleId = null;
        throw const AppFailure(FailureKind.conflict, code: 'VEHICLE_OCCUPIED');
      }
      final freshSpace = await catalog.identify(space.space.code, qr: false);
      final all = await vehicles.list();
      final fresh = await parking.rules(space.space.id, minutes);
      if (_disposed) return;
      if (freshSpace.space.id != space.space.id) {
        throw const AppFailure(FailureKind.conflict);
      }
      space = freshSpace;
      if (!space.selectable) {
        throw const AppFailure(FailureKind.conflict, code: 'SPACE_UNAVAILABLE');
      }
      if (all.any((v) => v.userId != owner) ||
          !all.any((v) => v.id == vehicleId && v.active)) {
        throw const AppFailure(FailureKind.forbidden);
      }
      if (!fresh.quoted) {
        quote = fresh;
        message = ruleMessage(fresh.reason);
        phase = ParkingPhase.ready;
        return;
      }
      if (fresh.conditions != quote!.conditions) {
        quote = fresh;
        message =
            'Las condiciones cambiaron. Revisa el resumen antes de confirmar.';
        phase = ParkingPhase.ready;
        return;
      }
      final tariff = await parking.tariffId(fresh.code!);
      if (_disposed) return;
      _intent = ParkingIntent(
        owner,
        space.space.id,
        space.space.qrCode,
        vehicleId!,
        tariff,
        minutes!,
      );
      await store.write(_intent!);
      if (_disposed) {
        await store.clear();
        return;
      }
      sent = true;
      final result = await parking.create(_intent!);
      if (_disposed) return;
      if (!result.matches(_intent!) || result.status != 'PENDING_PAYMENT') {
        throw invalidParking;
      }
      receipt = result;
      phase = ParkingPhase.created;
      try {
        await store.clear();
        _intent = null;
      } catch (_) {
        /* Recover by read-only lookup on next entry. */
      }
    } catch (e) {
      if (_disposed) return;
      final definitive =
          e is AppFailure &&
          {
            FailureKind.conflict,
            FailureKind.invalidRequest,
            FailureKind.forbidden,
            FailureKind.unauthorized,
            FailureKind.rejected,
          }.contains(e.kind);
      if (sent && !definitive) {
        phase = ParkingPhase.uncertain;
        message = 'La respuesta no se confirmó. No envíes otra solicitud; comprueba su estado.';
      } else {
        if (_intent != null) {
          try {
            await store.clear();
            _intent = null;
          } catch (_) {
            phase = ParkingPhase.uncertain;
            message = 'Comprueba la solicitud antes de continuar.';
            return;
          }
        }
        phase = e is AppFailure && e.kind == FailureKind.conflict
            ? ParkingPhase.conflict
            : ParkingPhase.error;
        message = e is AppFailure && e.code == 'VEHICLE_OCCUPIED'
            ? 'Este vehículo ya tiene un estacionamiento en curso.'
            : e is AppFailure && e.kind == FailureKind.conflict
            ? 'Este espacio ya no está disponible. Selecciona otro estacionamiento.'
            : parkingError(e);
        if (e is AppFailure && e.kind == FailureKind.conflict) {
          try {
            space = await catalog.identify(space.space.code, qr: false);
            await refreshVehicles();
          } catch (_) {}
        }
      }
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> recover() async {
    if (_busy || _disposed || _intent == null) return;
    _busy = true;
    _notify();
    try {
      final existing = await parking.mine(owner);
      if (_disposed) return;
      final matching = existing
          .where((r) => r.matches(_intent!) && r.status == 'PENDING_PAYMENT')
          .toList();
      if (matching.length == 1) {
        receipt = matching.single;
        phase = ParkingPhase.created;
        await store.clear();
        _intent = null;
        message = null;
      } else {
        phase = ParkingPhase.uncertain;
        message = 'Aún no pudimos confirmar la solicitud. No la envíes otra vez; consulta con soporte si persiste.';
      }
    } catch (e) {
      if (!_disposed) {
        phase = ParkingPhase.uncertain;
        message = e is AppFailure && e.code == 'VEHICLE_OCCUPIED'
            ? 'Este vehículo ya tiene un estacionamiento en curso.'
            : e is AppFailure && e.kind == FailureKind.conflict
            ? 'Este espacio ya no está disponible. Selecciona otro estacionamiento.'
            : parkingError(e);
        if (e is AppFailure && e.kind == FailureKind.conflict) {
          try {
            space = await catalog.identify(space.space.code, qr: false);
            await refreshVehicles();
          } catch (_) {}
        }
      }
    } finally {
      _busy = false;
      _notify();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
