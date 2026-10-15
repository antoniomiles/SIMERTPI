import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../../core/config/municipal_app_config.dart';
import '../../../core/errors/municipal_auth_failure.dart';

class MunicipalHttpResponse {
  const MunicipalHttpResponse(this.statusCode, this.body);

  final int statusCode;
  final String body;
}

abstract interface class MunicipalHttpTransport {
  Future<MunicipalHttpResponse> send(
    String method,
    Uri uri, {
    Map<String, String> headers = const {},
    String? body,
  });
}

class IoMunicipalHttpTransport implements MunicipalHttpTransport {
  IoMunicipalHttpTransport({
    HttpClient? client,
    this.timeout = const Duration(seconds: 15),
  }) : _client = client ?? HttpClient() {
    _client.connectionTimeout = timeout;
  }

  final HttpClient _client;
  final Duration timeout;

  @override
  Future<MunicipalHttpResponse> send(
    String method,
    Uri uri, {
    Map<String, String> headers = const {},
    String? body,
  }) async {
    try {
      final request = await _client.openUrl(method, uri).timeout(timeout);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      for (final entry in headers.entries) {
        request.headers.set(entry.key, entry.value);
      }
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(body);
      }
      final response = await request.close().timeout(timeout);
      final responseBody = await utf8.decoder
          .bind(response)
          .join()
          .timeout(timeout);
      return MunicipalHttpResponse(response.statusCode, responseBody);
    } on TimeoutException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    } on SocketException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    } on HandshakeException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    } on HttpException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    }
  }

  void close() => _client.close(force: true);
}

class MunicipalTokenResponse {
  const MunicipalTokenResponse({
    required this.userId,
    required this.accessToken,
    required this.refreshToken,
    required this.accessExpiresAt,
    required this.refreshExpiresAt,
  });

  final String userId;
  final String accessToken;
  final String refreshToken;
  final DateTime accessExpiresAt;
  final DateTime refreshExpiresAt;

  factory MunicipalTokenResponse.fromJson(Object? value) {
    final json = _object(value);
    final userId = json['userId'];
    final tokenType = json['tokenType'];
    final access = json['accessToken'];
    final refresh = json['refreshToken'];
    final accessExpiry = DateTime.tryParse('${json['accessExpiresAt'] ?? ''}');
    final refreshExpiry = DateTime.tryParse(
      '${json['refreshExpiresAt'] ?? ''}',
    );
    if (userId is! String ||
        userId.isEmpty ||
        tokenType != 'Bearer' ||
        access is! String ||
        !_isToken(access) ||
        refresh is! String ||
        !_isToken(refresh) ||
        accessExpiry == null ||
        refreshExpiry == null ||
        !refreshExpiry.isAfter(accessExpiry)) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.invalidResponse),
      );
    }
    return MunicipalTokenResponse(
      userId: userId,
      accessToken: access,
      refreshToken: refresh,
      accessExpiresAt: accessExpiry.toUtc(),
      refreshExpiresAt: refreshExpiry.toUtc(),
    );
  }
}

class MunicipalIdentityResponse {
  const MunicipalIdentityResponse({
    required this.userId,
    required this.username,
    required this.roles,
    required this.enabled,
  });

  final String userId;
  final String username;
  final List<String> roles;
  final bool enabled;

  factory MunicipalIdentityResponse.fromJson(Object? value) {
    final json = _object(value);
    final userId = json['userId'];
    final username = json['username'];
    final rawRoles = json['roles'];
    final enabled = json['enabled'];
    if (userId is! String ||
        userId.isEmpty ||
        username is! String ||
        username.trim().isEmpty ||
        rawRoles is! List ||
        rawRoles.any((role) => role is! String) ||
        enabled is! bool) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.invalidResponse),
      );
    }
    return MunicipalIdentityResponse(
      userId: userId,
      username: username,
      roles: List.unmodifiable(rawRoles.cast<String>()),
      enabled: enabled,
    );
  }
}

class MunicipalAuthApi {
  MunicipalAuthApi({required this._config, required this._transport});

  final MunicipalAppConfig _config;
  final MunicipalHttpTransport _transport;

  Future<MunicipalTokenResponse> login(String username, String password) async {
    final response = await _send(
      'POST',
      'login',
      body: {'username': username, 'password': password},
    );
    return MunicipalTokenResponse.fromJson(_successfulJson(response));
  }

  Future<MunicipalTokenResponse> refresh(String refreshToken) async {
    final response = await _send(
      'POST',
      'refresh',
      body: {'refreshToken': refreshToken},
    );
    return MunicipalTokenResponse.fromJson(_successfulJson(response));
  }

  Future<MunicipalIdentityResponse> me(String accessToken) async {
    final response = await _send('GET', 'me', accessToken: accessToken);
    return MunicipalIdentityResponse.fromJson(_successfulJson(response));
  }

  Future<void> logout(String refreshToken) async {
    final response = await _send(
      'POST',
      'logout',
      body: {'refreshToken': refreshToken},
    );
    if (response.statusCode != 204) _throwStatus(response.statusCode);
  }

  Future<MunicipalHttpResponse> _send(
    String method,
    String action, {
    Map<String, String>? body,
    String? accessToken,
  }) {
    final base = _config.apiBaseUri;
    if (base == null) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    }
    final path =
        '${base.path.replaceFirst(RegExp(r'/+$'), '')}/municipal/auth/';
    final uri = base.replace(path: '$path$action', query: null, fragment: null);
    return _guard(
      () => _transport.send(
        method,
        uri,
        headers: {
          if (accessToken != null)
            HttpHeaders.authorizationHeader: 'Bearer $accessToken',
        },
        body: body == null ? null : jsonEncode(body),
      ),
    );
  }

  Future<MunicipalHttpResponse> _guard(
    Future<MunicipalHttpResponse> Function() operation,
  ) async {
    try {
      final response = await operation();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        _throwStatus(response.statusCode);
      }
      return response;
    } on MunicipalAuthException {
      rethrow;
    } on FormatException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.invalidResponse),
      );
    } on TimeoutException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    } on SocketException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    } on HandshakeException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    } on HttpException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable),
      );
    }
  }

  Object? _successfulJson(MunicipalHttpResponse response) {
    try {
      return jsonDecode(response.body);
    } on FormatException {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.invalidResponse),
      );
    }
  }

  Never _throwStatus(int statusCode) => throw MunicipalAuthException(
    MunicipalAuthFailure.fromHttpStatus(statusCode),
  );
}

Map<String, Object?> _object(Object? value) {
  if (value is Map<String, Object?>) return value;
  if (value is Map) return value.cast<String, Object?>();
  throw const MunicipalAuthException(
    MunicipalAuthFailure(MunicipalAuthFailureKind.invalidResponse),
  );
}

bool _isToken(String value) => RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(value);
