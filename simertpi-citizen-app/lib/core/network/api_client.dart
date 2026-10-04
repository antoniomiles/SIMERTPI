import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import '../errors/app_failure.dart';
import '../logging/app_log.dart';

const correlationHeader = 'X-Correlation-ID';
final _safeCorrelation = RegExp(r'^[A-Za-z0-9._:-]{1,128}$');

enum ApiMethod { get, post, put, delete }

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

  Future<ApiResponse> request(
    ApiMethod method,
    String path, {
    Object? body,
    bool authenticated = true,
    Map<String, String> headers = const {},
    Map<String, String> query = const {},
    String? correlationId,
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
      final returned = response.headers.value(correlationHeader);
      final effectiveId =
          returned != null && _safeCorrelation.hasMatch(returned)
          ? returned
          : id;
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.drain<void>().timeout(readTimeout);
        if (response.statusCode == 401 && authenticated) {
          onUnauthorized?.call(extra['Authorization']);
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
        );
      }
      final text = await utf8.decoder
          .bind(response)
          .join()
          .timeout(readTimeout);
      log.requestResult(success: true, correlationId: effectiveId);
      return ApiResponse(
        response.statusCode,
        text.isEmpty ? null : jsonDecode(text),
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
        correlationId: id,
        outcomeUnknown: write,
      );
    } on SocketException {
      pending?.abort();
      throw AppFailure(
        write ? FailureKind.unknown : FailureKind.offline,
        correlationId: id,
        outcomeUnknown: write,
      );
    } catch (_) {
      pending?.abort();
      throw AppFailure(
        FailureKind.unknown,
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

typedef VoidUnauthorized = void Function(String? authorization);
