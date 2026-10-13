import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import '../errors/app_failure.dart';
import '../logging/app_log.dart';

const correlationHeader = 'X-Correlation-ID';
final _safeCorrelation = RegExp(r'^[A-Za-z0-9._:-]{1,128}$');

enum ApiMethod { get, post, put, patch, delete }

class ApiResponse {
  const ApiResponse(this.statusCode, this.body, this.correlationId);
  final int statusCode;
  final Object? body;
  final String correlationId;
}

class ApiClient {
  ApiClient({
    required this.baseUrl,
    HttpClient? client,
    this.readTimeout = const Duration(seconds: 15),
    Duration connectTimeout = const Duration(seconds: 10),
    this.headersProvider,
    this.onUnauthorized,
    this.onSessionRejected,
    this.onAuthorizationRejected,
    this.log = const AppLog(),
  }) : _client = client ?? HttpClient() {
    _client.connectionTimeout = connectTimeout;
  }
  final Uri baseUrl;
  final HttpClient _client;
  final Duration readTimeout;
  // Authentication is supplied in memory; this client never persists credentials.
  final Future<Map<String, String>> Function()? headersProvider;
  final AppLog log;
  final VoidUnauthorized? onUnauthorized;
  final Future<void> Function()? onSessionRejected;
  final Future<void> Function(String? authorization)? onAuthorizationRejected;

