import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/app.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/widgets/app_layout.dart';
import 'package:simertpi_citizen_app/features/auth/data/device_security.dart';
import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';
import 'package:simertpi_citizen_app/features/auth/presentation/security_settings.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/auth/state/inactivity_lock.dart';
import 'package:simertpi_citizen_app/features/payments/presentation/payments_page.dart';
import 'package:simertpi_citizen_app/features/payments/state/payments_controller.dart';
import 'package:simertpi_citizen_app/features/vehicles/presentation/vehicles_page.dart';

import 'cp245_flow_test.dart' as flow;
import 'payments_test.dart' as pay;
import 'support/auth_test_support.dart';

class MemoryActivity implements ActivityStore {
  final times = <String, DateTime>{};
  @override
  Future<DateTime?> read(String owner) async => times[owner];
  @override
  Future<void> write(String owner, DateTime at) async {
    times[owner] = at;
  }
}

class Vault implements DeviceSecurity {
  bool compatible = true, protected = false;
  int enables = 0, decrypts = 0, removes = 0;
  Object? error;
  final declined = <String>{};
  final material = <String, MobileSession>{};
  final sessions = <String, String>{};
  @override
  Future<bool> verifySession(MobileSession session) async =>
      sessions[session.userId] == session.refreshToken;
  @override
  Future<void> invalidateSession(String owner) async {
    sessions.remove(owner);
    material.remove(owner);
  }

  @override
  Future<BiometricStatus> status(String owner) async => !compatible
      ? BiometricStatus.unavailable
      : material.containsKey(owner)
      ? BiometricStatus.enabled
      : BiometricStatus.disabled;
  @override
  Future<bool> shouldInvite(String owner) async =>
      compatible && !declined.contains(owner) && !material.containsKey(owner);
  @override
  Future<void> decline(String owner) async {
    declined.add(owner);
  }

  @override
  Future<void> enable(MobileSession session) async {
    enables++;
    if (error != null) throw error!;
    material[session.userId] = session;
  }

  @override
  Future<void> update(MobileSession session) async {
    sessions[session.userId] = session.refreshToken;
    if (material.containsKey(session.userId)) {
      material[session.userId] = session;
    }
  }

  @override
  Future<MobileSession> unlock(String owner) async {
    decrypts++;
    if (error != null) throw error!;
    return material[owner]!;
  }

  @override
  Future<void> disable(String owner) async {
    removes++;
    material.remove(owner);
  }

  @override
  Future<void> protect(bool locked) async {
    protected = locked;
  }
}

class OtherAccount extends FakeAuthGateway {
  @override
  Future<MobileSession> login(String username, String password) async {
    final current = await super.login(username, password);
    return MobileSession(
      userId: username == 'other' ? 'other-id' : current.userId,
      accessToken: current.accessToken,
      refreshToken: current.refreshToken,
      accessExpiresAt: current.accessExpiresAt,
      refreshExpiresAt: current.refreshExpiresAt,
    );
  }
}

class RefusingClearStore extends MemorySessionStore {
  bool refuse = false;
  @override
  Future<void> clear() async {
    if (refuse) throw StateError('secure storage unavailable');
    await super.clear();
  }
}

