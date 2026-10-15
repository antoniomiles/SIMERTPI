import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/core/config/municipal_app_config.dart';
import 'package:simertpi_municipal_app/core/errors/municipal_auth_failure.dart';
import 'package:simertpi_municipal_app/features/auth/data/municipal_auth_api.dart';
import 'package:simertpi_municipal_app/features/auth/data/municipal_auth_repository_impl.dart';
import 'package:simertpi_municipal_app/features/auth/data/municipal_token_store.dart';

const _config = MunicipalAppConfig(
  environment: MunicipalEnvironment.development,
  apiBaseUrl: 'https://municipal.example/api/v1',
);
final _access = List.filled(43, 'a').join();
final _refresh = List.filled(43, 'b').join();
final _nextAccess = List.filled(43, 'c').join();
final _nextRefresh = List.filled(43, 'd').join();
const _userId = '7abf14d8-12dd-4e9d-9e7d-9085cabdb44a';

String _sessionJson({String? access, String? refresh}) => jsonEncode({
  'userId': _userId,
  'tokenType': 'Bearer',
  'accessToken': access ?? _access,
  'refreshToken': refresh ?? _refresh,
  'accessExpiresAt': '2026-10-14T22:15:00Z',
  'refreshExpiresAt': '2026-11-13T22:00:00Z',
});

String _identityJson({
  String userId = _userId,
  List<String> roles = const ['INSPECTOR'],
  bool enabled = true,
}) => jsonEncode({
  'userId': userId,
  'username': 'inspector.test',
  'roles': roles,
  'enabled': enabled,
});

class _Request {
  const _Request(this.method, this.uri, this.headers, this.body);
  final String method;
  final Uri uri;
  final Map<String, String> headers;
  final String? body;
}

class _Transport implements MunicipalHttpTransport {
  _Transport(this.handler);
  final FutureOr<MunicipalHttpResponse> Function(_Request) handler;
  final requests = <_Request>[];

  @override
  Future<MunicipalHttpResponse> send(
    String method,
    Uri uri, {
    Map<String, String> headers = const {},
    String? body,
  }) async {
    final request = _Request(method, uri, headers, body);
    requests.add(request);
    return handler(request);
  }
}

class _TokenStore implements MunicipalTokenStore {
  _TokenStore([this.value]);
  MunicipalStoredTokens? value;
  int writes = 0;
  int clears = 0;

  @override
  Future<MunicipalStoredTokens?> read() async => value;

  @override
  Future<void> write(MunicipalStoredTokens tokens) async {
    writes++;
    value = tokens;
  }

  @override
  Future<void> clear() async {
    clears++;
    value = null;
  }
}

MunicipalStoredTokens _storedTokens({String? access, String? refresh}) =>
    MunicipalStoredTokens(
      accessToken: access ?? _access,
      refreshToken: refresh ?? _refresh,
      accessExpiresAt: DateTime.utc(2026, 10, 14, 22, 15),
      refreshExpiresAt: DateTime.utc(2026, 11, 13, 22),
    );

