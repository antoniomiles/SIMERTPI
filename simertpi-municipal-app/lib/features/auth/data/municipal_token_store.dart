import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../../core/errors/municipal_auth_failure.dart';

class MunicipalStoredTokens {
  const MunicipalStoredTokens({
    required this.accessToken,
    required this.refreshToken,
    required this.accessExpiresAt,
    required this.refreshExpiresAt,
  });

  final String accessToken;
  final String refreshToken;
  final DateTime accessExpiresAt;
  final DateTime refreshExpiresAt;
}

abstract interface class MunicipalTokenStore {
  Future<MunicipalStoredTokens?> read();
  Future<void> write(MunicipalStoredTokens tokens);
  Future<void> clear();
}

class SecureMunicipalTokenStore implements MunicipalTokenStore {
  SecureMunicipalTokenStore({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();

  static const _key = 'simertpi.municipal.session.v1';
  final FlutterSecureStorage _storage;

  @override
  Future<MunicipalStoredTokens?> read() async {
    try {
      final value = await _storage.read(key: _key);
      if (value == null) return null;
      final json = jsonDecode(value);
      if (json is! Map<String, dynamic>) throw const FormatException();
      final access = json['accessToken'];
      final refresh = json['refreshToken'];
      final accessExpiry = DateTime.tryParse(
        '${json['accessExpiresAt'] ?? ''}',
      );
      final refreshExpiry = DateTime.tryParse(
        '${json['refreshExpiresAt'] ?? ''}',
      );
      if (access is! String ||
          refresh is! String ||
          accessExpiry == null ||
          refreshExpiry == null) {
        throw const FormatException();
      }
      return MunicipalStoredTokens(
        accessToken: access,
        refreshToken: refresh,
        accessExpiresAt: accessExpiry.toUtc(),
        refreshExpiresAt: refreshExpiry.toUtc(),
      );
    } on MunicipalAuthException {
      rethrow;
    } catch (_) {
      try {
        await clear();
      } catch (_) {
        // Do not include storage contents in errors or logs.
      }
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.secureStorage),
      );
    }
  }

  @override
  Future<void> write(MunicipalStoredTokens tokens) async {
    try {
      await _storage.write(
        key: _key,
        value: jsonEncode({
          'accessToken': tokens.accessToken,
          'refreshToken': tokens.refreshToken,
          'accessExpiresAt': tokens.accessExpiresAt.toUtc().toIso8601String(),
          'refreshExpiresAt': tokens.refreshExpiresAt.toUtc().toIso8601String(),
        }),
      );
    } catch (_) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.secureStorage),
      );
    }
  }

  @override
  Future<void> clear() async {
    try {
      await _storage.delete(key: _key);
    } catch (_) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.secureStorage),
      );
    }
  }
}
