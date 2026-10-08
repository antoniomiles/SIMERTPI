import 'package:flutter/foundation.dart';

import '../../parking/data/parking_contract.dart';
import '../../payments/data/payment_contract.dart';
import '../../discovery/data/parking_catalog.dart';
import '../../vehicles/data/vehicle_service.dart';
import '../data/active_parking_service.dart';

class ActiveParkingController extends ChangeNotifier {
  ActiveParkingController({
    required this.owner,
    required this.gateway,
    required this.payments,
    required this.store,
    required this.catalog,
    required this.vehicles,
    required this.dev,
    DateTime Function()? now,
  }) : now = now ?? DateTime.now;
  final String owner;
  final ActiveParkingGateway gateway;
  final PaymentGateway payments;
  final ExtensionStore store;
  final ParkingCatalogGateway catalog;
  final VehicleGateway vehicles;
  final bool dev;
  final DateTime Function() now;
  List<ParkingReceipt> sessions = [];
  String? _preferredSessionId;
  void prioritizeSession(String id) {
    _preferredSessionId = id;
    sessions.sort(
      (a, b) => a.id == id
          ? -1
          : b.id == id
          ? 1
          : 0,
    );
    emit();
  }

  ParkingCatalog? locations;
  List<CitizenVehicle> vehicleItems = [];
  bool loading = false,
      busy = false,
      loaded = false,
      stale = false,
      _disposed = false;
  String? message;
  ParkingReceipt? selected;
  ParkingRules? quote;
  Map<String, dynamic>? intent;
  CitizenPayment? payment;
  bool closeUncertain = false;
  bool extensionConfirmed = false;
  Map<String, List<ParkingRules>> extensionOptions = {};
  void emit() {
    if (!_disposed) notifyListeners();
  }

  Future<void> load() async {
    if (loading || busy || _disposed) return;
    loading = true;
    message = null;
    emit();
    try {
      final all = await gateway.mine(owner);
      if (all.any((s) => s.owner != owner)) throw invalidParking;
      if (_disposed) return;
      for (final session in all) {
        session.anchor(now());
      }
      sessions = all
          .where((s) => openParkingStates.contains(s.status))
          .toList();
      final preferred = _preferredSessionId;
      if (preferred != null) {
        sessions.sort(
          (a, b) => a.id == preferred
              ? -1
              : b.id == preferred
              ? 1
              : 0,
        );
      }
      extensionOptions = {};
      if (gateway is DurationOptionsGateway) {
        for (final session in sessions) {
          if (!session.extensionAllowed(now())) {
            continue;
          }
          try {
            final values = await (gateway as DurationOptionsGateway).options(
              session.id,
            );
            if (_disposed) return;
            if (values.any((q) => q.spaceId != session.spaceId)) {
              throw invalidParking;
            }
            extensionOptions[session.id] = values;
          } catch (_) {
            // Unknown eligibility stays disabled; never substitute a local municipal rule.
            extensionOptions[session.id] = [];
          }
        }
      }
      loaded = true;
      stale = false;
      try {
        locations = await catalog.load();
        vehicleItems = await vehicles.list();
      } catch (_) {
        /* Session stays visible when secondary metadata fails. */
      }
    } catch (e) {
      stale = true;
      message = parkingError(e);
    } finally {
      loading = false;
      emit();
    }
  }

  Future<void> prepare(String id) async {
    if (busy || _disposed) return;
    busy = true;
    quote = null;
    payment = null;
    extensionConfirmed = false;
    message = null;
    emit();
    try {
      selected = await gateway.session(id);
      selected!.anchor(now());
      if (selected!.owner != owner) throw invalidParking;
      intent = await store.read();
      if (intent != null) {
        if (intent!['owner'] != owner) throw invalidParking;
        selected = await gateway.session(intent!['sessionId'] as String);
        selected!.anchor(now());
        if (selected!.owner != owner) throw invalidParking;
        if (intent!['paymentId'] != null) {
          await _payment(intent!['paymentId'] as String);
        } else {
          message = 'Hay una extensión por confirmar. Consulta su resultado antes de solicitar otra.';
        }
        return;
      }
      if (!selected!.extensionAllowed(now())) return;
      final policy = await gateway.quote(id, null);
      final minutes = policy.minimum;
      if (minutes != null && minutes > 0) {
        quote = await gateway.quote(id, minutes);
      }
    } catch (e) {
      message = parkingError(e);
    } finally {
      busy = false;
      emit();
    }
  }

  Future<List<ParkingRules>> durationOptions() async =>
      gateway is DurationOptionsGateway
      ? (gateway as DurationOptionsGateway).options(selected!.id)
      : [if (quote?.quoted == true) quote!];
  void selectDuration(ParkingRules value) {
    if (busy ||
        intent != null ||
        !value.quoted ||
        value.spaceId != selected?.spaceId) {
      return;
    }
    quote = value;
    emit();
  }

