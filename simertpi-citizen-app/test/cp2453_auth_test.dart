import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/features/auth/data/device_credential.dart';
import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';
import 'package:simertpi_citizen_app/features/auth/data/device_security.dart';
import 'package:simertpi_citizen_app/features/auth/presentation/login_page.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/auth/state/inactivity_lock.dart';
import 'package:simertpi_citizen_app/features/welcome/welcome_page.dart';

import 'cp2452_security_test.dart' show Vault;
import 'support/auth_test_support.dart';

class SigningFake implements SigningDevice {
  bool invited = false;
  int signs = 0, prepares = 0, removes = 0;
  Object? error;
  final saved = <DeviceBinding>[];
  @override
  Future<List<DeviceBinding>> bindings() async => List.of(saved);
  @override
  Future<bool> invitationPresented() async => invited;
  @override
  Future<void> markInvitationPresented() async {
    invited = true;
  }

  @override
  Future<Map<String, String>> prepare(String owner) async {
    prepares++;
    return {'deviceId': 'device', 'publicKey': 'key'};
  }

  @override
  Future<String> sign(String owner, String payload) async {
    signs++;
    if (error != null) throw error!;
    return 'signature';
  }

  @override
  Future<void> commitBinding(DeviceBinding binding) async {
    saved.add(binding);
  }

  @override
  Future<void> removeBinding(String owner) async {
    removes++;
    saved.removeWhere((b) => b.owner == owner);
  }
}

class CredentialFake implements CredentialGateway {
  Completer<MobileSession>? pendingAuthentication;
  int registrations = 0, authentications = 0, revocations = 0, unlocks = 0;
  String owner = 'fixture-citizen';
  Object? error;
  bool mismatched = false, expired = false;
  Map<String, dynamic> _challenge(String id, String purpose) => {
    'credentialId': id,
    'challengeId': 'challenge',
    'payload':
        'SIMERTPI-DEVICE-V1\n$purpose\n${mismatched ? 'other' : owner}\n$id\nchallenge\nnonce',
    'expiresAt': DateTime.now()
        .toUtc()
        .add(Duration(minutes: expired ? -1 : 2))
        .toIso8601String(),
  };
  @override
  Future<Map<String, dynamic>> registration(
    String access,
    Map<String, String> key,
  ) async {
    if (error != null) throw error!;
    return _challenge('credential', 'REGISTER');
  }

  @override
  Future<void> register(String access, Map<String, dynamic> proof) async {
    registrations++;
    if (error != null) throw error!;
  }

  @override
  Future<Map<String, dynamic>> challenge(
    String credential, {
    String? access,
  }) async {
    if (error != null) throw error!;
    return _challenge(credential, access == null ? 'LOGIN' : 'UNLOCK');
  }

  @override
  Future<MobileSession> authenticate(Map<String, dynamic> proof) async {
    authentications++;
    if (pendingAuthentication != null) {
      return await pendingAuthentication!.future;
    }
    if (error != null) throw error!;
    final session = fixtureSession(access: 'D');
    return MobileSession(
      userId: session.userId,
      accessToken: session.accessToken,
      refreshToken: 'S' * 43,
      accessExpiresAt: session.accessExpiresAt,
      refreshExpiresAt: session.refreshExpiresAt,
    );
  }

  @override
  Future<void> unlock(String access, Map<String, dynamic> proof) async {
    unlocks++;
    if (error != null) throw error!;
  }

  @override
  Future<void> revoke(String access, String credential) async {
    revocations++;
    if (error != null) throw error!;
  }
}

class Fixture {
  final signing = SigningFake(),
      credentials = CredentialFake(),
      gateway = FakeAuthGateway(),
      store = MemorySessionStore(),
      vault = Vault(),
      lock = InactivityLock();
  late final auth = AuthController(
    gateway,
    store: store,
    deviceSecurity: vault,
    signingDevice: signing,
    credentialGateway: credentials,
    inactivity: lock,
  );
  Future<void> login() async {
    expect(await auth.login('citizen', 'password'), true);
    auth.completeLoginPresentation();
  }

  void dispose() {
    auth.dispose();
    lock.dispose();
  }
}

