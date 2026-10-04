import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';

import 'support/auth_test_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  test('Secure platform adapter writes only session DTO, namespaces environments and clears', () async {
    final values = <String, String>{};
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          final arguments = Map<String, dynamic>.from(call.arguments as Map);
          final key = arguments['key'] as String;
          switch (call.method) {
            case 'write':
              values[key] = arguments['value'] as String;
              return null;
            case 'read':
              return values[key];
            case 'delete':
              values.remove(key);
              return null;
            default:
              throw PlatformException(code: 'unsupported');
          }
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    final store = SecureSessionStore('dev:fixture-backend');
    final other = SecureSessionStore('qa:fixture-backend');
    await store.write(fixtureSession());
    final payload = jsonDecode(values.values.single) as Map;
    expect(payload.keys, isNot(contains('password')));
    expect(payload.keys, isNot(contains('username')));
    expect((await store.read())!.accessToken, 'A' * 43);
    expect(await other.read(), isNull);
    await store.clear();
    expect(values, isEmpty);
    expect(calls, containsAll(['write', 'read', 'delete']));
  });
}