void main() {
  final instant = DateTime.utc(2026, 10, 10, 12);
  group('CP24.5.2 inactivity and restoration', () {
    late DateTime now;
    late MemoryActivity store;
    late InactivityLock lock;
    setUp(() {
      now = instant;
      store = MemoryActivity();
      lock = InactivityLock(clock: () => now, store: store);
    });
    tearDown(() => lock.dispose());
    test('4:59 open, 5:00 locked without deleting the session', () async {
      await lock.attach('owner', restored: false);
      now = instant.add(const Duration(minutes: 4, seconds: 59));
      lock.check();
      expect(lock.locked, false);
      now = instant.add(const Duration(minutes: 5));
      lock.check();
      expect(lock.locked, true);
    });
    test('human activity resets, automatic check/HTTP does not', () async {
      await lock.attach('owner', restored: false);
      now = instant.add(const Duration(minutes: 4));
      lock.activity();
      now = instant.add(const Duration(minutes: 8, seconds: 59));
      lock.check();
      expect(lock.locked, false);
      now = instant.add(const Duration(minutes: 9));
      lock.check();
      expect(lock.locked, true);
      lock.activity();
      expect(lock.locked, true);
    });
    for (final minutes in [2, 6]) {
      test(
        'background/screen off/suspend $minutes minutes uses stored deadline',
        () async {
          await lock.attach('owner', restored: false);
          await lock.flush();
          now = instant.add(Duration(minutes: minutes));
          await lock.resume();
          expect(lock.locked, minutes >= 5);
        },
      );
      test('process/restart $minutes minutes retains deadline', () async {
        await lock.attach('owner', restored: false);
        await lock.flush();
        now = instant.add(Duration(minutes: minutes));
        final restored = InactivityLock(clock: () => now, store: store);
        await restored.attach('owner', restored: true);
        expect(restored.locked, minutes >= 5);
        restored.dispose();
      });
    }
    test(
      'missing metadata/new owner and backwards clock fail closed',
      () async {
        await lock.attach('new-owner', restored: true);
        expect(lock.locked, true);
        lock.unlock();
        now = instant.subtract(const Duration(seconds: 1));
        lock.check();
        expect(lock.locked, true);
      },
    );
    test('queued writes use the actual interaction timestamp', () async {
      await lock.attach('owner', restored: false);
      now = instant.add(const Duration(minutes: 1));
      lock.activity();
      await lock.flush();
      expect(store.times['owner'], now);
      lock.detach();
      expect(lock.locked, false);
    });
  });

  group('CP24.5.2 session and rotating refresh', () {
    late FakeAuthGateway gateway;
    late MemorySessionStore store;
    late Vault vault;
    late InactivityLock lock;
    late AuthController auth;
    setUp(() async {
      gateway = FakeAuthGateway();
      store = MemorySessionStore();
      vault = Vault();
      lock = InactivityLock();
      auth = AuthController(
        gateway,
        store: store,
        inactivity: lock,
        deviceSecurity: vault,
      );
      await auth.login('citizen', 'password');
      auth.completeLoginPresentation();
    });
    tearDown(() {
      auth.dispose();
      lock.dispose();
    });
    test('successful refresh saves pair and same account', () async {
      expect(await auth.renew(), true);
      expect(gateway.refreshes, 1);
      expect(store.session!.accessToken, 'C' * 43);
      expect(store.session!.refreshBlocked, false);
      expect(auth.isLocked, false);
    });
    for (final kind in [
      FailureKind.offline,
      FailureKind.timeout,
      FailureKind.unavailable,
      FailureKind.unknown,
    ]) {
      test(
        '$kind preserves tokens, blocks blind retry across restart and requires password',
        () async {
          final previous = store.session!;
          gateway.failure = AppFailure(kind, outcomeUnknown: true);
          expect(await auth.renew(), false);
          expect(auth.isAuthenticated, true);
          expect(auth.isLocked, true);
          expect(store.session!.refreshToken, previous.refreshToken);
          expect(store.session!.refreshBlocked, true);
          expect(gateway.logouts, 0);
          expect(await auth.renew(), false);
          expect(gateway.refreshes, 1);
          final restored = AuthController(gateway, store: store);
          await restored.restore();
          expect(restored.isLocked, true);
          expect(await restored.renew(), false);
          expect(gateway.refreshes, 1);
          restored.dispose();
          gateway.failure = null;
          expect(await auth.unlockPassword('citizen', 'password'), true);
          expect(auth.isLocked, false);
          expect(store.session!.refreshBlocked, false);
        },
      );
    }
    test('definitive invalid/revoked refresh clears session and biometric material', () async {
      await auth.enableBiometrics();
      lock.lock();
      gateway.failure = const AppFailure(FailureKind.unauthorized);
      expect(await auth.unlockBiometrics(), false);
      expect(auth.isAuthenticated, false);
      expect(store.session, isNull);
      expect(vault.material, isEmpty);
      expect(await auth.unlockBiometrics(), false);
    });
    test(
      'two refreshes share one rotating transaction and journal before send',
      () async {
        gateway.pending = Completer<void>();
        final first = auth.renew(), second = auth.renew();
        await Future<void>.delayed(Duration.zero);
        expect(gateway.refreshes, 1);
        expect(store.session!.refreshBlocked, true);
        gateway.pending!.complete();
        expect(await first, true);
        expect(await second, true);
        expect(gateway.refreshes, 1);
      },
    );
    test('late refresh during logout cannot restore tokens', () async {
      gateway.pending = Completer<void>();
      final refresh = auth.renew();
      await Future<void>.delayed(Duration.zero);
      await auth.logout();
      gateway.pending!.complete();
      expect(await refresh, false);
      expect(store.session, isNull);
      expect(auth.isAuthenticated, false);
    });
    test('late refresh during new login cannot overwrite new pair', () async {
      final pending = Completer<void>();
      gateway.pending = pending;
      final refresh = auth.renew();
      await Future<void>.delayed(Duration.zero);
      gateway.pending = null;
      expect(await auth.login('citizen', 'new-password'), true);
      pending.complete();
      expect(await refresh, false);
      expect(store.session!.accessToken, 'B' * 43);
    });
    for (final unlocking in [false, true]) {
      test(
        'refresh cannot start during ${unlocking ? 'password unlock' : 'login'} and overwrite its tokens',
        () async {
          if (unlocking) lock.lock();
          gateway.pending = Completer<void>();
          final login = unlocking
              ? auth.unlockPassword('citizen', 'password')
              : auth.login('citizen', 'password');
          expect(await auth.renew(), false);
          expect(gateway.refreshes, 0);
          gateway.pending!.complete();
          expect(await login, true);
          expect(store.session!.accessToken, 'B' * 43);
        },
      );
    }
    test(
      'logout during password unlock allows new login and rejects late unlock',
      () async {
        lock.lock();
        final pending = Completer<void>();
        gateway.pending = pending;
        final unlock = auth.unlockPassword('citizen', 'password');
        await Future<void>.delayed(Duration.zero);
        await auth.logout();
        gateway.pending = null;
        expect(await auth.login('citizen', 'new-password'), true);
        pending.complete();
        expect(await unlock, false);
        expect(auth.busy, false);
        expect(auth.isAuthenticated, true);
      },
    );
    test(
      'late unauthorized response cannot invalidate a new session',
      () async {
        final old = (await auth.headers())['Authorization'];
        await auth.login('citizen', 'new-password');
        await auth.rejectAuthorization(old);
        expect(auth.isAuthenticated, true);
        expect(await auth.recoverUnauthorized(old), false);
        expect(gateway.refreshes, 0);
      },
    );
    test(
      'logout removes biometric authority and cannot be undone by fingerprint',
      () async {
        await auth.enableBiometrics();
        expect(vault.material, isNotEmpty);
        await auth.logout();
        expect(vault.material, isEmpty);
        expect(store.session, isNull);
        expect(await auth.unlockBiometrics(), false);
        expect(gateway.logouts, 1);
      },
    );
    test('logout tombstone prevents restoration even when secure token deletion fails', () async {
      final failing = RefusingClearStore();
      final closing = AuthController(
        gateway,
        store: failing,
        deviceSecurity: vault,
      );
      await closing.login('citizen', 'password');
      await closing.enableBiometrics();
      failing.refuse = true;
      await closing.logout();
      expect(failing.session, isNotNull);
      expect(closing.isAuthenticated, false);
      final restored = AuthController(
        gateway,
        store: failing,
        deviceSecurity: vault,
      );
      await restored.restore();
      expect(restored.isAuthenticated, false);
      expect(restored.phase, AuthPhase.signedOut);
      expect(await restored.unlockBiometrics(), false);
      expect(vault.material, isEmpty);
      closing.dispose();
      restored.dispose();
    });
    test('transferred tokens without this device proof require password, never biometric/refresh', () async {
      final freshVault = Vault();
      final restored = AuthController(
        gateway,
        store: store,
        deviceSecurity: freshVault,
      );
      await restored.restore();
      expect(restored.isAuthenticated, false);
      expect(await restored.unlockBiometrics(), false);
      expect(gateway.refreshes, 0);
      expect(await restored.login('citizen', 'password'), true);
      expect(await freshVault.verifySession(store.session!), true);
      restored.dispose();
    });
    test('expired access never supplies expired Authorization after unavailable refresh', () async {
      final expiredStore = MemorySessionStore()
        ..session = fixtureSession(
          accessExpiry: DateTime.now().subtract(const Duration(minutes: 1)),
        );
      final expired = AuthController(gateway, store: expiredStore);
      gateway.failure = const AppFailure(FailureKind.unavailable);
      await expired.restore();
      await expectLater(expired.headers(), throwsA(isA<AppFailure>()));
      expect(expired.isAuthenticated, true);
      expect(gateway.refreshes, 1);
      expired.dispose();
    });
    test(
      'password failure leaves locked account and its tokens intact',
      () async {
        lock.lock();
        final before = store.session;
        gateway.failure = const AppFailure(FailureKind.unauthorized);
        expect(await auth.unlockPassword('citizen', 'bad'), false);
        expect(auth.isLocked, true);
        expect(store.session, same(before));
      },
    );
    test('wrong account cannot unlock prior private navigation', () async {
      final other = AuthController(
        OtherAccount(),
        store: MemorySessionStore(),
        inactivity: lock,
      );
      await other.login('citizen', 'password');
      lock.lock();
      expect(await other.unlockPassword('other', 'password'), false);
      expect(other.userId, 'fixture-citizen');
      expect(other.isLocked, true);
      expect(other.message, contains('cuenta actual'));
      other.dispose();
    });
    test('biometric success with unavailable backend never unlocks', () async {
      await auth.enableBiometrics();
      lock.lock();
      gateway.failure = const AppFailure(FailureKind.offline);
      expect(await auth.unlockBiometrics(), false);
      expect(auth.isLocked, true);
      expect(auth.isAuthenticated, true);
    });
    test(
      'biometric copy belonging to another session is refused before refresh',
      () async {
        await auth.enableBiometrics();
        lock.lock();
        vault.material[auth.userId!] = MobileSession(
          userId: auth.userId!,
          accessToken: 'A' * 43,
          refreshToken: 'X' * 43,
          accessExpiresAt: fixtureSession().accessExpiresAt,
          refreshExpiresAt: fixtureSession().refreshExpiresAt,
        );
        final before = gateway.refreshes;
        expect(await auth.unlockBiometrics(), false);
        expect(auth.isLocked, true);
        expect(gateway.refreshes, before);
      },
    );
    for (final code in [
      'CANCELLED',
      'NOT_RECOGNIZED',
      'LOCKOUT',
      'LOCKOUT_PERMANENT',
      'UNAVAILABLE',
      'INVALIDATED',
    ]) {
      test('Android $code does not unlock or destroy session', () async {
        await auth.enableBiometrics();
        lock.lock();
        vault.error = PlatformException(code: code);
        expect(await auth.unlockBiometrics(), false);
        expect(auth.isLocked, true);
        expect(auth.isAuthenticated, true);
        expect(auth.message, isNot(contains('Exception')));
      });
    }
  });

  group('CP24.5.2 biometrics invitation and device scope', () {
    late AuthController auth;
    late Vault vault;
    setUp(() async {
      vault = Vault();
      auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
        deviceSecurity: vault,
      );
      await auth.login('citizen', 'password');
    });
    tearDown(() => auth.dispose());
    test(
      'first compatible login invites, decline remembered per owner/device',
      () async {
        expect(await auth.shouldInviteBiometrics(), true);
        await auth.declineBiometrics();
        expect(await auth.shouldInviteBiometrics(), false);
        expect(await vault.shouldInvite('other'), true);
        expect(await Vault().shouldInvite(auth.userId!), true);
      },
    );
    test(
      'activation verifies backend and saves only after Android success',
      () async {
        expect(await auth.enableBiometrics(), true);
        expect(vault.enables, 1);
        expect(auth.biometricStatus, BiometricStatus.enabled);
        expect(await auth.shouldInviteBiometrics(), false);
        expect((auth.gateway as FakeAuthGateway).refreshes, 1);
      },
    );
    test('disable deletes material, retains session and reactivation prompts again', () async {
      await auth.enableBiometrics();
      await auth.disableBiometrics();
      expect(vault.material, isEmpty);
      expect(auth.isAuthenticated, true);
      expect(auth.biometricStatus, BiometricStatus.disabled);
      expect(await auth.enableBiometrics(), true);
      expect(vault.enables, 2);
    });
    for (final code in ['CANCELLED', 'NOT_RECOGNIZED', 'UNAVAILABLE']) {
      test(
        'activation $code preserves password login and creates no material',
        () async {
          vault.error = PlatformException(code: code);
          expect(await auth.enableBiometrics(), false);
          expect(vault.material, isEmpty);
          expect(auth.isAuthenticated, true);
          expect(await auth.shouldInviteBiometrics(), true);
        },
      );
    }
    test('incompatible/not enrolled hardware never invites', () async {
      vault.compatible = false;
      await auth.refreshBiometricStatus();
      expect(auth.biometricStatus, BiometricStatus.unavailable);
      expect(await auth.shouldInviteBiometrics(), false);
    });
    test(
      'new phone/reinstallation has no authorized biometric session',
      () async {
        await auth.enableBiometrics();
        final fresh = Vault();
        expect(await fresh.status(auth.userId!), BiometricStatus.disabled);
        final reinstalled = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
          deviceSecurity: fresh,
        );
        await reinstalled.restore();
        expect(await reinstalled.unlockBiometrics(), false);
        reinstalled.dispose();
      },
    );
    test(
      'enrollment invalidation requires password and fresh activation',
      () async {
        await auth.enableBiometrics();
        vault.material.clear();
        await auth.refreshBiometricStatus();
        expect(auth.biometricStatus, BiometricStatus.disabled);
        expect(await auth.enableBiometrics(), true);
        expect(vault.enables, 2);
      },
    );
    for (final activate in [false, true]) {
      testWidgets(
        'post-login invitation ${activate ? 'activates' : 'declines'} once and continues to Home',
        (tester) async {
          auth.dispose();
          auth = AuthController(
            FakeAuthGateway(),
            store: MemorySessionStore(),
            deviceSecurity: vault,
          );
          await tester.pumpWidget(authTestApp(auth));
          await tester.enterText(find.byType(TextFormField).at(0), 'citizen');
          await tester.enterText(find.byType(TextFormField).at(1), 'password');
          await tester.ensureVisible(find.text('INGRESAR'));
          await tester.tap(find.text('INGRESAR'));
          await tester.pump();
          await tester.pump(const Duration(seconds: 1));
          expect(auth.phase, AuthPhase.authenticated, reason: auth.message);
          expect(find.text('Activa el ingreso biométrico'), findsOneWidget);
          await tester.tap(
            find.text(activate ? 'Activar biometría' : 'Ahora no'),
          );
          await tester.pumpAndSettle();
          expect(find.text('¿Qué deseas hacer?'), findsOneWidget);
          expect((auth.gateway as FakeAuthGateway).calls, 1);
          expect(await auth.shouldInviteBiometrics(), false);
        },
      );
    }
    testWidgets(
      'profile security uses native enable and confirms material deletion',
      (tester) async {
        auth.dispose();
        auth = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
          deviceSecurity: vault,
        );
        await auth.login('citizen', 'password');
        auth.completeLoginPresentation();
        await tester.pumpWidget(
          AppScope(
            config: AppConfig.parse(environment: 'dev'),
            auth: auth,
            child: const MaterialApp(
              home: Scaffold(
                body: SingleChildScrollView(child: SecuritySettings()),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byType(SwitchListTile));
        await tester.pumpAndSettle();
        expect(vault.material, isNotEmpty);
        await tester.tap(find.byType(SwitchListTile));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Desactivar'));
        await tester.pumpAndSettle();
        expect(vault.material, isEmpty);
        expect(auth.isAuthenticated, true);
      },
    );
  });

  group('CP24.5.2 private navigation and unchanged payments', () {
    testWidgets('account switch discards the previous private route', (
      tester,
    ) async {
      final auth = AuthController(OtherAccount(), store: MemorySessionStore());
      await auth.login('citizen', 'password');
      await tester.pumpWidget(authTestApp(auth, route: AppRoute.vehicles));
      await tester.pumpAndSettle();
      expect(find.text('Mis vehículos'), findsWidgets);
      await auth.login('other', 'password');
      await tester.pumpAndSettle();
      expect(auth.userId, 'other-id');
      expect(find.byType(VehiclesPage), findsNothing);
      expect(find.text('¿Qué deseas hacer?'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      auth.dispose();
    });
    for (final night in [false, true]) {
      testWidgets(
        'existing lock login respects ${night ? 'night' : 'day'} colors and 200% text',
        (tester) async {
          tester.view.physicalSize = const Size(320, 640);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          tester.platformDispatcher.textScaleFactorTestValue = 2;
          addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
          final lock = InactivityLock();
          final auth = AuthController(
            FakeAuthGateway(),
            store: MemorySessionStore(),
            inactivity: lock,
          );
          await auth.login('citizen', 'password');
          await tester.pumpWidget(
            authTestApp(
              auth,
              utcNow: () => DateTime.utc(2026, 10, 10, night ? 2 : 14),
            ),
          );
          await tester.pumpAndSettle();
          lock.lock();
          await tester.pumpAndSettle();
          await tester.ensureVisible(find.text('Usuario y contraseña'));
          await tester.ensureVisible(find.text('Usuario y contraseña'));
        await tester.tap(find.text('Usuario y contraseña'));
          await tester.pumpAndSettle();
          final context = tester.element(find.text('Desbloquea SIMERTPI'));
          expect(
            Theme.of(context).colorScheme.primary,
            Color(night ? 0xFF020562 : 0xFF0B6B3A),
          );
          await tester.ensureVisible(find.text('INGRESAR'));
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox());
          auth.dispose();
          lock.dispose();
        },
      );
    }
    testWidgets(
      'software keyboard counts human edits, programmatic text changes do not',
      (tester) async {
        var now = DateTime.now();
        final lock = InactivityLock(clock: () => now);
        final auth = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
          inactivity: lock,
        );
        final text = TextEditingController();
        await auth.login('citizen', 'password');
        await tester.pumpWidget(authTestApp(auth));
        await tester.pumpAndSettle();
        tester
            .state<NavigatorState>(find.byType(Navigator).first)
            .push(
              MaterialPageRoute<void>(
                builder: (_) => Scaffold(
                  body: AppTextField(label: 'Dato', controller: text),
                ),
              ),
            );
        await tester.pumpAndSettle();
        now = now.add(const Duration(minutes: 4));
        await tester.enterText(find.byType(TextFormField), 'human edit');
        now = now.add(const Duration(minutes: 4, seconds: 59));
        text.text = 'automatic data';
        lock.check();
        expect(lock.locked, false);
        now = now.add(const Duration(seconds: 1));
        lock.check();
        expect(lock.locked, true);
        await tester.pumpWidget(const SizedBox());
        auth.dispose();
        lock.dispose();
        text.dispose();
      },
    );
    testWidgets(
      'five minutes hides private data; Back/deep route cannot bypass; password restores route',
      (tester) async {
        var now = DateTime.now();
        final lock = InactivityLock(clock: () => now);
        final auth = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
          inactivity: lock,
        );
        await auth.login('citizen', 'password');
        await tester.pumpWidget(authTestApp(auth, route: AppRoute.vehicles));
        await tester.pumpAndSettle();
        expect(find.text('Mis vehículos'), findsWidgets);
        now = now.add(const Duration(minutes: 5));
        lock.check();
        await tester.pumpAndSettle();
        expect(find.text('Mis vehículos'), findsNothing);
        expect(find.text('Usuario y contraseña'), findsOneWidget);
        expect(await tester.binding.handlePopRoute(), true);
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('Usuario y contraseña'));
        await tester.tap(find.text('Usuario y contraseña'));
        await tester.pumpAndSettle();
        expect(find.text('Desbloquea SIMERTPI'), findsOneWidget);
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
        expect(find.text('Mis vehículos'), findsWidgets);
        expect(find.text('Desbloquea SIMERTPI'), findsNothing);
        await tester.pumpWidget(const SizedBox());
        auth.dispose();
        lock.dispose();
      },
    );
    testWidgets(
      'locked root hides even newly generated private notification/deep link routes',
      (tester) async {
        final vault = Vault(), lock = InactivityLock();
        final auth = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
          inactivity: lock,
          deviceSecurity: vault,
        );
        await auth.login('citizen', 'password');
        auth.completeLoginPresentation();
        await tester.pumpWidget(authTestApp(auth));
        await tester.pumpAndSettle();
        final navigator = tester.state<NavigatorState>(
          find.byType(Navigator).first,
        );
        lock.lock();
        await tester.pumpAndSettle();
        navigator.pushNamed(AppRoute.notifications.path);
        await tester.pumpAndSettle();
        expect(find.text('Usuario y contraseña'), findsOneWidget);
        expect(find.text('Notificaciones'), findsNothing);
        expect(vault.protected, true);
        await tester.pumpWidget(const SizedBox());
        auth.dispose();
        lock.dispose();
      },
    );
    testWidgets('lock before Pay has zero operations; unlocking does not pay', (
      tester,
    ) async {
      final f = flow.Flow();
      await f.selection.load();
      f.payment.stageDraft(f.selection);
      final lock = InactivityLock();
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
        inactivity: lock,
      );
      await auth.login('citizen', 'password');
      await tester.pumpWidget(
        AppScope(
          config: AppConfig.parse(environment: 'dev'),
          auth: auth,
          child: SimertpiApp(auth: auth),
        ),
      );
      await tester.pumpAndSettle();
      final navigator = tester.state<NavigatorState>(
        find.byType(Navigator).first,
      );
      navigator.push(
        MaterialPageRoute<void>(
          builder: (_) => PaymentsPage(
            parkingController: f.selection,
            controller: f.payment,
          ),
        ),
      );
      await tester.pumpAndSettle();
      f.noOperations();
      lock.lock();
      await tester.pumpAndSettle();
      expect(find.text(r'Pagar $0,31'), findsNothing);
      f.noOperations();
      await tester.ensureVisible(find.text('Usuario y contraseña'));
        await tester.tap(find.text('Usuario y contraseña'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).last, 'password');
      await tester.ensureVisible(find.text('INGRESAR'));
      await tester.tap(find.text('INGRESAR'));
      await tester.pumpAndSettle();
      f.noOperations();
      expect(find.text(r'Pagar $0,31'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      f.dispose();
      auth.dispose();
      lock.dispose();
    });
    testWidgets(
      'touch Pay at the inactivity deadline is cancelled before any operation',
      (tester) async {
        final f = flow.Flow();
        await f.selection.load();
        f.payment.stageDraft(f.selection);
        var now = DateTime.now();
        final lock = InactivityLock(clock: () => now);
        final auth = AuthController(
          FakeAuthGateway(),
          store: MemorySessionStore(),
          inactivity: lock,
        );
        await auth.login('citizen', 'password');
        await tester.pumpWidget(
          AppScope(
            config: AppConfig.parse(environment: 'dev'),
            auth: auth,
            child: SimertpiApp(auth: auth),
          ),
        );
        await tester.pumpAndSettle();
        final navigator = tester.state<NavigatorState>(
          find.byType(Navigator).first,
        );
        navigator.push(
          MaterialPageRoute<void>(
            builder: (_) => PaymentsPage(
              parkingController: f.selection,
              controller: f.payment,
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text(r'Pagar $0,31'));
        now = now.add(const Duration(minutes: 5));
        await tester.tap(find.text(r'Pagar $0,31'));
        await tester.pumpAndSettle();
        expect(auth.isLocked, true);
        f.noOperations();
        await tester.pumpWidget(const SizedBox());
        f.dispose();
        auth.dispose();
        lock.dispose();
      },
    );
    for (final outcome in ['APPROVED', 'REJECTED', 'UNKNOWN']) {
      test(
        'started payment $outcome completes while locked without a second checkout/cobro',
        () async {
          final f = flow.Flow();
          final lock = InactivityLock();
          final auth = AuthController(
            FakeAuthGateway(),
            store: MemorySessionStore(),
            inactivity: lock,
          );
          await auth.login('citizen', 'password');
          await f.selection.load();
          f.payment.stageDraft(f.selection);
          f.gateway.outcome = outcome == 'UNKNOWN' ? 'PENDING' : outcome;
          f.gateway.gate = Completer<void>();
          if (outcome == 'UNKNOWN') {
            f.gateway.failure = const AppFailure(
              FailureKind.timeout,
              outcomeUnknown: true,
            );
          }
          f.gateway.sent = () => f.parking.existing = [
            pay.session(
              outcome == 'APPROVED'
                  ? 'ACTIVE'
                  : outcome == 'REJECTED'
                  ? 'CANCELLED'
                  : 'PENDING_PAYMENT',
            ),
          ];
          final payment = f.payment.startParking();
          await Future<void>.delayed(Duration.zero);
          lock.lock();
          f.gateway.gate!.complete();
          await payment;
          expect(f.parking.creates, 1);
          expect(f.gateway.creates, 1);
          expect(auth.isLocked, true);
          expect(f.payment.activated, outcome == 'APPROVED');
          expect(await auth.unlockPassword('citizen', 'password'), true);
          expect(f.parking.creates, 1);
          expect(f.gateway.creates, 1);
          if (outcome == 'UNKNOWN') {
            expect(f.payment.phase, PaymentPhase.uncertain);
            expect(f.payment.canConfirm, false);
          }
          f.dispose();
          auth.dispose();
          lock.dispose();
        },
      );
    }
  });
}
