import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../../core/network/api_client.dart';
import '../../parking/data/parking_contract.dart';

abstract interface class ActiveParkingGateway {
  Future<List<ParkingReceipt>> mine(String owner);
  Future<ParkingReceipt> session(String id);
  Future<ParkingReceipt> close(String id);
  Future<ParkingRules> quote(String id, int? minutes);
  Future<Map> extend(String id, Map<String, Object> request);
}

class ActiveParkingService implements ActiveParkingGateway {
  ActiveParkingService(this.api);
  final ApiClient? api;
  ApiClient get client => api ?? (throw invalidParking);
  @override
  Future<List<ParkingReceipt>> mine(String owner) =>
      ParkingService(api).mine(owner);
  Future<Map> _read(
    ApiMethod method,
    String path, {
    Map<String, Object>? body,
  }) async {
    final r = await client.request(
      method,
      path,
      body: body,
      responseDecoder: parkingJson,
    );
    if (r.body is! Map) throw invalidParking;
    return r.body as Map;
  }

  @override
  Future<ParkingReceipt> session(String id) async {
    final s = ParkingReceipt(
      await _read(ApiMethod.get, 'parking-sessions/${Uri.encodeComponent(id)}'),
    );
    if (s.id != id) throw invalidParking;
    return s;
  }

  @override
  Future<ParkingReceipt> close(String id) async {
    final s = ParkingReceipt(
      await _read(
        ApiMethod.post,
        'parking-sessions/${Uri.encodeComponent(id)}/close',
      ),
    );
    if (s.id != id || s.status != 'COMPLETED') throw invalidParking;
    return s;
  }

  @override
  Future<ParkingRules> quote(String id, int? minutes) async {
    final r = await client.request(
      ApiMethod.get,
      'parking-sessions/${Uri.encodeComponent(id)}/extensions/quote',
      query: {if (minutes != null) 'additionalMinutes': '$minutes'},
      responseDecoder: parkingJson,
    );
    if (r.body is! Map) throw invalidParking;
    final q = ParkingRules(r.body as Map);
    if (q.minutes != minutes) throw invalidParking;
    return q;
  }

  @override
  Future<Map> extend(String id, Map<String, Object> request) async {
    final r = await _read(
      ApiMethod.post,
      'parking-sessions/${Uri.encodeComponent(id)}/extensions/mobile',
      body: request,
    );
    if (r['parkingSessionId'] != id ||
        r['additionalMinutes'] != request['additionalMinutes'] ||
        r['paymentId'] is! String) {
      throw invalidParking;
    }
    return r;
  }
}

abstract interface class ExtensionStore {
  Future<Map<String, dynamic>?> read();
  Future<void> write(Map<String, Object> intent);
  Future<void> clear();
}

class SecureExtensionStore implements ExtensionStore {
  SecureExtensionStore(String namespace)
    : key = 'simertpi.extension.v1.${base64Url.encode(utf8.encode(namespace))}';
  final String key;
  final storage = const FlutterSecureStorage(
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.unlocked_this_device,
    ),
  );
  @override
  Future<Map<String, dynamic>?> read() async {
    final s = await storage.read(key: key);
    return s == null ? null : jsonDecode(s) as Map<String, dynamic>;
  }

  @override
  Future<void> write(Map<String, Object> intent) =>
      storage.write(key: key, value: jsonEncode(intent));
  @override
  Future<void> clear() => storage.delete(key: key);
}

String activeStatus(String status) => switch (status) {
  'ACTIVE' => 'Activo',
  'EXTENDED' => 'Tiempo extendido',
  'EXPIRED' => 'Tiempo contratado finalizado',
  'MAX_TIME_REACHED' => 'Límite de tiempo alcanzado',
  'COMPLETED' => 'Finalizado',
  _ => 'Pendiente de resolución',
};
const openParkingStates = {'ACTIVE', 'EXTENDED', 'EXPIRED', 'MAX_TIME_REACHED'};
String remainingTime(ParkingReceipt s, DateTime now) {
  final seconds = s.expectedEndAt.difference(now).inSeconds.clamp(0, 99999999);
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(seconds ~/ 3600)}:${two(seconds ~/ 60 % 60)}:${two(seconds % 60)}';
}