void main() {
  group('municipal authentication API contracts', () {
    test(
      'uses only the municipal endpoints and exact backend DTO fields',
      () async {
        final transport = _Transport((request) {
          if (request.uri.path.endsWith('/me')) {
            return MunicipalHttpResponse(200, _identityJson());
          }
          if (request.uri.path.endsWith('/logout')) {
            return const MunicipalHttpResponse(204, '');
          }
          return MunicipalHttpResponse(200, _sessionJson());
        });
        final api = MunicipalAuthApi(config: _config, transport: transport);

        await api.login(' inspector.test ', 'not-persisted');
        await api.me(_access);
        await api.refresh(_refresh);
        await api.logout(_refresh);

        expect(transport.requests.map((request) => request.uri.path), [
          '/api/v1/municipal/auth/login',
          '/api/v1/municipal/auth/me',
          '/api/v1/municipal/auth/refresh',
          '/api/v1/municipal/auth/logout',
        ]);
        expect(transport.requests[0].method, 'POST');
        expect(jsonDecode(transport.requests[0].body!), {
          'username': ' inspector.test ',
          'password': 'not-persisted',
        });
        expect(
          transport.requests[1].headers[HttpHeaders.authorizationHeader],
          'Bearer $_access',
        );
        expect(
          transport.requests[2].headers.containsKey(
            HttpHeaders.authorizationHeader,
          ),
          isFalse,
        );
        expect(
          transport.requests[3].headers.containsKey(
            HttpHeaders.authorizationHeader,
          ),
          isFalse,
        );
      },
    );

    test(
      'maps 401, 403, network, timeout, and malformed response safely',
      () async {
        for (final entry
            in <(MunicipalHttpResponse?, Object?, MunicipalAuthFailureKind)>[
              (
                const MunicipalHttpResponse(401, '{}'),
                null,
                MunicipalAuthFailureKind.unauthenticated,
              ),
              (
                const MunicipalHttpResponse(403, '{}'),
                null,
                MunicipalAuthFailureKind.forbidden,
              ),
              (
                const MunicipalHttpResponse(503, '{}'),
                null,
                MunicipalAuthFailureKind.unavailable,
              ),
              (
                null,
                const SocketException('offline'),
                MunicipalAuthFailureKind.unavailable,
              ),
              (
                null,
                TimeoutException('late'),
                MunicipalAuthFailureKind.unavailable,
              ),
              (
                const MunicipalHttpResponse(200, '{bad'),
                null,
                MunicipalAuthFailureKind.invalidResponse,
              ),
              (
                const MunicipalHttpResponse(200, '{"accessToken":false}'),
                null,
                MunicipalAuthFailureKind.invalidResponse,
              ),
            ]) {
          final transport = _Transport((_) {
            if (entry.$2 != null) throw entry.$2!;
            return entry.$1!;
          });
          final api = MunicipalAuthApi(config: _config, transport: transport);
          try {
            await api.login('inspector', 'password');
            fail('Expected ${entry.$3}');
          } on MunicipalAuthException catch (error) {
            expect(error.failure.kind, entry.$3);
          }
        }
      },
    );

    test('rejects an invalid base URL instead of making a request', () async {
      final transport = _Transport(
        (_) => const MunicipalHttpResponse(200, '{}'),
      );
      const config = MunicipalAppConfig(
        environment: MunicipalEnvironment.development,
        apiBaseUrl: 'https://municipal.example/',
      );
      final api = MunicipalAuthApi(config: config, transport: transport);

      await expectLater(
        api.login('inspector', 'password'),
        throwsA(isA<MunicipalAuthException>()),
      );
      expect(transport.requests, isEmpty);
    });
  });

  group('municipal session repository', () {
    test(
      'persists tokens only after server /me confirms enabled INSPECTOR',
      () async {
        final transport = _Transport(
          (request) => MunicipalHttpResponse(
            200,
            request.uri.path.endsWith('/me') ? _identityJson() : _sessionJson(),
          ),
        );
        final store = _TokenStore();
        final repository = MunicipalAuthRepositoryImpl(
          api: MunicipalAuthApi(config: _config, transport: transport),
          tokenStore: store,
          clock: () => DateTime.utc(2026, 10, 14, 22),
        );

        final session = await repository.authenticate(
          username: 'inspector.test',
          password: 'not-stored',
        );

        expect(session.identity.canInspect, isTrue);
        expect(session.identity.username, 'inspector.test');
        expect(session.accessExpiresAt, DateTime.utc(2026, 10, 14, 22, 15));
        expect(store.writes, 1);
        expect(store.value?.accessToken, _access);
        expect(store.value?.refreshToken, _refresh);
      },
    );

    test(
      'does not persist citizen, disabled, or mismatched /me identities',
      () async {
        for (final identity in [
          _identityJson(roles: const ['CITIZEN']),
          _identityJson(enabled: false),
          _identityJson(userId: 'another-user'),
        ]) {
          final transport = _Transport(
            (request) => MunicipalHttpResponse(
              200,
              request.uri.path.endsWith('/me') ? identity : _sessionJson(),
            ),
          );
          final store = _TokenStore();
          final repository = MunicipalAuthRepositoryImpl(
            api: MunicipalAuthApi(config: _config, transport: transport),
            tokenStore: store,
          );
          await expectLater(
            repository.authenticate(
              username: 'inspector',
              password: 'password',
            ),
            throwsA(
              isA<MunicipalAuthException>().having(
                (error) => error.failure.kind,
                'failure kind',
                MunicipalAuthFailureKind.forbidden,
              ),
            ),
          );
          expect(store.value, isNull);
          expect(
            transport.requests.last.uri.path,
            '/api/v1/municipal/auth/logout',
          );
        }
      },
    );

    test('restores a valid secure session by querying /me', () async {
      final transport = _Transport(
        (_) => MunicipalHttpResponse(200, _identityJson()),
      );
      final store = _TokenStore(_storedTokens());
      final repository = MunicipalAuthRepositoryImpl(
        api: MunicipalAuthApi(config: _config, transport: transport),
        tokenStore: store,
      );

      final session = await repository.restoreSession();

      expect(session?.identity.canInspect, isTrue);
      expect(transport.requests.single.uri.path, '/api/v1/municipal/auth/me');
    });

    test(
      'refreshes expired access, rotates tokens, and validates the new profile',
      () async {
        var meRequests = 0;
        final transport = _Transport((request) {
          if (request.uri.path.endsWith('/me')) {
            meRequests++;
            return meRequests == 1
                ? const MunicipalHttpResponse(401, '{}')
                : MunicipalHttpResponse(200, _identityJson());
          }
          if (request.uri.path.endsWith('/refresh')) {
            return MunicipalHttpResponse(
              200,
              _sessionJson(access: _nextAccess, refresh: _nextRefresh),
            );
          }
          return const MunicipalHttpResponse(204, '');
        });
        final store = _TokenStore(_storedTokens());
        final repository = MunicipalAuthRepositoryImpl(
          api: MunicipalAuthApi(config: _config, transport: transport),
          tokenStore: store,
        );

        final session = await repository.restoreSession();

        expect(session?.identity.canInspect, isTrue);
        expect(store.value?.accessToken, _nextAccess);
        expect(store.value?.refreshToken, _nextRefresh);
        expect(meRequests, 2);
      },
    );

    test('keeps a rotated refresh token if profile verification is temporarily offline', () async {
      var meRequests = 0;
      final transport = _Transport((request) {
        if (request.uri.path.endsWith('/me')) {
          meRequests++;
          if (meRequests == 1) return const MunicipalHttpResponse(401, '{}');
          if (meRequests == 2) throw const SocketException('temporary outage');
          return MunicipalHttpResponse(200, _identityJson());
        }
        if (request.uri.path.endsWith('/refresh')) {
          return MunicipalHttpResponse(
            200,
            _sessionJson(access: _nextAccess, refresh: _nextRefresh),
          );
        }
        return const MunicipalHttpResponse(204, '');
      });
      final store = _TokenStore(_storedTokens());
      final repository = MunicipalAuthRepositoryImpl(
        api: MunicipalAuthApi(config: _config, transport: transport),
        tokenStore: store,
      );

      await expectLater(
        repository.restoreSession(),
        throwsA(
          isA<MunicipalAuthException>().having(
            (error) => error.failure.kind,
            'failure kind',
            MunicipalAuthFailureKind.unavailable,
          ),
        ),
      );
      expect(store.value?.refreshToken, _nextRefresh);

      final recovered = await repository.restoreSession();
      expect(recovered?.identity.canInspect, isTrue);
      expect(meRequests, 3);
    });

    test(
      'coalesces concurrent refresh requests and clears a revoked session',
      () async {
        final refreshStarted = Completer<void>();
        final releaseRefresh = Completer<void>();
        var meRequests = 0;
        var refreshRequests = 0;
        final transport = _Transport((request) async {
          if (request.uri.path.endsWith('/me')) {
            meRequests++;
            return meRequests <= 2
                ? const MunicipalHttpResponse(401, '{}')
                : MunicipalHttpResponse(200, _identityJson());
          }
          if (request.uri.path.endsWith('/refresh')) {
            refreshRequests++;
            refreshStarted.complete();
            await releaseRefresh.future;
            return MunicipalHttpResponse(
              200,
              _sessionJson(access: _nextAccess, refresh: _nextRefresh),
            );
          }
          return const MunicipalHttpResponse(204, '');
        });
        final store = _TokenStore(_storedTokens());
        final repository = MunicipalAuthRepositoryImpl(
          api: MunicipalAuthApi(config: _config, transport: transport),
          tokenStore: store,
        );

        final first = repository.restoreSession();
        await Future<void>.delayed(Duration.zero);
        final second = repository.restoreSession();
        await refreshStarted.future;
        releaseRefresh.complete();
        await Future.wait([first, second]);
        expect(refreshRequests, 1);
        expect(store.value?.refreshToken, _nextRefresh);

        final revokedTransport = _Transport(
          (request) => const MunicipalHttpResponse(401, '{}'),
        );
        final revokedStore = _TokenStore(_storedTokens());
        final revokedRepository = MunicipalAuthRepositoryImpl(
          api: MunicipalAuthApi(config: _config, transport: revokedTransport),
          tokenStore: revokedStore,
        );
        await expectLater(
          revokedRepository.restoreSession(),
          throwsA(isA<MunicipalAuthException>()),
        );
        expect(revokedStore.value, isNull);
      },
    );

    test(
      'logout calls backend and always clears local secure tokens',
      () async {
        final transport = _Transport(
          (_) => const MunicipalHttpResponse(503, '{}'),
        );
        final store = _TokenStore(_storedTokens());
        final repository = MunicipalAuthRepositoryImpl(
          api: MunicipalAuthApi(config: _config, transport: transport),
          tokenStore: store,
        );

        await repository.signOut();

        expect(
          transport.requests.single.uri.path,
          '/api/v1/municipal/auth/logout',
        );
        expect(jsonDecode(transport.requests.single.body!), {
          'refreshToken': _refresh,
        });
        expect(store.value, isNull);
        expect(store.clears, 1);
      },
    );
  });
}
