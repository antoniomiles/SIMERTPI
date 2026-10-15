import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/app/municipal_app.dart';
import 'package:simertpi_municipal_app/core/config/municipal_app_config.dart';
import 'package:simertpi_municipal_app/features/auth/domain/municipal_auth_repository.dart';
import 'package:simertpi_municipal_app/features/auth/domain/municipal_session.dart';

class _SignedOutRepository implements MunicipalAuthRepository {
  @override
  Future<MunicipalSession> authenticate({
    required String username,
    required String password,
  }) => throw StateError('Should not authenticate in bootstrap test');

  @override
  Future<MunicipalSession?> restoreSession() async => null;

  @override
  Future<void> signOut() async {}

  @override
  Future<MunicipalSession> renewSession() async =>
      throw StateError('Should not renew a signed-out session');
}

void main() {
  testWidgets('starts at login without simulating a municipal session', (
    tester,
  ) async {
    const config = MunicipalAppConfig(
      environment: MunicipalEnvironment.development,
      apiBaseUrl: '',
    );

    await tester.pumpWidget(
      SimertpiMunicipalApp(
        config: config,
        authRepository: _SignedOutRepository(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('SIMERTPI Municipal'), findsOneWidget);
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(find.textContaining('Backend sin configurar'), findsOneWidget);
    expect(find.text('Turno activo'), findsNothing);
    expect(find.text('Sesión municipal activa'), findsNothing);
  });
}
