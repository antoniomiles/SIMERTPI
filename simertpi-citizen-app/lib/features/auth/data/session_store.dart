import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class MobileSession {
  const MobileSession({
    required this.userId,
    required this.accessToken,
    required this.refreshToken,
    required this.accessExpiresAt,
    required this.refreshExpiresAt,
  });
  final String userId, accessToken, refreshToken;
  final DateTime accessExpiresAt, refreshExpiresAt;
  factory MobileSession.fromJson(Object? data) {
    if (data is! Map ||
        data['tokenType'] != 'Bearer' ||
        data['userId'] is! String ||
        data['accessToken'] is! String ||
        data['refreshToken'] is! String ||
        !RegExp(r'^[A-Za-z0-9_-]{43}$')
            .hasMatch(data['accessToken'] as String) ||
        !RegExp(r'^[A-Za-z0-9_-]{43}$')
            .hasMatch(data['refreshToken'] as String)) {
      throw const FormatException('Invalid session');
    }
    final access = DateTime.parse(data['accessExpiresAt'] as String).toUtc();
    final refresh = DateTime.parse(data['refreshExpiresAt'] as String).toUtc();
    if (access.isAfter(refresh)) {
      throw const FormatException('Invalid lifetimes');
    }
    return MobileSession(
      userId: data['userId'] as String,
      accessToken: data['accessToken'] as String,
      refreshToken: data['refreshToken'] as String,
      accessExpiresAt: access,
      refreshExpiresAt: refresh,
    );
  }
  Map<String, Object> toJson() => {
    'userId': userId,
    'tokenType': 'Bearer',
    'accessToken': accessToken,
    'refreshToken': refreshToken,
    'accessExpiresAt': accessExpiresAt.toIso8601String(),
    'refreshExpiresAt': refreshExpiresAt.toIso8601String(),
  };
  @override
  String toString() => 'MobileSession[redacted]';
}

abstract interface class SessionStore {
  Future<MobileSession?> read();
  Future<void> write(MobileSession session);
  Future<void> clear();
}

class SecureSessionStore implements SessionStore {
  SecureSessionStore(String namespace)
    : key = 'simertpi.session.v1.${base64Url.encode(utf8.encode(namespace))}';
  final String key;
  final FlutterSecureStorage storage = FlutterSecureStorage(
    iOptions: const IOSOptions(
      accessibility: KeychainAccessibility.unlocked_this_device,
    ),
  );
  @override
  Future<MobileSession?> read() async {
    final value = await storage.read(key: key);
    if (value == null) return null;
    return MobileSession.fromJson(jsonDecode(value));
  }

  @override
  Future<void> write(MobileSession session) =>
      storage.write(key: key, value: jsonEncode(session.toJson()));
  @override
  Future<void> clear() => storage.delete(key: key);
}
