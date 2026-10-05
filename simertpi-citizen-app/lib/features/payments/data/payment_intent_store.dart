import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'payment_contract.dart';

abstract interface class PaymentIntentStore {
  Future<PaymentIntent?> read();
  Future<void> write(PaymentIntent intent);
}

class SecurePaymentIntentStore implements PaymentIntentStore {
  SecurePaymentIntentStore(String namespace, {FlutterSecureStorage? storage})
    : key =
          'simertpi.payment.intent.v1.${base64Url.encode(utf8.encode(namespace))}',
      storage =
          storage ??
          const FlutterSecureStorage(
            iOptions: IOSOptions(
              accessibility: KeychainAccessibility.unlocked_this_device,
            ),
          );
  final String key;
  final FlutterSecureStorage storage;
  @override
  Future<PaymentIntent?> read() async {
    final text = await storage.read(key: key);
    return text == null
        ? null
        : PaymentIntent.fromJson(jsonDecode(text) as Map);
  }

  @override
  Future<void> write(PaymentIntent intent) =>
      storage.write(key: key, value: jsonEncode(intent.toJson()));
}