  Future<void> pricing(int minutes) async {
    if (busy || intent != null || selected == null || _disposed) return;
    busy = true;
    message = null;
    emit();
    try {
      quote = await gateway.quote(selected!.id, minutes);
    } catch (e) {
      quote = null;
      message = parkingError(e);
    } finally {
      busy = false;
      emit();
    }
  }

  bool get canExtend =>
      dev &&
      !busy &&
      !stale &&
      intent == null &&
      quote?.quoted == true &&
      selected != null &&
      selected!.extensionAllowed(now());
  Future<void> confirmExtension() async {
    if (!canExtend) return;
    busy = true;
    message = null;
    emit();
    try {
      final old = quote!;
      final fresh = await gateway.quote(selected!.id, old.minutes);
      if (fresh.conditions != old.conditions ||
          fresh.expiresAt != old.expiresAt ||
          !fresh.quoted) {
        quote = fresh;
        message = 'Las condiciones cambiaron. Revisa la cotización antes de confirmar.';
        return;
      }
      final key = PaymentIntent(owner, selected!.id, 'TEST').key;
      intent = {
        'owner': owner,
        'sessionId': selected!.id,
        'additionalMinutes': fresh.minutes!,
        'paymentMethod': 'TEST',
        'idempotencyKey': key,
        'expectedAmount': fresh.amount!,
        'expectedEndAt': fresh.expiresAt!,
      };
      await store.write(Map<String, Object>.from(intent!));
      await _send();
    } catch (e) {
      message = 'No pudimos confirmar la extensión. Consulta su resultado antes de repetir.';
    } finally {
      busy = false;
      emit();
    }
  }

  Future<void> _send() async {
    final current = intent!;
    final id = current['sessionId'] as String;
    final body = Map<String, Object>.from(current)
      ..remove('owner')
      ..remove('sessionId')
      ..remove('paymentId');
    await store.write(Map<String, Object>.from(current));
    final result = await gateway.extend(id, body);
    current['paymentId'] = result['paymentId'];
    await store.write(Map<String, Object>.from(current));
    await _payment(result['paymentId'] as String);
  }

  Future<void> _payment(String id) async {
    final p = await payments.status(id);
    if (p.sessionId != intent!['sessionId'] ||
        p.method != intent!['paymentMethod']) {
      throw invalidParking;
    }
    payment = p;
    selected = await gateway.session(p.sessionId);
    selected!.anchor(now());
    if (selected!.owner != owner || selected!.id != p.sessionId) {
      throw invalidParking;
    }
    extensionConfirmed =
        p.status == PaymentStatus.approved &&
        selected!.status == 'EXTENDED' &&
        !selected!.expectedEndAt.isBefore(
          DateTime.parse(intent!['expectedEndAt'] as String),
        );
    if (p.status == PaymentStatus.approved && !extensionConfirmed) {
      message = 'El pago está aprobado. Estamos comprobando el nuevo tiempo contratado.';
      return;
    }
    if (!p.waiting) {
      await store.clear();
      intent = null;
    }
  }

  Future<void> checkExtension() async {
    if (busy || intent == null || _disposed) return;
    busy = true;
    message = null;
    emit();
    try {
      if (intent!['paymentId'] == null) {
        await _send();
      } else {
        final id = intent!['paymentId'] as String;
        final p = await payments.status(id);
        if (p.waiting) await payments.refresh(id);
        await _payment(id);
      }
    } catch (e) {
      message = paymentError(e);
    } finally {
      busy = false;
      emit();
    }
  }

  Future<bool> closeSession(String id) async {
    if (busy || _disposed) return false;
    busy = true;
    message = null;
    emit();
    try {
      final current = await gateway.session(id);
      current.anchor(now());
      if (current.owner != owner || current.id != id) throw invalidParking;
      if (current.status == 'COMPLETED') {
        sessions.removeWhere((s) => s.id == id);
        closeUncertain = false;
        return true;
      }
      if (!openParkingStates.contains(current.status) ||
          !current.closeAllowed(now())) {
        throw invalidParking;
      }
      final result = await gateway.close(id);
      if (result.owner != owner ||
          result.id != id ||
          result.status != 'COMPLETED') {
        throw invalidParking;
      }
      sessions.removeWhere((s) => s.id == id);
      closeUncertain = false;
      return true;
    } catch (e) {
      closeUncertain = true;
      stale = true;
      message = 'No pudimos confirmar el cierre. Consulta el estado antes de volver a finalizar.';
      return false;
    } finally {
      busy = false;
      emit();
    }
  }

  String space(ParkingReceipt s) =>
      locations?.spaces.where((v) => v.id == s.spaceId).firstOrNull?.code ??
      'Espacio seleccionado';
  String? street(ParkingReceipt s) {
    final v = locations?.spaces.where((v) => v.id == s.spaceId).firstOrNull;
    return v == null ? null : locations?.streetOf(v)?.name;
  }

  String plate(ParkingReceipt s) =>
      vehicleItems
          .where((v) => v.id == s.vehicleId && v.userId == owner)
          .firstOrNull
          ?.plate ??
      'Vehículo seleccionado';
  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