void main() {
  group('CP24.5.3 device credentials and installation invitation', () {
    late Fixture f;
    setUp(() => f = Fixture());
    tearDown(() => f.dispose());
    test(
      'double biometric submission creates only one backend session',
      () async {
        await f.login();
        await f.auth.enableBiometrics();
        final binding = f.signing.saved.single;
        await f.auth.logout();
        final outcomes = await Future.wait([
          f.auth.loginBiometrics(binding),
          f.auth.loginBiometrics(binding),
        ]);
        expect(outcomes, [true, false]);
        expect(f.credentials.authentications, 1);
      },
    );
    test('late biometric response after logout/password login cannot overwrite new tokens', () async {
      await f.login();
      await f.auth.enableBiometrics();
      final binding = f.signing.saved.single;
      await f.auth.logout();
      final pending = Completer<MobileSession>();
      f.credentials.pendingAuthentication = pending;
      final old = f.auth.loginBiometrics(binding);
      while (f.credentials.authentications == 0) {
        await Future<void>.delayed(Duration.zero);
      }
      await f.auth.logout();
      await f.login();
      final access = f.store.session!.accessToken;
      pending.complete(fixtureSession(access: 'D'));
      expect(await old, false);
      expect(f.store.session!.accessToken, access);
      expect(f.auth.isAuthenticated, true);
      expect(f.gateway.logouts, 2);
    });
    test('first password login eligible; presentation persisted independent of account/logout', () async {
      expect(await f.auth.shouldInviteBiometrics(), false);
      await f.login();
      expect(await f.auth.shouldInviteBiometrics(), true);
      await f.auth.markBiometricInvitation();
      await f.auth.declineBiometrics();
      await f.auth.logout();
      await f.login();
      expect(await f.auth.shouldInviteBiometrics(), false);
      expect(f.signing.invited, true);
      final restarted = AuthController(
        f.gateway,
        store: f.store,
        deviceSecurity: f.vault,
        signingDevice: f.signing,
        credentialGateway: f.credentials,
      );
      await restarted.restore();
      expect(await restarted.shouldInviteBiometrics(), false);
      restarted.dispose();
    });
    test('fresh installation/device resets only device marker and has no credential', () async {
      await f.login();
      await f.auth.markBiometricInvitation();
      final newPhone = Fixture();
      await newPhone.login();
      expect(await newPhone.auth.shouldInviteBiometrics(), true);
      expect(await newPhone.auth.biometricBindings(), isEmpty);
      newPhone.dispose();
    });
    test('activation verifies account, Android signature and registration before enabling', () async {
      await f.login();
      expect(await f.auth.enableBiometrics(), true);
      expect(f.signing.signs, 1);
      expect(f.credentials.registrations, 1);
      expect(f.auth.biometricStatus, BiometricStatus.enabled);
      expect(f.signing.saved.single.owner, f.auth.userId);
      expect(f.signing.invited, true);
    });
    test('logout clears session tokens while credential survives; biometric emits fresh tokens', () async {
      await f.login();
      await f.auth.enableBiometrics();
      final binding = f.signing.saved.single;
      final old = f.store.session!.refreshToken;
      await f.auth.logout();
      expect(f.store.session, isNull);
      expect(f.auth.isAuthenticated, false);
      expect(f.gateway.logouts, 1);
      expect(f.signing.saved, hasLength(1));
      expect(await f.auth.loginBiometrics(binding), true);
      expect(f.credentials.authentications, 1);
      expect(f.store.session!.refreshToken, isNot(old));
      expect(f.gateway.calls, 1);
    });
    test(
      'locked biometric validates existing session without creating new one',
      () async {
        await f.login();
        await f.auth.enableBiometrics();
        f.lock.lock();
        expect(await f.auth.unlockBiometrics(), true);
        expect(f.credentials.unlocks, 1);
        expect(f.credentials.authentications, 0);
        expect(f.gateway.calls, 1);
        expect(f.auth.isLocked, false);
      },
    );
    test('disable revokes remote before deleting local and never resets invitation', () async {
      await f.login();
      await f.auth.enableBiometrics();
      await f.auth.disableBiometrics();
      expect(f.credentials.revocations, 1);
      expect(f.signing.removes, 1);
      expect(f.auth.isAuthenticated, true);
      expect(f.signing.invited, true);
      expect(await f.auth.shouldInviteBiometrics(), false);
      expect(await f.auth.enableBiometrics(), true);
      expect(f.signing.prepares, 2);
    });
    test(
      'failed remote revocation preserves material and reports truthful state',
      () async {
        await f.login();
        await f.auth.enableBiometrics();
        f.credentials.error = const AppFailure(FailureKind.unavailable);
        await f.auth.disableBiometrics();
        expect(f.signing.saved, hasLength(1));
        expect(f.signing.removes, 0);
        expect(f.auth.message, contains('No pudimos desactivar'));
      },
    );
    for (final failure in [
      const AppFailure(FailureKind.unauthorized),
      const AppFailure(FailureKind.unavailable),
      const AppFailure(FailureKind.offline),
      const AppFailure(FailureKind.timeout),
    ]) {
      test('backend $failure cannot fabricate biometric login', () async {
        await f.login();
        await f.auth.enableBiometrics();
        final binding = f.signing.saved.single;
        await f.auth.logout();
        f.credentials.error = failure;
        expect(await f.auth.loginBiometrics(binding), false);
        expect(f.auth.isAuthenticated, false);
        expect(f.store.session, isNull);
        expect(f.auth.busy, false);
      });
    }
    for (final code in [
      'CANCELLED',
      'NOT_RECOGNIZED',
      'LOCKOUT',
      'LOCKOUT_PERMANENT',
    ]) {
      test('native $code does not create backend session', () async {
        await f.login();
        await f.auth.enableBiometrics();
        final binding = f.signing.saved.single;
        await f.auth.logout();
        f.signing.error = PlatformException(code: code);
        expect(await f.auth.loginBiometrics(binding), false);
        expect(f.credentials.authentications, 0);
        expect(f.auth.isAuthenticated, false);
      });
    }
    for (final invalid in ['owner', 'expired']) {
      test('invalid challenge $invalid is rejected before signing', () async {
        await f.login();
        await f.auth.enableBiometrics();
        final binding = f.signing.saved.single;
        await f.auth.logout();
        f.signing.signs = 0;
        f.credentials.mismatched = invalid == 'owner';
        f.credentials.expired = invalid == 'expired';
        expect(await f.auth.loginBiometrics(binding), false);
        expect(f.signing.signs, 0);
        expect(f.credentials.authentications, 0);
      });
    }
    test('unknown/cross-account binding cannot authenticate', () async {
      expect(
        await f.auth.loginBiometrics(
          const DeviceBinding('other', 'other', 'unknown', 'device'),
        ),
        false,
      );
      expect(f.credentials.authentications, 0);
    });
    test('incompatible device does not offer automatic invitation', () async {
      f.vault.compatible = false;
      await f.login();
      expect(await f.auth.shouldInviteBiometrics(), false);
    });
  });

  group('CP24.5.3 original screens', () {
    testWidgets(
      'manual setup during lock stays on protected navigator and verifies same account',
      (tester) async {
        final f = Fixture();
        await f.login();
        await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.home));
        await tester.pumpAndSettle();
        f.lock.lock();
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('Ingresar con huella'));
        await tester.tap(find.text('Ingresar con huella'));
        await tester.pumpAndSettle();
        expect(find.text('Activar ingreso biométrico'), findsOneWidget);
        await tester.tap(find.text('Configurar'));
        await tester.pumpAndSettle();
        expect(
          tester
              .widget<TextField>(find.byType(TextField).first)
              .controller!
              .text,
          'citizen',
        );
        await tester.enterText(find.byType(TextFormField).last, 'password');
        await tester.ensureVisible(find.text('INGRESAR'));
        await tester.tap(find.text('INGRESAR'));
        await tester.pumpAndSettle();
        expect(f.auth.isLocked, false);
        expect(f.credentials.registrations, 1);
        expect(f.signing.saved.single.owner, f.auth.userId);
        expect(find.text('Activa el ingreso biométrico'), findsNothing);
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      },
    );
    testWidgets(
      'create account during lock requires visible confirmation then clears private navigation',
      (tester) async {
        final f = Fixture();
        await f.login();
        await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.home));
        await tester.pumpAndSettle();
        f.lock.lock();
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('Crear cuenta'));
        await tester.tap(find.text('Crear cuenta'));
        await tester.pumpAndSettle();
        expect(
          find.text('Para crear otra cuenta debes cerrar la sesión actual.'),
          findsOneWidget,
        );
        expect(f.auth.isLocked, true);
        await tester.tap(find.widgetWithText(FilledButton, 'Cerrar sesión'));
        await tester.pumpAndSettle();
        expect(f.auth.isAuthenticated, false);
        expect(find.byType(WelcomePage), findsOneWidget);
        await tester.ensureVisible(find.text('Crear cuenta'));
        await tester.tap(find.text('Crear cuenta'));
        await tester.pumpAndSettle();
        expect(find.text('Nombres'), findsOneWidget);
        expect(f.gateway.registrations, 0);
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      },
    );
    testWidgets(
      'welcome buttons functional; manual decline creates no session or installation marker',
      (tester) async {
        final f = Fixture();
        await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.welcome));
        await tester.pumpAndSettle();
        expect(find.byType(WelcomePage), findsOneWidget);
        expect(find.text('Próximamente'), findsNothing);
        expect(find.byType(LoginPage), findsNothing);
        await tester.ensureVisible(find.text('Ingresar con huella'));
        await tester.tap(find.text('Ingresar con huella'));
        await tester.pumpAndSettle();
        expect(find.text('Activar ingreso biométrico'), findsOneWidget);
        await tester.tap(find.text('Ahora no'));
        await tester.pumpAndSettle();
        expect(f.auth.isAuthenticated, false);
        expect(f.signing.invited, false);
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      },
    );
    testWidgets(
      'manual setup opens existing credentials and does not duplicate fingerprint button',
      (tester) async {
        final f = Fixture();
        await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.welcome));
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('Ingresar con huella'));
        await tester.tap(find.text('Ingresar con huella'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Configurar'));
        await tester.pumpAndSettle();
        expect(find.byType(LoginPage), findsOneWidget);
        expect(find.text('Ingresar con huella'), findsNothing);
        await tester.enterText(find.byType(TextFormField).first, 'citizen');
        await tester.enterText(find.byType(TextFormField).last, 'password');
        await tester.tap(find.text('INGRESAR'));
        await tester.pumpAndSettle();
        expect(f.credentials.registrations, 1);
        expect(find.text('Activa el ingreso biométrico'), findsNothing);
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      },
    );
    testWidgets('automatic invitation shown once even after decline/logout', (
      tester,
    ) async {
      final f = Fixture();
      await tester.pumpWidget(authTestApp(f.auth));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).first, 'citizen');
      await tester.enterText(find.byType(TextFormField).last, 'password');
      await tester.tap(find.text('INGRESAR'));
      await tester.pumpAndSettle();
      expect(find.text('Activa el ingreso biométrico'), findsOneWidget);
      expect(f.signing.invited, true);
      await tester.tap(find.text('Ahora no'));
      await tester.pumpAndSettle();
      await f.auth.logout();
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Usuario y contraseña'));
      await tester.tap(find.text('Usuario y contraseña'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).first, 'citizen');
      await tester.enterText(find.byType(TextFormField).last, 'password');
      await tester.tap(find.text('INGRESAR'));
      await tester.pumpAndSettle();
      expect(find.text('Activa el ingreso biométrico'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      f.dispose();
    });
    testWidgets(
      'five minute lock uses original welcome, password is prefilled, no duplicate biometric',
      (tester) async {
        final f = Fixture();
        await f.login();
        await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.home));
        await tester.pumpAndSettle();
        f.lock.lock();
        await tester.pumpAndSettle();
        expect(find.byType(WelcomePage), findsOneWidget);
        expect(find.byType(LoginPage), findsNothing);
        expect(await tester.binding.handlePopRoute(), true);
        await tester.ensureVisible(find.text('Usuario y contraseña'));
        await tester.tap(find.text('Usuario y contraseña'));
        await tester.pumpAndSettle();
        expect(
          tester
              .widget<TextField>(find.byType(TextField).first)
              .controller!
              .text,
          'citizen',
        );
        expect(
          tester.widget<TextField>(find.byType(TextField).first).enabled,
          false,
        );
        expect(find.text('Ingresar con huella'), findsNothing);
        await tester.enterText(find.byType(TextFormField).last, 'password');
        await tester.tap(find.text('INGRESAR'));
        await tester.pumpAndSettle();
        expect(f.auth.isLocked, false);
        expect(find.text('Activa el ingreso biométrico'), findsNothing);
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      },
    );
    testWidgets('multiple authorized accounts require explicit selection', (
      tester,
    ) async {
      final f = Fixture();
      f.signing.saved.addAll([
        const DeviceBinding('a', 'Cuenta A', 'ca', 'd'),
        const DeviceBinding('b', 'Cuenta B', 'cb', 'd'),
      ]);
      await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.welcome));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Ingresar con huella'));
      await tester.tap(find.text('Ingresar con huella'));
      await tester.pumpAndSettle();
      expect(find.text('Selecciona tu cuenta'), findsOneWidget);
      expect(find.text('Cuenta A'), findsOneWidget);
      expect(find.text('Cuenta B'), findsOneWidget);
      expect(f.credentials.authentications, 0);
      await tester.pumpWidget(const SizedBox());
      f.dispose();
    });
    testWidgets(
      'only principal blue changes and action group moves exactly two logical pixels',
      (tester) async {
        final f = Fixture();
        await tester.pumpWidget(authTestApp(f.auth, route: AppRoute.welcome));
        await tester.pumpAndSettle();
        expect(AppColors.brandNightBlue, const Color(0xFF020562));
        expect(AppColors.brandGreen, const Color(0xFF0B6B3A));
        final transform = tester.widget<Transform>(
          find.byKey(const ValueKey('welcome-actions-offset')),
        );
        expect(transform.transform.getTranslation().y, -2);
        expect(transform.transform.getTranslation().x, 0);
        expect(
          find.byKey(const ValueKey('welcome-login-action')),
          findsOneWidget,
        );
        expect(
          find.byKey(const ValueKey('welcome-biometric-action')),
          findsOneWidget,
        );
        expect(
          tester.getSize(find.byKey(const ValueKey('welcome-login-action'))),
          tester.getSize(
            find.byKey(const ValueKey('welcome-biometric-action')),
          ),
        );
        await tester.pumpWidget(const SizedBox());
        f.dispose();
      },
    );
  });
}
