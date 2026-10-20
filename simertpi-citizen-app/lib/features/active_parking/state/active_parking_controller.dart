import 'dart:async';

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
  String? focusedSessionId;
  void prioritizeSession(String id) {
    focusedSessionId = id; // Focus is presentation state, never a sorting rule.
    emit();
  }

  int _readRevision = 0;
  bool _reloadRequested = false;
  Completer<void>? _loadCompletion;
  static int compareSessions(ParkingReceipt a, ParkingReceipt b) {
    int group(ParkingReceipt value) =>
        {'ACTIVE', 'EXTENDED'}.contains(value.status) ? 0 : 1;
    final status = group(a).compareTo(group(b));
    if (status != 0) return status;
    final started = b.startedAt.compareTo(a.startedAt);
    return started != 0 ? started : a.id.compareTo(b.id);
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
    if (_disposed) return;
    // Existing mutations may finish while a read is waiting. Invalidate that snapshot.
    if (busy && loading) {
      _readRevision++;
      _reloadRequested = true;
    }
    notifyListeners();
    if (!busy && !loading && _reloadRequested) {
      unawaited(_drainLoads());
    }
  }

  Future<void> load() {
    if (_disposed) return Future.value();
    _readRevision++;
    _reloadRequested = true;
    final completion = _loadCompletion ??= Completer<void>();
    if (!loading && !busy) {
      unawaited(_drainLoads());
    }
    return completion.future;
  }

  Future<void> _drainLoads() async {
    if (loading || busy || _disposed) return;
    loading = true;
    message = null;
    emit();
    try {
      while (_reloadRequested && !busy && !_disposed) {
        _reloadRequested = false;
        final revision = _readRevision;
        bool current() => !_disposed && !busy && revision == _readRevision;
        try {
          final all = await gateway.mine(owner);
          if (!current()) continue;
          if (all.any((session) => session.owner != owner)) {
            throw invalidParking;
          }
          final next =
              all
                  .where(
                    (session) => openParkingStates.contains(session.status),
                  )
                  .toList()
                ..sort(compareSessions);
          for (final session in next) {
            session.anchor(now());
          }
          final options = <String, List<ParkingRules>>{};
          if (gateway is DurationOptionsGateway) {
            for (final session in next) {
              if (!session.extensionAllowed(now())) continue;
              try {
                final values = await (gateway as DurationOptionsGateway)
                    .options(session.id);
                if (!current()) break;
                if (values.any((quote) => quote.spaceId != session.spaceId)) {
                  throw invalidParking;
                }
                options[session.id] = values;
              } catch (_) {
                options[session.id] = [];
              }
            }
          }
          if (!current()) continue;
          var nextLocations = locations;
          var nextVehicles = vehicleItems;
          try {
            nextLocations = await catalog.load();
            if (!current()) continue;
            nextVehicles = await vehicles.list();
          } catch (_) {
            /* Preserve sessions when secondary metadata is unavailable. */
          }
          if (!current()) continue;
          sessions = next;
          extensionOptions = options;
          locations = nextLocations;
          vehicleItems = nextVehicles;
          loaded = true;
          stale = false;
          message = null;
        } catch (error) {
          if (current()) {
            stale = true;
            message = parkingError(error);
          }
        }
      }
    } finally {
      loading = false;
      if (!_reloadRequested || _disposed) {
        final completion = _loadCompletion;
        _loadCompletion = null;
        if (completion != null && !completion.isCompleted) {
          completion.complete();
        }
      }
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
      quote = policy;
      final minutes = policy.minimum;
      if (!policy.freeParking && minutes != null && minutes > 0) {
        quote = await gateway.quote(id, minutes);
      }
    } catch (e) {
      message = parkingError(e);
    } finally {
      busy = false;
      emit();
    }
  }

  Future<List<ParkingRules>> durationOptions() async {
    if (quote?.freeParking == true) return const [];
    final parking = selected;
    if (parking == null) return const [];
    if (gateway is! DurationOptionsGateway) {
      return [if (quote?.quoted == true) quote!];
    }
    final options = await (gateway as DurationOptionsGateway).options(
      parking.id,
    );
    if (options.isEmpty && !_disposed && selected?.id == parking.id) {
      final current = await gateway.quote(parking.id, null);
      if (current.freeParking) {
        quote = current;
        emit();
      }
    }
    return options;
  }

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

  int? get extensionMinutes =>
      intent?['additionalMinutes'] as int? ?? quote?.minutes;
  String? get extensionAmount =>
      intent?['expectedAmount'] as String? ?? quote?.amount;
  String? get extensionCurrency =>
      intent?['currency'] as String? ?? quote?.currency;

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
        'currency': fresh.currency!,
        'expectedEndAt': fresh.expiresAt!,
      };
      await store.write(Map<String, Object>.from(intent!));
    } catch (e) {
      message = 'No pudimos confirmar la extensión. Consulta su resultado antes de repetir.';
    } finally {
      busy = false;
      emit();
    }
  }

  Future<void> startExtensionPayment() async {
    if (busy || intent == null || payment != null || _disposed) return;
    busy = true;
    message = null;
    emit();
    try {
      await _send();
    } catch (e) {
      message = paymentError(e);
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
    _readRevision++;
    _reloadRequested = false;
    if (_loadCompletion?.isCompleted == false) _loadCompletion!.complete();
    _loadCompletion = null;
    super.dispose();
  }
}
