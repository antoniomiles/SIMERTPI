import 'package:flutter/services.dart';

import '../../../core/network/api_client.dart';
import '../../../core/errors/app_failure.dart';
import '../../../core/logging/app_log.dart';
import 'session_store.dart';

class DeviceBinding {
  const DeviceBinding(
    this.owner,
    this.username,
    this.credentialId,
    this.deviceId, {
    this.usable = true,
  });
  final bool usable;
  final String owner, username, credentialId, deviceId;
  factory DeviceBinding.fromJson(Map<Object?, Object?> json) => DeviceBinding(
    json['owner'] as String,
    json['username'] as String,
    json['credentialId'] as String,
    json['deviceId'] as String,
    usable: json['usable'] != false,
  );
  Map<String, Object?> toJson() => {
    'owner': owner,
    'username': username,
    'credentialId': credentialId,
    'deviceId': deviceId,
  };
}

abstract interface class SigningDevice {
  Future<List<DeviceBinding>> bindings();
  Future<Map<String, String>> prepare(String owner);
  Future<String> sign(String owner, String payload);
  Future<void> commitBinding(DeviceBinding binding);
  Future<void> removeBinding(String owner);
  Future<bool> invitationPresented();
  Future<void> markInvitationPresented();
}

class AndroidSigningDevice implements SigningDevice {
  const AndroidSigningDevice(this.namespace);
  final String namespace;
  static const channel = MethodChannel('simertpi/device_credential');
  Future<T?> call<T>(String method, [Map<String, Object?> args = const {}]) =>
      channel.invokeMethod<T>(method, {'namespace': namespace, ...args});
  @override
  Future<List<DeviceBinding>> bindings() async =>
      (await call<List<Object?>>('bindings') ?? [])
          .map((e) => DeviceBinding.fromJson(e as Map<Object?, Object?>))
          .toList();
  @override
  Future<Map<String, String>> prepare(String owner) async =>
      Map<String, String>.from(
        (await call<Map<Object?, Object?>>('prepare', {'owner': owner}))!,
      );
  @override
  Future<String> sign(String owner, String payload) async =>
      (await call<String>('sign', {'owner': owner, 'payload': payload}))!;
  @override
  Future<void> commitBinding(DeviceBinding binding) async {
    await call<void>('commit', binding.toJson());
  }

  @override
  Future<void> removeBinding(String owner) async {
    await call<void>('remove', {'owner': owner});
  }

  @override
  Future<bool> invitationPresented() async =>
      await call<bool>('invitationPresented') == true;
  @override
  Future<void> markInvitationPresented() async {
    await call<void>('markInvitationPresented');
  }
}

abstract interface class CredentialGateway {
  Future<Map<String, dynamic>> registration(
    String access,
    Map<String, String> key,
  );
  Future<void> register(String access, Map<String, dynamic> proof);
  Future<Map<String, dynamic>> challenge(String credential, {String? access});
  Future<MobileSession> authenticate(Map<String, dynamic> proof);
  Future<void> unlock(String access, Map<String, dynamic> proof);
  Future<void> revoke(String access, String credential);
}

class DeviceCredentialGateway implements CredentialGateway {
  const DeviceCredentialGateway(this.api);
  final ApiClient? api;
  Future<ApiResponse> _post(String route, Object body, [String? access]) async {
    final diagnostic = route == 'register/challenge'
        ? 'BIO_REGISTER_CHALLENGE'
        : route == 'register/verify'
        ? 'BIO_REGISTER_VERIFY'
        : null;
    try {
      final response =
          await (api ?? (throw const AppFailure(FailureKind.unavailable)))
              .request(
                ApiMethod.post,
                'auth/device/$route',
                body: body,
                authenticated: false,
                retryUnauthorized: false,
                headers: {
                  if (access != null) 'Authorization': 'Bearer $access',
                },
              );
      if (diagnostic != null) {
        const AppLog().biometric(
          '${diagnostic}_OK',
          source: 'HTTP',
          status: response.statusCode,
          path: '/api/v1/auth/device/$route',
          correlationId: response.correlationId,
        );
      }
      return response;
    } catch (error) {
      if (diagnostic != null) {
        const AppLog().biometric(
          '${diagnostic}_FAILED',
          source: 'HTTP',
          error: error,
          path: '/api/v1/auth/device/$route',
        );
      }
      rethrow;
    }
  }

  @override
  Future<Map<String, dynamic>> registration(
    String access,
    Map<String, String> key,
  ) async => Map<String, dynamic>.from(
    (await _post('register/challenge', key, access)).body as Map,
  );
  @override
  Future<void> register(String access, Map<String, dynamic> proof) async {
    await _post('register/verify', proof, access);
  }

  @override
  Future<Map<String, dynamic>> challenge(
    String credential, {
    String? access,
  }) async => Map<String, dynamic>.from(
    (await _post(access == null ? 'challenge' : 'unlock/challenge', {
          'credentialId': credential,
        }, access)).body
        as Map,
  );
  @override
  Future<MobileSession> authenticate(Map<String, dynamic> proof) async =>
      MobileSession.fromJson((await _post('authenticate', proof)).body);
  @override
  Future<void> unlock(String access, Map<String, dynamic> proof) async {
    await _post('unlock/verify', proof, access);
  }

  @override
  Future<void> revoke(String access, String credential) async {
    await _post('revoke', {'credentialId': credential}, access);
  }
}
