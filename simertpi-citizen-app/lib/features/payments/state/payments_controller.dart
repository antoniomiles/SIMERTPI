import '../../discovery/data/parking_catalog.dart';

import 'package:flutter/foundation.dart';

import '../../parking/data/parking_contract.dart';
import '../../vehicles/data/vehicle_service.dart';
import '../data/payment_contract.dart';
import '../data/payment_intent_store.dart';

enum PaymentPhase {
  initial,
  loading,
  selection,
  ready,
  processing,
  result,
  uncertain,
  error,
}

class PaymentsController extends ChangeNotifier {
  PaymentsController({
    required this.owner,
    required this.parking,
    required this.catalog,
    required this.vehicles,
    required this.payments,
    required this.store,
    required this.dev,
    DateTime Function()? now,
  }) : now = now ?? DateTime.now;
  final String owner;
  final ParkingGateway parking;
  final ParkingCatalogGateway catalog;
  String? spaceCode, zoneName;
  bool get activated =>
      payment?.status == PaymentStatus.approved && session?.status == 'ACTIVE';
  final VehicleGateway vehicles;
  final PaymentGateway payments;
  final PaymentIntentStore store;
  final bool dev;
  final DateTime Function() now;
  PaymentPhase phase = PaymentPhase.initial;
  List<ParkingReceipt> choices = [];
  ParkingReceipt? session;
  CitizenVehicle? vehicle;
  ParkingRules? quote;
  CitizenPayment? payment;
  PaymentIntent? intent;
  String? message;
  bool methodSelected = false, _busy = false, _disposed = false;
  bool get busy => _busy;
  bool get canConfirm =>
      dev &&
      methodSelected &&
      !busy &&
      phase == PaymentPhase.ready &&
      quote?.quoted == true;
  bool get canRetry =>
      !busy &&
      payment?.retryable == true &&
      session?.status == 'PENDING_PAYMENT' &&
      session!.expectedEndAt.isAfter(now());
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void chooseMethod() {
    if (dev && !busy && phase == PaymentPhase.ready) {
      methodSelected = true;
      _notify();
    }
  }

  Future<void> _session(String id) async {
    final all = await parking.mine(owner);
    session = all.where((s) => s.id == id && s.owner == owner).firstOrNull;
    if (session == null) throw invalidParking;
  }

