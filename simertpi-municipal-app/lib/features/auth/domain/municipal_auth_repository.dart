import 'municipal_session.dart';

/// App-domain port for a future municipal authentication adapter.
/// No network implementation exists until the backend contract is approved.
abstract interface class MunicipalAuthRepository {
  Future<MunicipalSession> authenticate({
    required String username,
    required String password,
  });

  Future<void> signOut();
  Future<MunicipalSession?> restoreSession();
}
