import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/logging/app_log.dart';
import 'package:simertpi_citizen_app/core/logging/biometric_diagnostic.dart';
import 'package:simertpi_citizen_app/features/auth/presentation/biometric_diagnostic_dialog.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';
import 'cp2453_auth_test.dart' show Fixture;

void main() {
  tearDown(() => BiometricDiagnostics.pending.value = null);

  test('actual activation failure preserves password session and captures challenge stage', () async {
    final fixture = Fixture();
    await fixture.login();
    fixture.credentials.error = const AppFailure(
      FailureKind.forbidden,
      httpStatus: 403,
    );
    expect(await fixture.auth.enableBiometrics(), false);
    expect(fixture.auth.isAuthenticated, true);
    expect(fixture.signing.signs, 0);
    expect(fixture.signing.saved, isEmpty);
    expect(
      BiometricDiagnostics.pending.value!.failedStage,
      'BIO_REGISTER_CHALLENGE_FAILED',
    );
    expect(
      BiometricDiagnostics.pending.value!.lastCompleted,
      'BIO_KEYSTORE_PREPARE_OK',
    );
    fixture.dispose();
  });

  test(
    'actual renewal failure retains status without deleting password session',
    () async {
      final fixture = Fixture();
      await fixture.login();
      fixture.gateway.failure = const AppFailure(
        FailureKind.unavailable,
        httpStatus: 503,
      );
      expect(await fixture.auth.enableBiometrics(), false);
      expect(fixture.auth.isAuthenticated, true);
      expect(fixture.signing.prepares, 0);
      final report = BiometricDiagnostics.pending.value!;
      expect(report.failedStage, 'BIO_SESSION_RENEW_FAILED');
      expect(report.text, contains('Código HTTP: 503'));
      expect(report.text, contains('/api/v1/auth/refresh'));
      fixture.dispose();
    },
  );

  test(
    'explicit debug DEV gate excludes release and every other environment',
    () {
      for (final environment in AppEnvironment.values) {
        expect(biometricDiagnosticEnabled(environment, debug: false), false);
        expect(
          biometricDiagnosticEnabled(environment, debug: true),
          environment == AppEnvironment.dev,
        );
      }
    },
  );

  test(
    'first failure preserves last success and original HTTP metadata',
    () async {
      final result = await BiometricDiagnostics.capture(() async {
        const log = AppLog();
        log.biometric('BIO_ENABLE_START');
        log.biometric('BIO_KEYSTORE_PREPARE_OK');
        log.biometric(
          'BIO_REGISTER_CHALLENGE_FAILED',
          source: 'HTTP',
          path: '/api/v1/auth/device/register/challenge',
          error: const AppFailure(
            FailureKind.forbidden,
            httpStatus: 403,
            correlationId: '0123456789abcdef0123456789abcdef',
            messageOverride: 'SECRET_BODY',
          ),
        );
        log.biometric('BIO_REGISTER_CHALLENGE_FAILED');
        log.biometric('BIO_ENABLE_COMPLETED', success: false);
        return false;
      });
      expect(result, false);
      final report = BiometricDiagnostics.pending.value!;
      expect(report.failedStage, 'BIO_REGISTER_CHALLENGE_FAILED');
      expect(report.lastCompleted, 'BIO_KEYSTORE_PREPARE_OK');
      expect(report.text, contains('Código HTTP: 403'));
      expect(report.text, contains('Tipo de error: forbidden'));
      expect(report.text, isNot(contains('SECRET_BODY')));
    },
  );

  test('successful activation produces no pending dialog', () async {
    expect(
      await BiometricDiagnostics.capture(() async {
        const AppLog().biometric('BIO_ENABLE_START');
        const AppLog().biometric('BIO_ENABLE_COMPLETED', success: true);
        return true;
      }),
      true,
    );
    expect(BiometricDiagnostics.pending.value, isNull);
  });

  test(
    'a false result without a technical failure does not invent a diagnosis',
    () async {
      await BiometricDiagnostics.capture(() async {
        const AppLog().biometric('BIO_ENABLE_START');
        const AppLog().biometric('BIO_ENABLE_COMPLETED', success: false);
        return false;
      });
      expect(BiometricDiagnostics.pending.value, isNull);
    },
  );

  testWidgets(
    'diagnostic from a previous authentication context cannot cover another login',
    (tester) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      final navigator = GlobalKey<NavigatorState>();
      BiometricDiagnostics.pending.value = BiometricDiagnostic(
        DateTime(2026),
        source: Object(),
      )..observe('event=BIO_KEYSTORE_PREPARE_FAILED');
      await tester.pumpWidget(
        AppScope(
          config: const AppConfig(environment: AppEnvironment.dev),
          auth: auth,
          child: MaterialApp(
            navigatorKey: navigator,
            home: const Scaffold(body: Text('Login existing')),
            builder: (_, child) => BiometricDiagnosticHost(
              navigatorKey: () => navigator,
              ready: () => true,
              child: child!,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Login existing'), findsOneWidget);
      expect(find.text('Diagnóstico biométrico — DEV'), findsNothing);
      expect(BiometricDiagnostics.pending.value, isNull);
      await tester.pumpWidget(const SizedBox());
      auth.dispose();
    },
  );

  for (final stage in [
    'BIO_SESSION_RENEW',
    'BIO_KEYSTORE_PREPARE',
    'BIO_REGISTER_CHALLENGE',
    'BIO_ANDROID_PROMPT',
    'BIO_REGISTER_VERIFY',
    'BIO_LOCAL_STATE',
  ]) {
    test('$stage failure retains its stage without assuming a HTTP result', () {
      final report = BiometricDiagnostic(DateTime(2026, 10, 12));
      report.observe(AppLog.biometricLine('${stage}_START')!);
      report.observe(
        AppLog.biometricLine(
          '${stage}_FAILED',
          error: PlatformException(
            code: 'UNAVAILABLE',
            message: 'SECRET',
            details: {
              'nativeCode': 'KEY_GENERATION',
              'exceptionClass': 'ProviderException',
              'androidCode': 7,
              'privateKey': 'SECRET_KEY',
            },
          ),
        )!,
      );
      expect(report.failedStage, '${stage}_FAILED');
      expect(report.text, contains('Código Android: 7'));
      expect(report.text, contains('ProviderException'));
      expect(report.text, contains('Código HTTP: No disponible'));
      expect(report.text, isNot(contains('SECRET')));
    });
  }

  test('diagnostic model independently rejects secret fields and arbitrary strings', () {
    final report = BiometricDiagnostic(DateTime(2026));
    report.observe(
      'event=BIO_LOCAL_BINDING_FAILED errorType=SECRET_TYPE '
      'exceptionClass=SECRET_CLASS path=/secret?token=SECRET correlationId=SECRET '
      'nativeCode=SECRET androidCode=SECRET password=SECRET',
    );
    expect(report.text, isNot(contains('SECRET')));
    expect(report.text, contains('No disponible'));
  });

  testWidgets(
    'pending diagnostic survives route change, displays once, copies safely and close preserves session',
    (tester) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      await auth.login('fixture', 'password');
      final navigator = GlobalKey<NavigatorState>();
      final routes = BiometricDiagnosticRouteObserver();
      var ready = false;
      await tester.pumpWidget(
        AppScope(
          config: const AppConfig(environment: AppEnvironment.dev),
          auth: auth,
          child: MaterialApp(
            navigatorKey: navigator,
            navigatorObservers: [routes],
            home: const Scaffold(body: Text('Login existing')),
            builder: (_, child) => BiometricDiagnosticHost(
              navigatorKey: () => navigator,
              ready: () => ready && routes.settled,
              child: child!,
            ),
          ),
        ),
      );
      final report = BiometricDiagnostic(DateTime(2026, 10, 12));
      report.observe(
        AppLog.biometricLine(
          'BIO_KEYSTORE_PREPARE_FAILED',
          error: PlatformException(code: 'UNAVAILABLE', message: 'SECRET'),
        )!,
      );
      BiometricDiagnostics.pending.value = report;
      await tester.pump();
      expect(find.text('Diagnóstico biométrico — DEV'), findsNothing);
      navigator.currentState!.pushReplacement(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Home existing')),
        ),
      );
      ready = true;
      await tester.pumpAndSettle();
      expect(find.text('Diagnóstico biométrico — DEV'), findsOneWidget);
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            copied = (call.arguments as Map)['text'] as String;
          }
          return null;
        },
      );
      await tester.tap(find.text('Copiar diagnóstico'));
      await tester.pump();
      expect(copied, report.text);
      expect(copied, isNot(contains('SECRET')));
      await tester.tap(find.text('Cerrar'));
      await tester.pumpAndSettle();
      expect(auth.isAuthenticated, true);
      expect(find.text('Home existing'), findsOneWidget);
      expect(find.text('Diagnóstico biométrico — DEV'), findsNothing);
      await tester.pump(const Duration(seconds: 1));
      expect(find.text('Diagnóstico biométrico — DEV'), findsNothing);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
      await tester.pumpWidget(const SizedBox());
      auth.dispose();
    },
  );

  for (final environment in [
    AppEnvironment.qa,
    AppEnvironment.uat,
    AppEnvironment.prod,
  ]) {
    testWidgets('$environment never presents pending diagnostics', (
      tester,
    ) async {
      final auth = AuthController(
        FakeAuthGateway(),
        store: MemorySessionStore(),
      );
      final navigator = GlobalKey<NavigatorState>();
      await tester.pumpWidget(
        AppScope(
          config: AppConfig(environment: environment),
          auth: auth,
          child: MaterialApp(
            navigatorKey: navigator,
            home: const SizedBox(),
            builder: (_, child) => BiometricDiagnosticHost(
              navigatorKey: () => navigator,
              ready: () => true,
              child: child!,
            ),
          ),
        ),
      );
      BiometricDiagnostics.pending.value = BiometricDiagnostic(DateTime(2026))
        ..observe('event=BIO_KEYSTORE_PREPARE_FAILED');
      await tester.pumpAndSettle();
      expect(find.text('Diagnóstico biométrico — DEV'), findsNothing);
      expect(BiometricDiagnostics.pending.value, isNull);
      await tester.pumpWidget(const SizedBox());
      auth.dispose();
    });
  }
}
