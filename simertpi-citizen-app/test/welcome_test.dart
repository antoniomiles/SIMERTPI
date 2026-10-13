import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/app.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/theme/app_theme.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/home/home_page.dart';
import 'package:simertpi_citizen_app/features/welcome/welcome_page.dart';
import 'package:simertpi_citizen_app/features/welcome/welcome_theme.dart';

import 'support/auth_test_support.dart';
import 'push_lifecycle_test.dart' as push_fixture;

class FakeNotificationPermissionGateway
    implements NotificationPermissionGateway {
  PushPermissionState state = PushPermissionState.notDetermined;
  PushPermissionState requestResult = PushPermissionState.granted;
  int requests = 0;

  @override
  Future<PushPermissionState> permission() async => state;

  @override
  Future<PushPermissionState> requestPermission() async {
    requests++;
    state = requestResult;
    return state;
  }
}

void main() {
  test('Poppins and approved logos are bundled as local assets', () async {
    for (final asset in [
      'assets/fonts/Poppins-Regular.ttf',
      'assets/fonts/Poppins-Medium.ttf',
      'assets/fonts/Poppins-SemiBold.ttf',
      'assets/fonts/OFL.txt',
      'assets/branding/SIMERTPI_logotipo_negro.png',
      'assets/branding/SIMERTPI_logotipo_blanco.png',
    ]) {
      final data = await rootBundle.load(asset);
      expect(data.lengthInBytes, greaterThan(0), reason: asset);
    }
  });

  test('citizen themes apply Poppins and day/night brand colors globally', () {
    final theme = AppTheme.light;
    final nightTheme = AppTheme.forNight(true);
    expect(theme.textTheme.bodyMedium?.fontFamily, 'Poppins');
    expect(theme.textTheme.bodyMedium?.fontWeight, FontWeight.w400);
    expect(theme.textTheme.titleLarge?.fontWeight, FontWeight.w600);
    expect(theme.textTheme.titleMedium?.fontWeight, FontWeight.w500);
    expect(theme.textTheme.labelLarge?.fontWeight, FontWeight.w500);
    expect(AppColors.brandGreen, const Color(0xFF0B6B3A));
    expect(theme.colorScheme.primary, AppColors.brandGreen);
    expect(AppColors.brandNightBlue, const Color(0xFF020562));
    expect(nightTheme.colorScheme.primary, AppColors.brandNightBlue);
    expect(nightTheme.colorScheme.secondary, AppColors.brandGreen);
    expect(
      (Colors.white.computeLuminance() + .05) /
          (AppColors.brandNightBlue.computeLuminance() + .05),
      greaterThan(7),
    );
    expect(nightTheme.extension<AppBrandPalette>()?.isNight, isTrue);
  });

  test('Ecuador continental mode honors both daily boundaries', () {
    expect(
      welcomeModeAt(DateTime.utc(2026, 1, 2, 10, 59)),
      WelcomeMode.night,
    ); // 05:59 Ecuador
    expect(
      welcomeModeAt(DateTime.utc(2026, 1, 2, 11)),
      WelcomeMode.day,
    ); // 06:00 Ecuador
    expect(
      welcomeModeAt(DateTime.utc(2026, 1, 2, 23, 59)),
      WelcomeMode.day,
    ); // 18:59 Ecuador
    expect(
      welcomeModeAt(DateTime.utc(2026, 1, 3)),
      WelcomeMode.night,
    ); // 19:00 Ecuador
  });

  test('next transition is calculated in Ecuador time and returned as UTC', () {
    expect(
      nextWelcomeModeChangeUtc(DateTime.utc(2026, 1, 2, 10, 59)),
      DateTime.utc(2026, 1, 2, 11),
    );
    expect(
      nextWelcomeModeChangeUtc(DateTime.utc(2026, 1, 2, 23, 59)),
      DateTime.utc(2026, 1, 3),
    );
  });

  testWidgets(
    'welcome loads approved art and navigates to existing auth routes',
    (tester) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      addTearDown(auth.dispose);
      await tester.pumpWidget(authTestApp(auth, route: AppRoute.welcome));
      await tester.pumpAndSettle();

      expect(find.text('Usuario y contraseña'), findsOneWidget);
      expect(find.text('Crear cuenta'), findsOneWidget);
      expect(find.text('Próximamente'), findsNothing);
      final loginIcon = tester.getRect(
        find.byKey(const ValueKey('welcome-login-icon')),
      );
      final loginLabel = tester.getRect(find.text('Usuario y contraseña'));
      final biometricIcon = tester.getRect(
        find.byKey(const ValueKey('welcome-biometric-icon')),
      );
      final biometricLabel = tester.getRect(find.text('Ingresar con huella'));
      final loginButton = tester.getRect(
        find.byKey(const ValueKey('welcome-login-action')),
      );
      final biometricButton = tester.getRect(
        find.byKey(const ValueKey('welcome-biometric-action')),
      );
      expect(loginIcon.center.dy, lessThan(loginLabel.center.dy));
      expect(biometricIcon.center.dy, lessThan(biometricLabel.center.dy));
      expect(loginButton.height, biometricButton.height);
      expect(loginButton.width, biometricButton.width);
      expect(loginButton.height, 70);
      expect(find.byType(SingleChildScrollView), findsNothing);
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, 'Ingresar con huella'),
            )
            .onPressed,
        isNotNull,
      );
      expect(
        find.byKey(const ValueKey('welcome-logo-day')).evaluate().isNotEmpty ||
            find
                .byKey(const ValueKey('welcome-logo-night'))
                .evaluate()
                .isNotEmpty,
        isTrue,
      );
      final isDay = find
          .byKey(const ValueKey('welcome-logo-day'))
          .evaluate()
          .isNotEmpty;
      final logo = tester.widget<Image>(
        find.byKey(ValueKey(isDay ? 'welcome-logo-day' : 'welcome-logo-night')),
      );
      expect(logo.width, lessThan(270));
      expect(logo.height, lessThan(70));
      expect(logo.width, greaterThanOrEqualTo(225));
      expect(logo.height, greaterThanOrEqualTo(44));
      expect(
        tester
            .getRect(
              find.byKey(
                ValueKey(isDay ? 'welcome-logo-day' : 'welcome-logo-night'),
              ),
            )
            .top,
        greaterThanOrEqualTo(20),
      );
      expect(
        (logo.image as AssetImage).assetName,
        isDay
            ? 'assets/branding/SIMERTPI_logotipo_negro.png'
            : 'assets/branding/SIMERTPI_logotipo_blanco.png',
      );
      expect(tester.takeException(), isNull);

      final art = tester.getRect(
        find.byKey(ValueKey('welcome-art-${isDay ? 'day' : 'night'}')),
      );
      final artFinder = find.byKey(
        ValueKey('welcome-art-${isDay ? 'day' : 'night'}'),
      );
      expect(art.left, 0);
      expect(art.width, MediaQuery.sizeOf(tester.element(artFinder)).width);
      expect(art.height / art.width, closeTo(1672 / 941, .001));
      expect(loginButton.top, lessThan(art.bottom));
      expect(loginButton.bottom, lessThanOrEqualTo(800));
      expect(loginButton.width, closeTo(178, 1));

      await tester.ensureVisible(find.text('Usuario y contraseña'));
      await tester.tap(find.text('Usuario y contraseña'));
      await tester.pumpAndSettle();
      expect(find.text('Inicia sesión'), findsOneWidget);
      expect(find.byType(TextFormField), findsNWidgets(2));

      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Crear cuenta'));
      await tester.tap(find.text('Crear cuenta'));
      await tester.pumpAndSettle();
      expect(find.text('Nombres'), findsOneWidget);
      expect(find.text('Apellidos'), findsOneWidget);
    },
  );

  testWidgets('welcome reevaluates Ecuador mode when app resumes', (
    tester,
  ) async {
    var now = DateTime.utc(2026, 2, 1, 10, 59);
    final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
    addTearDown(auth.dispose);
    await tester.pumpWidget(
      authTestApp(auth, route: AppRoute.welcome, utcNow: () => now),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('welcome-art-night')), findsOneWidget);
    expect(
      Theme.of(tester.element(find.byType(WelcomePage))).colorScheme.primary,
      AppColors.brandNightBlue,
    );

    now = DateTime.utc(2026, 2, 1, 11);
    await tester.pump(const Duration(minutes: 1));
    expect(find.byKey(const ValueKey('welcome-art-day')), findsOneWidget);
    expect(
      Theme.of(tester.element(find.byType(WelcomePage))).colorScheme.primary,
      AppColors.brandGreen,
    );
    now = DateTime.utc(2026, 2, 1, 10, 59);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(find.byKey(const ValueKey('welcome-art-night')), findsOneWidget);
    now = DateTime.utc(2026, 2, 1, 11);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(find.byKey(const ValueKey('welcome-art-day')), findsOneWidget);
    now = DateTime.utc(2026, 2, 2, 0);
    await tester.pump(const Duration(hours: 13));
    expect(find.byKey(const ValueKey('welcome-art-night')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('welcome brand CTA and upper controls follow the active mode', (
    tester,
  ) async {
    var now = DateTime.utc(2026, 2, 1, 11);
    final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
    addTearDown(auth.dispose);
    await tester.pumpWidget(
      authTestApp(auth, route: AppRoute.welcome, utcNow: () => now),
    );
    await tester.pump();

    FilledButton createButton() => tester.widget<FilledButton>(
      find.byKey(const ValueKey('welcome-create-account')),
    );
    expect(
      createButton().style!.backgroundColor!.resolve({}),
      AppColors.brandGreen,
    );
    final dayLogin = tester.widget<OutlinedButton>(
      find.byKey(const ValueKey('welcome-login-action')),
    );
    expect(dayLogin.style!.foregroundColor!.resolve({}), AppColors.brandGreen);
    expect(
      tester.getSize(find.byKey(const ValueKey('welcome-login-action'))).height,
      70,
    );

    now = DateTime.utc(2026, 2, 2, 0);
    await tester.pump(const Duration(hours: 13));
    expect(
      createButton().style!.backgroundColor!.resolve({}),
      AppColors.brandNightBlue,
    );
    final nightLogin = tester.widget<OutlinedButton>(
      find.byKey(const ValueKey('welcome-login-action')),
    );
    expect(
      nightLogin.style!.foregroundColor!.resolve({}),
      AppColors.brandNightBlue,
    );
    expect(
      tester.getSize(find.byKey(const ValueKey('welcome-login-action'))).height,
      70,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('welcome requests OS permission once without account opt-in', (
    tester,
  ) async {
    final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
    final runtime = push_fixture.FakeRuntime()
      ..state = PushPermissionState.notDetermined;
    final store = push_fixture.FakeStore();
    final api = push_fixture.FakeApi();
    final permission = FakeNotificationPermissionGateway();
    final lifecycle = PushDeviceLifecycle(
      runtime: runtime,
      api: api,
      store: store,
      isAuthenticated: () => auth.isAuthenticated,
      ownerId: () => auth.userId,
      unreadNotifications: ValueNotifier(0),
      citizenTab: ValueNotifier(0),
      parkingSessionTarget: ValueNotifier(null),
    );
    final prompt = WelcomePushPermissionPrompt(
      store: store,
      gateway: permission,
      isAuthenticated: () => auth.isAuthenticated,
    );
    addTearDown(() async {
      lifecycle.dispose();
      await runtime.dispose();
      auth.dispose();
    });

    await tester.pumpWidget(
      AppScope(
        config: AppConfig.parse(environment: 'dev'),
        auth: auth,
        pushLifecycle: lifecycle,
        welcomePermissionPrompt: prompt,
        child: SimertpiApp(auth: auth, initialRoute: AppRoute.welcome.path),
      ),
    );
    await tester.pumpAndSettle();

    expect(permission.requests, 1);
    expect(runtime.prompts, 0);
    expect(runtime.tokenReads, 0);
    expect(api.registrations, isEmpty);
    expect(store.consents, isEmpty);
    expect(store.welcomePrompted, isTrue);
    expect(runtime.autoInit, isFalse);
    expect(find.byType(WelcomePage), findsOneWidget);
    expect(find.byKey(const ValueKey('welcome-login-action')), findsOneWidget);
    expect(permission.requests, 1);
  });

  test('permission denial is not prompted again from Welcome', () async {
    final runtime = push_fixture.FakeRuntime()
      ..state = PushPermissionState.notDetermined
      ..requestResult = PushPermissionState.denied;
    final store = push_fixture.FakeStore();
    final permission = FakeNotificationPermissionGateway()
      ..requestResult = PushPermissionState.denied;
    final prompt = WelcomePushPermissionPrompt(
      store: store,
      gateway: permission,
      isAuthenticated: () => false,
    );
    addTearDown(() async {
      await runtime.dispose();
    });

    expect(
      await prompt.requestOnce(),
      PushPermissionState.systemSettingsRequired,
    );
    expect(
      await prompt.requestOnce(),
      PushPermissionState.systemSettingsRequired,
    );
    expect(permission.requests, 1);
    expect(runtime.prompts, 0);
    expect(runtime.tokenReads, 0);
    expect(store.consents, isEmpty);
  });

  test(
    'already granted system permission skips another native request',
    () async {
      final runtime = push_fixture.FakeRuntime();
      final store = push_fixture.FakeStore();
      final permission = FakeNotificationPermissionGateway()
        ..state = PushPermissionState.granted;
      final prompt = WelcomePushPermissionPrompt(
        store: store,
        gateway: permission,
        isAuthenticated: () => false,
      );
      addTearDown(() async {
        await runtime.dispose();
      });

      expect(await prompt.requestOnce(), PushPermissionState.granted);
      expect(permission.requests, 0);
      expect(runtime.tokenReads, 0);
    },
  );

  testWidgets(
    'auth header is white with the official black logo in both modes',
    (tester) async {
      var now = DateTime.utc(2026, 2, 1, 11);
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      addTearDown(auth.dispose);
      await tester.pumpWidget(authTestApp(auth, utcNow: () => now));
      await tester.pump();

      final appBar = tester.widget<AppBar>(find.byType(AppBar));
      expect(appBar.backgroundColor, Colors.white);
      expect(appBar.title, isNull);
      expect(find.byKey(const ValueKey('auth-brand-logo')), findsOneWidget);
      final appBarRect = tester.getRect(find.byType(AppBar));
      final logoRect = tester.getRect(
        find.byKey(const ValueKey('auth-brand-logo')),
      );
      expect(logoRect.top, greaterThan(appBarRect.bottom));
      expect(logoRect.center.dx, closeTo(appBarRect.center.dx, 1));
      expect(find.text('SIMERTPI'), findsNothing);
      final dayLogo = tester.widget<Image>(
        find.byKey(const ValueKey('auth-brand-logo')),
      );
      expect(
        Theme.of(tester.element(find.text('INGRESAR')))
            .filledButtonTheme
            .style!
            .backgroundColor!
            .resolve({}),
        AppColors.brandGreen,
      );
      expect(
        (dayLogo.image as AssetImage).assetName,
        'assets/branding/SIMERTPI_logotipo_negro.png',
      );

      now = DateTime.utc(2026, 2, 1, 10);
      await tester.pumpWidget(
        authTestApp(auth, utcNow: () => now, key: UniqueKey()),
      );
      await tester.pump();
      expect(find.byKey(const ValueKey('auth-brand-logo')), findsOneWidget);
      final nightLogo = tester.widget<Image>(
        find.byKey(const ValueKey('auth-brand-logo')),
      );
      expect(
        (nightLogo.image as AssetImage).assetName,
        'assets/branding/SIMERTPI_logotipo_negro.png',
      );
      expect(
        tester.widget<AppBar>(find.byType(AppBar)).backgroundColor,
        Colors.white,
      );
      expect(
        find.byKey(const ValueKey('auth-brand-surface-night')),
        findsNothing,
      );
      expect(
        Theme.of(tester.element(find.text('INGRESAR')))
            .filledButtonTheme
            .style!
            .backgroundColor!
            .resolve({}),
        AppColors.brandNightBlue,
      );
      final fieldTheme = Theme.of(tester.element(find.text('INGRESAR')))
          .inputDecorationTheme;
      expect(fieldTheme.fillColor, const Color(0xFFEEF4FA));
      expect(
        (fieldTheme.focusedBorder! as OutlineInputBorder).borderSide.color,
        AppColors.brandNightBlue,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'existing session bypasses welcome and splash continues to Home',
    (tester) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await auth.login('citizen', 'fixture-password');
      addTearDown(auth.dispose);

      await tester.pumpWidget(authTestApp(auth, route: AppRoute.splash));
      await tester.pumpAndSettle();
      expect(find.byType(HomePage), findsOneWidget);
      expect(find.byType(WelcomePage), findsNothing);
    },
  );

  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets('welcome fits at $size with accessible controls', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      addTearDown(auth.dispose);

      await tester.pumpWidget(authTestApp(auth, route: AppRoute.welcome));
      await tester.pumpAndSettle();
      expect(find.text('Usuario y contraseña'), findsOneWidget);
      expect(find.text('Ingresar con huella'), findsOneWidget);
      expect(find.text('Crear cuenta'), findsOneWidget);
      if (size.height > size.width) {
        expect(find.byType(SingleChildScrollView), findsNothing);
      } else {
        expect(
          find.byKey(const ValueKey('welcome-constrained-scroll')),
          findsOneWidget,
        );
      }
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('welcome remains usable with 200% text scaling', (tester) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
    addTearDown(auth.dispose);

    await tester.pumpWidget(authTestApp(auth, route: AppRoute.welcome));
    await tester.pumpAndSettle();
    expect(find.text('Usuario y contraseña'), findsOneWidget);
    expect(find.text('Crear cuenta'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
