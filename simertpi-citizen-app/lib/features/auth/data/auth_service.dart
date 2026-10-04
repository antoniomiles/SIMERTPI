import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import 'session_store.dart';

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
  Future<MobileSession> login(String username, String password);
  Future<MobileSession> refresh(String refreshToken);
  Future<void> logout(String refreshToken);
  Future<void> register(RegistrationRequest request);
}

class AuthService implements AuthGateway {
  const AuthService(this.api);
  final ApiClient? api;
  ApiClient get _api =>
      api ?? (throw const AppFailure(FailureKind.unavailable));

  @override
  Future<MobileSession> login(String username, String password) async =>
      _session(
        await _api.request(
          ApiMethod.post,
          'auth/login',
          authenticated: false,
          body: {'username': username, 'password': password},
        ),
      );
  @override
  Future<MobileSession> refresh(String refreshToken) async => _session(
    await _api.request(
      ApiMethod.post,
      'auth/refresh',
      authenticated: false,
      body: {'refreshToken': refreshToken},
    ),
  );
  @override
  Future<void> logout(String refreshToken) async {
    await _api.request(
      ApiMethod.post,
      'auth/logout',
      authenticated: false,
      body: {'refreshToken': refreshToken},
    );
  }

  MobileSession _session(ApiResponse response) {
    try {
      return MobileSession.fromJson(response.body);
    } catch (_) {
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
