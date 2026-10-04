import 'dart:convert';

import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';

/// Values match CreateUserRequest. No role, owner or session token is accepted.
class RegistrationRequest {
  const RegistrationRequest({
    required this.username,
    required this.email,
    required this.password,
    required this.firstName,
    required this.lastName,
    this.phone,
  });
  final String username, email, password, firstName, lastName;
  final String? phone;
  Map<String, Object?> toJson() => {
    'username': username,
    'email': email,
    'password': password,
    'firstName': firstName,
    'lastName': lastName,
    'phone': phone,
  };
  @override
  String toString() => 'RegistrationRequest';
}

abstract interface class AuthGateway {
  Future<void> verifyCitizen(String username, String password);
  Future<void> register(RegistrationRequest request);
}

class AuthService implements AuthGateway {
  const AuthService(this.api);
  final ApiClient? api;
  ApiClient get _api =>
      api ?? (throw const AppFailure(FailureKind.unavailable));

  @override
  Future<void> verifyCitizen(String username, String password) async {
    // There is no login/me endpoint. This existing read is CITIZEN-only,
    // side-effect free, returns no identity details, and is NOT a login DTO.
    final response = await _api.request(
      ApiMethod.get,
      'notifications/preferences',
      authenticated: false,
      headers: {'Authorization': basicAuthorization(username, password)},
    );
    if (response.body is! List ||
        (response.body as List).any(
          (item) =>
              item is! Map ||
              item['channel'] is! String ||
              item['enabled'] is! bool,
        )) {
      throw const AppFailure(FailureKind.unknown);
    }
  }

  @override
  Future<void> register(RegistrationRequest request) async {
    final response = await _api.request(
      ApiMethod.post,
      'users',
      authenticated: false,
      body: request.toJson(),
    );
    final body = response.body;
    // UserResponse is not a token and must never imply authenticated state.
    if (response.statusCode != 201 ||
        body is! Map ||
        body['id'] is! String ||
        body['username'] != request.username ||
        body['enabled'] is! bool) {
      throw const AppFailure(FailureKind.unknown, outcomeUnknown: true);
    }
  }
}

String basicAuthorization(String username, String password) =>
    'Basic ${base64Encode(utf8.encode('$username:$password'))}';