  Future<ApiResponse> request(
    ApiMethod method,
    String path, {
    Object? body,
    bool authenticated = true,
    Map<String, String> headers = const {},
    Map<String, String> query = const {},
    String? correlationId,
    bool retryUnauthorized = true,
    Object? Function(String)? responseDecoder,
  }) async {
    if (path.startsWith('/') ||
        path.contains('..') ||
        Uri.parse(path).hasScheme ||
        path.contains('?') ||
        path.contains('#')) {
      throw ArgumentError('Only relative API paths are accepted');
    }
    final id = correlationId != null && _safeCorrelation.hasMatch(correlationId)
        ? correlationId
        : _newCorrelationId();
    final uri = baseUrl.replace(
      path: '${baseUrl.path.replaceFirst(RegExp(r'/$'), '')}/$path',
      queryParameters: query.isEmpty ? null : query,
    );
    final write = method != ApiMethod.get;
    HttpClientRequest? pending;
    final biometricRequest = {
      'auth/device/register/challenge',
      'auth/device/register/verify',
      'auth/refresh',
    }.contains(path);
    int? biometricHttpStatus;
    try {
      final extra = <String, String>{
        if (authenticated)
          ...await headersProvider?.call() ?? <String, String>{},
        ...headers,
      };
      pending = await _client
          .openUrl(method.name.toUpperCase(), uri)
          .timeout(readTimeout);
      // Do not follow redirects carrying future authentication to another host.
      pending.followRedirects = false;
      pending.headers.set(HttpHeaders.acceptHeader, 'application/json');
      for (final entry in extra.entries) {
        if (entry.key.contains(RegExp(r'[\r\n]')) ||
            entry.value.contains(RegExp(r'[\r\n]'))) {
          throw const AppFailure(FailureKind.unknown);
        }
        pending.headers.set(entry.key, entry.value);
      }
      pending.headers.set(correlationHeader, id);
      if (body != null) {
        pending.headers.contentType = ContentType.json;
        pending.write(jsonEncode(body));
      }
      final response = await pending.close().timeout(readTimeout);
      if (biometricRequest) {
        biometricHttpStatus = response.statusCode;
      }
      final returned = response.headers.value(correlationHeader);
      final effectiveId =
          returned != null && _safeCorrelation.hasMatch(returned)
          ? returned
          : id;
      if (response.statusCode < 200 || response.statusCode >= 300) {
        String? safeCode;
        String? safeMessage;
        if (response.statusCode == 409) {
          try {
            final error = jsonDecode(
              await utf8.decoder.bind(response).join().timeout(readTimeout),
            );
            const allowed = {
              'VEHICLE_OCCUPIED',
              'SPACE_UNAVAILABLE',
              'SPACE_DISABLED',
              'VEHICLE_SPACE_REENTRY_COOLDOWN',
            };
            if (error is Map && allowed.contains(error['code'])) {
              safeCode = error['code'] as String;
              final message = error['message'];
              if (safeCode == 'VEHICLE_SPACE_REENTRY_COOLDOWN' &&
                  message is String &&
                  RegExp(
                    r'^Ya cumpliste las 4 horas continuas en este espacio\. Podrás volver a utilizarlo a partir de las ([01]\d|2[0-3]):[0-5]\d\. Puedes elegir otro espacio disponible\.$',
                  ).hasMatch(message)) {
                safeMessage = message;
              }
            }
          } catch (_) {}
        } else {
          await response.drain<void>().timeout(readTimeout);
        }
        if (response.statusCode == 401 && authenticated && retryUnauthorized) {
          final recovered =
              await onUnauthorized?.call(extra['Authorization']) ?? false;
          if (recovered && retryUnauthorized) {
            return await request(
              method,
              path,
              body: body,
              authenticated: authenticated,
              headers: headers,
              query: query,
              correlationId: id,
              retryUnauthorized: false,
              responseDecoder: responseDecoder,
            );
          }
        }
        if (response.statusCode == 401 && authenticated && !retryUnauthorized) {
          if (onAuthorizationRejected != null) {
            await onAuthorizationRejected!(extra['Authorization']);
          } else {
            await onSessionRejected?.call();
          }
        }
        throw AppFailure(
          response.statusCode == 401
              ? FailureKind.unauthorized
              : response.statusCode == 403
              ? FailureKind.forbidden
              : response.statusCode == 409
              ? FailureKind.conflict
              : response.statusCode == 400
              ? FailureKind.invalidRequest
              : response.statusCode >= 500
              ? FailureKind.unavailable
              : FailureKind.rejected,
          correlationId: effectiveId,
          httpStatus: biometricHttpStatus,
          code: safeCode,
          messageOverride: safeMessage,
        );
      }
      final text = await utf8.decoder
          .bind(response)
          .join()
          .timeout(readTimeout);
      log.requestResult(success: true, correlationId: effectiveId);
      return ApiResponse(
        response.statusCode,
        text.isEmpty ? null : (responseDecoder ?? jsonDecode)(text),
        effectiveId,
      );
    } on AppFailure {
      pending?.abort();
      log.requestResult(success: false, correlationId: id);
      rethrow;
    } on TimeoutException {
      pending?.abort();
      throw AppFailure(
        FailureKind.timeout,
        diagnosticExceptionClass: biometricRequest ? 'TimeoutException' : null,
        httpStatus: biometricHttpStatus,
        correlationId: id,
        outcomeUnknown: write,
      );
    } on SocketException {
      pending?.abort();
      throw AppFailure(
        write ? FailureKind.unknown : FailureKind.offline,
        diagnosticExceptionClass: biometricRequest ? 'SocketException' : null,
        httpStatus: biometricHttpStatus,
        correlationId: id,
        code: 'NETWORK_UNREACHABLE',
        outcomeUnknown: write,
      );
    } catch (error) {
      pending?.abort();
      String? diagnosticClass;
      if (biometricRequest) {
        final type = error.runtimeType.toString();
        diagnosticClass =
            {
              'FormatException',
              'TypeError',
              'StateError',
              'HandshakeException',
              'TlsException',
              'HttpException',
            }.contains(type)
            ? type
            : 'OTHER';
      }
      throw AppFailure(
        FailureKind.unknown,
        diagnosticExceptionClass: diagnosticClass,
        httpStatus: biometricHttpStatus,
        correlationId: id,
        outcomeUnknown: write,
      );
    }
  }

  String _newCorrelationId() {
    final random = Random.secure();
    return List.generate(
      16,
      (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
    ).join();
  }

  void close() => _client.close(force: true);
}

typedef VoidUnauthorized = Future<bool> Function(String? authorization);
