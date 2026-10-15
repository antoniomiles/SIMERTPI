import 'municipal_session.dart';

/// App-domain port for municipal authentication and secure session lifecycle.
abstract interface class MunicipalAuthRepository {
  Future<MunicipalSession> authenticate({
    required String username,
    required String password,
  });

  Future<void> signOut();
  Future<MunicipalSession?> restoreSession();
  Future<MunicipalSession> renewSession();
}