  Future<void> load([String? sessionId]) async {
    if (busy || _disposed) return;
    _busy = true;
    phase = PaymentPhase.loading;
    message = null;
    _notify();
    try {
      intent = await store.read();
      if (intent != null && intent!.owner != owner) throw invalidParking;
      // Never discard an unresolved operation to pay a different session.
      if (intent != null) {
        await _session(intent!.sessionId);
        if (intent!.paymentId != null) {
          await _accept(await payments.status(intent!.paymentId!));
          if (sessionId == null ||
              sessionId == intent!.sessionId ||
              payment!.waiting) {
            return;
          }
          // A confirmed terminal outcome permits a different session; keep the
          // old persisted key until the next explicit confirmation writes a new one.
          intent = null;
          payment = null;
        } else {
          phase = PaymentPhase.uncertain;
          return;
        }
      }
      final all = await parking.mine(owner);
      choices = all
          .where(
            (s) =>
                s.status == 'PENDING_PAYMENT' && s.expectedEndAt.isAfter(now()),
          )
          .toList();
      final target =
          sessionId ?? (choices.length == 1 ? choices.single.id : null);
      if (target == null) {
        phase = PaymentPhase.selection;
        return;
      }
      await _prepare(target);
    } catch (error) {
      phase = intent != null ? PaymentPhase.uncertain : PaymentPhase.error;
      message = paymentError(error);
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> select(String id) async {
    if (busy || intent != null || _disposed) return;
    _busy = true;
    phase = PaymentPhase.loading;
    message = null;
    _notify();
    try {
      await _prepare(id);
    } catch (error) {
      phase = PaymentPhase.error;
      message = paymentError(error);
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> _prepare(String id) async {
    await _session(id);
    final current = session!;
    if (current.status != 'PENDING_PAYMENT' ||
        !current.expectedEndAt.isAfter(now())) {
      throw invalidParking;
    }
    final locations = await catalog.load();
    final location = locations.spaces
        .where((s) => s.id == current.spaceId)
        .firstOrNull;
    if (location == null) {
      throw invalidParking;
    }
    spaceCode = location.code;
    zoneName = locations.zoneOf(location)?.name;
    final items = await vehicles.list();
    vehicle = items
        .where(
          (v) => v.id == current.vehicleId && v.userId == owner && v.active,
        )
        .firstOrNull;
    if (vehicle == null) throw invalidParking;
    final minutes = current.expectedEndAt
        .difference(current.startedAt)
        .inMinutes;
    final rules = await parking.rules(current.spaceId, minutes);
    if (!rules.quoted ||
        rules.code == null ||
        await parking.tariffId(rules.code!) != current.tariffId) {
      throw invalidParking;
    }
    quote = rules;
    methodSelected = false;
    phase = PaymentPhase.ready;
  }

  Future<void> confirm() async {
    if (!canConfirm || _disposed) return;
    _busy = true;
    phase = PaymentPhase.processing;
    message = null;
    _notify();
    final previous = quote!;
    bool saved = false;
    try {
      await _prepare(session!.id);
      if (_disposed) return;
      if (quote!.conditions != previous.conditions) {
        message = 'Las condiciones cambiaron. Revisa el nuevo importe antes de confirmar.';
        return;
      }
      phase = PaymentPhase.processing;
      final next = PaymentIntent(
        owner,
        session!.id,
        'TEST',
        createdAt: now().toUtc(),
      );
      // Persist BEFORE sending. An interrupted request must reuse this exact key.
      await store.write(next);
      saved = true;
      intent = next;
      if (_disposed) return;
      await _accept(await payments.create(next));
    } catch (error) {
      phase = saved ? PaymentPhase.uncertain : PaymentPhase.ready;
      message = paymentError(error);
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> _accept(CitizenPayment value) async {
    final pending = intent!;
    if (value.sessionId != pending.sessionId ||
        value.method != pending.method ||
        (pending.paymentId != null && pending.paymentId != value.id)) {
      throw invalidParking;
    }
    payment = value;
    intent = pending.identified(value.id);
    await store.write(intent!);
    if (_disposed) return;
    // Query the parking state; never derive activation locally from a UI action.
    await _session(value.sessionId);
    try {
      final locations = await catalog.load();
      final location = locations.spaces
          .where((s) => s.id == session!.spaceId)
          .firstOrNull;
      if (location != null) {
        spaceCode = location.code;
        zoneName = locations.zoneOf(location)?.name;
      }
      final items = await vehicles.list();
      vehicle = items
          .where((v) => v.id == session!.vehicleId && v.userId == owner)
          .firstOrNull;
    } catch (_) {
      /* Confirmed financial/session state remains authoritative if metadata is unavailable. */
    }
    phase = PaymentPhase.result;
  }

  Future<void> check() async {
    if (busy || intent == null || _disposed) return;
    _busy = true;
    message = null;
    _notify();
    try {
      final current = intent!;
      if (current.owner != owner) {
        throw invalidParking;
      }
      CitizenPayment value;
      if (current.paymentId == null) {
        if (!current.replayAllowed(now().toUtc())) {
          message = 'Esta operación requiere revisión de soporte. No intentes otro pago para esta solicitud.';
          phase = PaymentPhase.uncertain;
          return;
        }
        value = await payments.create(
          current,
        ); // explicit, same key/body, within server TTL
      } else {
        value = await payments.status(current.paymentId!);
        if (value.waiting) value = await payments.refresh(value.id);
      }
      await _accept(value);
    } catch (error) {
      phase = PaymentPhase.uncertain;
      message = paymentError(error);
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> reviewRetry() async {
    if (!canRetry || _disposed) return;
    _busy = true;
    message = null;
    _notify();
    try {
      // Re-read before permitting a new attempt. A concurrent approval wins.
      final fresh = await payments.status(payment!.id);
      await _accept(fresh);
      if (!fresh.retryable) return;
      await _prepare(fresh.sessionId);
      // Keep the old intent durable until confirm writes the new key. Back/restart
      // restores its known terminal status; it can never replay an old POST.
      intent = null;
      payment = null;
    } catch (error) {
      phase = PaymentPhase.uncertain;
      message = paymentError(error);
    } finally {
      _busy = false;
      _notify();
    }
  }

  Future<void> pendingRequests() async {
    if (busy || _disposed || payment == null || payment!.waiting) return;
    _busy = true;
    _notify();
    try {
      final all = await parking.mine(owner);
      choices = all
          .where(
            (s) =>
                s.owner == owner &&
                s.status == 'PENDING_PAYMENT' &&
                s.expectedEndAt.isAfter(now()),
          )
          .toList();
      // Confirmed terminal outcome: retain its durable record until a future
      // explicit confirmation replaces it. Never abandon an uncertain payment.
      intent = null;
      payment = null;
      session = null;
      quote = null;
      methodSelected = false;
      message = null;
      phase = PaymentPhase.selection;
    } catch (error) {
      message = paymentError(error);
    } finally {
      _busy = false;
      _notify();
    }
  }

  String get resultTitle => switch (payment?.status) {
    PaymentStatus.approved => 'Pago confirmado',
    PaymentStatus.declined => 'Pago rechazado',
    PaymentStatus.failed => 'Pago no completado',
    PaymentStatus.cancelled => 'Pago cancelado',
    PaymentStatus.refunded => 'Pago reembolsado',
    _ => 'Pago por confirmar',
  };
  String get resultMessage => payment?.status == PaymentStatus.approved
      ? session?.status == 'ACTIVE'
            ? 'El sistema confirmó la activación del estacionamiento.'
            : 'El pago fue aprobado. El estado del estacionamiento debe revisarse; no asumas que está activo.'
      : payment?.waiting == true
      ? 'Aún no hay confirmación definitiva. Comprueba el estado sin realizar otro pago.'
      : 'El sistema no confirmó un pago aprobado para esta solicitud.';
  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
