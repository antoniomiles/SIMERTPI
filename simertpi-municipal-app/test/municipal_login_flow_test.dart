import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/app/municipal_app.dart';
import 'package:simertpi_municipal_app/core/config/municipal_app_config.dart';
import 'package:simertpi_municipal_app/core/errors/municipal_auth_failure.dart';
import 'package:simertpi_municipal_app/core/security/municipal_identity.dart';
import 'package:simertpi_municipal_app/features/auth/domain/municipal_auth_repository.dart';
import 'package:simertpi_municipal_app/features/auth/domain/municipal_session.dart';

const _config = MunicipalAppConfig(
  environment: MunicipalEnvironment.development,
  apiBaseUrl: 'https://municipal.example/api/v1',
);

MunicipalSession _session() {
  final now = DateTime.now().toUtc();
  return MunicipalSession(
    identity: MunicipalIdentity(
      id: 'inspector-id',
      username: 'inspector.demo',
      roles: {MunicipalRole.inspector},
      enabled: true,
    ),
    authenticatedAt: now,
    accessExpiresAt: now.add(const Duration(minutes: 15)),
  );
}

class _Repository implements MunicipalAuthRepository {
  MunicipalSession? initialSession;
  MunicipalAuthFailureKind? loginFailure;
  final restoreGate = Completer<MunicipalSession?>();
  bool delayRestore = false;
  int loginCalls = 0;
  int signOutCalls = 0;
  int renewCalls = 0;
  String? lastUsername;
  String? lastPassword;

  @override
  Future<MunicipalSession> authenticate({
    required String username,
    required String password,
  }) async {
    loginCalls++;
    lastUsername = username;
    lastPassword = password;
    if (loginFailure case final failure?) {
      throw MunicipalAuthException(MunicipalAuthFailure(failure));
    }
    return _session();
  }

  @override
  Future<MunicipalSession?> restoreSession() =>
      delayRestore ? restoreGate.future : Future.value(initialSession);

  @override
  Future<void> signOut() async {
    signOutCalls++;
    initialSession = null;
  }

  @override
  Future<MunicipalSession> renewSession() async {
    renewCalls++;
    return _session();
  }
}

void main() {
  testWidgets('shows verification state before restoring a saved session', (
    tester,
  ) async {
    final repository = _Repository()..delayRestore = true;
    await tester.pumpWidget(
      SimertpiMunicipalApp(config: _config, authRepository: repository),
    );
    await tester.pump();

    expect(find.text('Verificando sesión…'), findsOneWidget);
    expect(find.text('Iniciar sesión'), findsNothing);
    expect(find.text('Sesión municipal activa'), findsNothing);

    repository.restoreGate.complete(null);
    await tester.pumpAndSettle();
    expect(find.text('Iniciar sesión'), findsOneWidget);
  });

  testWidgets('restores a verified session and renews it before expiry', (
    tester,
  ) async {
    final soonExpiring = _session();
    final repository = _Repository()
      ..initialSession = MunicipalSession(
        identity: soonExpiring.identity,
        authenticatedAt: soonExpiring.authenticatedAt,
        accessExpiresAt: DateTime.now().toUtc().add(const Duration(seconds: 5)),
      );
    await tester.pumpWidget(
      SimertpiMunicipalApp(config: _config, authRepository: repository),
    );
    await tester.pumpAndSettle();

    expect(find.text('Inspector: inspector.demo'), findsOneWidget);
    expect(repository.renewCalls, 1);
  });

  testWidgets(
    'validates credentials and opens the protected home only after login',
    (tester) async {
      final repository = _Repository();
      await tester.pumpWidget(
        SimertpiMunicipalApp(config: _config, authRepository: repository),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('municipal-login-submit')));
      await tester.pumpAndSettle();
      expect(find.text('Ingresa tu usuario.'), findsOneWidget);
      expect(repository.loginCalls, 0);

      await tester.enterText(
        find.byKey(const Key('municipal-username')),
        'inspector.demo',
      );
      await tester.enterText(
        find.byKey(const Key('municipal-password')),
        'secret-on-wire-only',
      );
      await tester.tap(find.byKey(const Key('municipal-login-submit')));
      await tester.pumpAndSettle();

      expect(repository.lastUsername, 'inspector.demo');
      expect(repository.lastPassword, 'secret-on-wire-only');
      expect(find.text('Sesión municipal activa'), findsOneWidget);
      expect(find.text('Inspector: inspector.demo'), findsOneWidget);
      expect(find.text('Turno activo'), findsNothing);

      await tester.tap(find.byKey(const Key('municipal-logout')));
      await tester.pumpAndSettle();
      expect(repository.signOutCalls, 1);
      expect(find.text('Iniciar sesión'), findsOneWidget);
      expect(find.text('Sesión municipal activa'), findsNothing);
    },
  );

  testWidgets('shows authorization failures and allows another login attempt', (
    tester,
  ) async {
    final repository = _Repository()
      ..loginFailure = MunicipalAuthFailureKind.forbidden;
    await tester.pumpWidget(
      SimertpiMunicipalApp(config: _config, authRepository: repository),
    );
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('municipal-username')),
      'citizen',
    );
    await tester.enterText(
      find.byKey(const Key('municipal-password')),
      'wrong-role',
    );
    await tester.tap(find.byKey(const Key('municipal-login-submit')));
    await tester.pumpAndSettle();

    expect(
      find.text('La cuenta no tiene autorización para esta aplicación.'),
      findsOneWidget,
    );
    expect(find.text('Sesión municipal activa'), findsNothing);
    expect(find.byKey(const Key('municipal-login-submit')), findsOneWidget);
  });

  testWidgets('does not submit when API environment is missing', (
    tester,
  ) async {
    final repository = _Repository();
    const unconfigured = MunicipalAppConfig(
      environment: MunicipalEnvironment.development,
      apiBaseUrl: '',
    );
    await tester.pumpWidget(
      SimertpiMunicipalApp(config: unconfigured, authRepository: repository),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const Key('municipal-backend-unconfigured')),
      findsOneWidget,
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('municipal-login-submit')))
          .onPressed,
      isNull,
    );
  });
}
