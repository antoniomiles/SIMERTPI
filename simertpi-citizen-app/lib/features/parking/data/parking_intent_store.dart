import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'parking_contract.dart';

abstract interface class ParkingIntentStore {
  Future<ParkingIntent?> read();
  Future<void> write(ParkingIntent value);
  Future<void> clear();
}

class SecureParkingIntentStore implements ParkingIntentStore {
  SecureParkingIntentStore(String namespace)
    : key =
          'simertpi.parking.intent.v1.${base64Url.encode(utf8.encode(namespace))}';
  final String key;
  final storage = FlutterSecureStorage(
    iOptions: const IOSOptions(
      accessibility: KeychainAccessibility.unlocked_this_device,
    ),
  );
  @override
  Future<ParkingIntent?> read() async {
    final text = await storage.read(key: key);
    return text == null
        ? null
        : ParkingIntent.fromJson(jsonDecode(text) as Map);
  }

  @override
  Future<void> write(ParkingIntent value) =>
      storage.write(key: key, value: jsonEncode(value.toJson()));
  @override
  Future<void> clear() => storage.delete(key: key);
}
