import '../../../core/errors/municipal_auth_failure.dart';
import '../../../core/security/municipal_identity.dart';
import '../domain/municipal_auth_repository.dart';
import '../domain/municipal_session.dart';
import 'municipal_auth_api.dart';
import 'municipal_token_store.dart';

typedef _VerifiedRefresh = ({
  MunicipalStoredTokens tokens,
  MunicipalIdentity identity,
});

class MunicipalAuthRepositoryImpl implements MunicipalAuthRepository {
  MunicipalAuthRepositoryImpl({
    required this._api,
    required this._tokenStore,
    DateTime Function()? clock,
  }) : _clock = clock ?? DateTime.now;

  final MunicipalAuthApi _api;
  final MunicipalTokenStore _tokenStore;
  final DateTime Function() _clock;
  Future<_VerifiedRefresh>? _refreshInFlight;
  int _sessionGeneration = 0;

  @override
  Future<MunicipalSession> authenticate({
    required String username,
    required String password,
  }) async {
    if (username.trim().isEmpty || password.isEmpty) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unauthenticated),
      );
    }
    final tokenResponse = await _api.login(username.trim(), password);
    late final MunicipalIdentity identity;
    try {
      identity = _verifiedIdentity(await _api.me(tokenResponse.accessToken));
      if (identity.id != tokenResponse.userId) _invalidIdentity();
    } catch (_) {
      try {
        await _api.logout(tokenResponse.refreshToken);
      } catch (_) {
        // The unverified session is never persisted locally.
      }
      rethrow;
    }
    final tokens = _tokens(tokenResponse);
    try {
      await _tokenStore.write(tokens);
    } catch (_) {
      try {
        await _api.logout(tokens.refreshToken);
      } catch (_) {
        // Local storage failure must never leave credentials in presentation state.
      }
      rethrow;
    }
    _sessionGeneration++;
    return _session(identity, tokens);
  }

  @override
  Future<MunicipalSession?> restoreSession() async {
    final stored = await _tokenStore.read();
    if (stored == null) return null;
    final generation = _sessionGeneration;
    try {
      try {
        final identity = _verifiedIdentity(await _api.me(stored.accessToken));
        if (generation != _sessionGeneration) _invalidIdentity();
        return _session(identity, stored);
      } on MunicipalAuthException catch (failure) {
        if (failure.failure.kind == MunicipalAuthFailureKind.unauthenticated) {
          final renewed = await _refresh(stored, generation);
          return _session(renewed.identity, renewed.tokens);
        }
        if (failure.failure.kind == MunicipalAuthFailureKind.forbidden) {
          await _clearAfterInvalidSession();
        }
        rethrow;
      }
    } on MunicipalAuthException catch (failure) {
      if (generation == _sessionGeneration &&
          (failure.failure.kind == MunicipalAuthFailureKind.unauthenticated ||
              failure.failure.kind == MunicipalAuthFailureKind.forbidden ||
              failure.failure.kind ==
                  MunicipalAuthFailureKind.invalidResponse)) {
        await _clearAfterInvalidSession();
      }
      rethrow;
    }
  }

  @override
  Future<MunicipalSession> renewSession() async {
    final stored = await _tokenStore.read();
    if (stored == null) {
      throw const MunicipalAuthException(
        MunicipalAuthFailure(MunicipalAuthFailureKind.unauthenticated),
      );
    }
    final renewed = await _refresh(stored, _sessionGeneration);
    return _session(renewed.identity, renewed.tokens);
  }

  @override
  Future<void> signOut() async {
    _sessionGeneration++;
    MunicipalStoredTokens? tokens;
    try {
      tokens = await _tokenStore.read();
    } catch (_) {
      // Continue with local deletion even when a stored value is corrupt.
    }
    try {
      if (tokens != null) await _api.logout(tokens.refreshToken);
    } catch (_) {
      // Local logout must still complete when the backend cannot be reached.
    }
    await _tokenStore.clear();
  }

  Future<_VerifiedRefresh> _refresh(
    MunicipalStoredTokens current,
    int generation,
  ) {
    final running = _refreshInFlight;
    if (running != null) return running;
    final future = _refreshAndStore(current, generation);
    _refreshInFlight = future;
    return future.whenComplete(() {
      if (identical(_refreshInFlight, future)) _refreshInFlight = null;
    });
  }

  Future<_VerifiedRefresh> _refreshAndStore(
    MunicipalStoredTokens current,
    int generation,
  ) async {
    try {
      final renewed = _tokens(await _api.refresh(current.refreshToken));
      if (generation != _sessionGeneration) {
        throw const MunicipalAuthException(
          MunicipalAuthFailure(MunicipalAuthFailureKind.unauthenticated),
        );
      }
      // The old refresh token is already consumed. Persist its rotation before
      // profile verification so a temporary /me outage can be retried safely.
      await _tokenStore.write(renewed);
      final identity = _verifiedIdentity(await _api.me(renewed.accessToken));
      return (tokens: renewed, identity: identity);
    } on MunicipalAuthException catch (failure) {
      if (generation == _sessionGeneration &&
          (failure.failure.kind == MunicipalAuthFailureKind.unauthenticated ||
              failure.failure.kind == MunicipalAuthFailureKind.forbidden ||
              failure.failure.kind == MunicipalAuthFailureKind.secureStorage)) {
        await _clearAfterInvalidSession();
      }
      rethrow;
    }
  }

  Future<void> _clearAfterInvalidSession() async {
    _sessionGeneration++;
    await _tokenStore.clear();
  }

  MunicipalIdentity _verifiedIdentity(MunicipalIdentityResponse response) {
    final roles = response.roles
        .map(MunicipalRole.fromAuthority)
        .whereType<MunicipalRole>()
        .toSet();
    final identity = MunicipalIdentity(
      id: response.userId,
      username: response.username,
      roles: roles,
      enabled: response.enabled,
    );
    if (!identity.canInspect) _invalidIdentity();
    return identity;
  }

  Never _invalidIdentity() => throw const MunicipalAuthException(
    MunicipalAuthFailure(MunicipalAuthFailureKind.forbidden),
  );

  MunicipalStoredTokens _tokens(MunicipalTokenResponse response) =>
      MunicipalStoredTokens(
        accessToken: response.accessToken,
        refreshToken: response.refreshToken,
        accessExpiresAt: response.accessExpiresAt,
        refreshExpiresAt: response.refreshExpiresAt,
      );

  MunicipalSession _session(
    MunicipalIdentity identity,
    MunicipalStoredTokens tokens,
  ) => MunicipalSession(
    identity: identity,
    authenticatedAt: _clock().toUtc(),
    accessExpiresAt: tokens.accessExpiresAt,
  );
}
