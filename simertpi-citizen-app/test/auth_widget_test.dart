import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/widgets/app_buttons.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

import 'support/auth_test_support.dart';

void main() {
  late FakeAuthGateway fake;
  late AuthController auth;
  setUp(() {
    fake = FakeAuthGateway();
    auth = AuthController(fake, store: MemorySessionStore());
  });
  tearDown(() => auth.dispose());
  Future<void> fillLogin(WidgetTester tester) async {
    final fields = find.byType(TextFormField);
    await tester.enterText(fields.at(0), 'citizen');
    await tester.enterText(fields.at(1), 'fixture-password');
  }

  testWidgets(
    'Login renders Figma hierarchy with backend username and password toggle',
    (tester) async {
      await tester.pumpWidget(authTestApp(auth));
      expect(find.text('Inicia sesión'), findsOneWidget);
      expect(find.text('Nombre de usuario'), findsWidgets);
      expect(
        tester.widget<TextField>(find.byType(TextField).last).obscureText,
        true,
      );
      await tester.tap(find.byTooltip('Mostrar contraseña'));
      await tester.pump();
      expect(
        tester.widget<TextField>(find.byType(TextField).last).obscureText,
        false,
      );
      expect(find.text('Recuperar contraseña'), findsNothing);
    },
  );
  testWidgets('Required validation is local and does not submit', (
    tester,
  ) async {
    await tester.pumpWidget(authTestApp(auth));
    await tester.tap(find.text('INGRESAR'));
    await tester.pumpAndSettle();
    expect(find.text('Ingresa tu nombre de usuario.'), findsOneWidget);
    expect(find.text('Ingresa tu contraseña.'), findsOneWidget);
    expect(fake.calls, 0);
  });
  testWidgets(
    'Processing locks button, prevents double submit and success resets navigation stack',
    (tester) async {
      fake.pending = Completer<void>();
      await tester.pumpWidget(authTestApp(auth));
      await fillLogin(tester);
      await tester.tap(find.text('INGRESAR'));
      await tester.pump();
      expect(find.text('Ingresando…'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );
      await tester.tap(find.text('Ingresando…'));
      expect(fake.calls, 1);
      fake.pending!.complete();
      await tester.pumpAndSettle();
      expect(find.text('¿Dónde vas a estacionar?'), findsOneWidget);
      expect(
        Navigator.of(tester.element(find.text('¿Dónde vas a estacionar?')))
            .canPop(),
        false,
      );
      await tester.ensureVisible(find.byTooltip('Cerrar sesión'));
      await tester.tap(find.byTooltip('Cerrar sesión'));
      await tester.pumpAndSettle();
      expect(find.text('Inicia sesión'), findsOneWidget);
      expect(
        Navigator.of(tester.element(find.text('Inicia sesión'))).canPop(),
        false,
      );
      expect(find.text('¿Dónde vas a estacionar?'), findsNothing);
    },
  );
  testWidgets(
    'Invalid credentials show understandable error and preserve inputs',
    (tester) async {
      fake.failure = const AppFailure(FailureKind.unauthorized);
      await tester.pumpWidget(authTestApp(auth));
      await fillLogin(tester);
      await tester.tap(find.text('INGRESAR'));
      await tester.pumpAndSettle();
      expect(
        find.text('No pudimos verificar tu usuario y contraseña.'),
        findsOneWidget,
      );
      expect(
        tester.widget<TextField>(find.byType(TextField).first).controller!.text,
        'citizen',
      );
      expect(find.text('Ingresando…'), findsNothing);
    },
  );
  testWidgets(
    'Direct protected route and invalidation cannot expose showcase',
    (tester) async {
      await tester.pumpWidget(authTestApp(auth, route: AppRoute.home));
      expect(find.text('Inicia sesión'), findsOneWidget);
      expect(find.text('¿Dónde vas a estacionar?'), findsNothing);
      await auth.login('citizen', 'fixture-password');
      await tester.pumpAndSettle();
      expect(find.text('¿Dónde vas a estacionar?'), findsOneWidget);
      await auth.logout(expired: true);
      await tester.pumpAndSettle();
      expect(find.textContaining('Tu sesión ya no es válida'), findsOneWidget);
      expect(find.text('¿Dónde vas a estacionar?'), findsNothing);
    },
  );
  testWidgets(
    'Registration submits exact backend fields and never auto-authenticates',
    (tester) async {
      await tester.pumpWidget(authTestApp(auth));
      await tester.tap(find.text('¿No tienes cuenta? Crear cuenta'));
      await tester.pumpAndSettle();
      final values = [
        'citizen',
        'Fixture',
        'Citizen',
        'test@example.invalid',
        '',
        'fixture-password',
      ];
      for (var i = 0; i < values.length; i++) {
        await tester.ensureVisible(find.byType(TextFormField).at(i));
        await tester.enterText(find.byType(TextFormField).at(i), values[i]);
      }
      tester.testTextInput.hide();
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('CREAR CUENTA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CREAR CUENTA'));
      await tester.pumpAndSettle();
      expect(fake.registrations, 1);
      expect(fake.registered!.phone, isNull);
      expect(auth.isAuthenticated, false);
      expect(
        find.text('Cuenta creada. Ahora puedes iniciar sesión.'),
        findsOneWidget,
      );
      await tester.tap(find.text('Ir a iniciar sesión'));
      await tester.pumpAndSettle();
      expect(find.text('Inicia sesión'), findsOneWidget);
      expect(fake.calls, 0);
      await fillLogin(tester);
      await tester.tap(find.text('INGRESAR'));
      await tester.pumpAndSettle();
      expect(fake.calls, 1);
      expect(await auth.store.read(), isNotNull);
      expect(find.text('¿Dónde vas a estacionar?'), findsOneWidget);
    },
  );
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets('Auth is scrollable with keyboard and 200% text at $size', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      tester.view.viewInsets = const FakeViewPadding(bottom: 160);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      for (final route in [AppRoute.login, AppRoute.register]) {
        await tester.pumpWidget(
          authTestApp(auth, route: route, key: ValueKey(route)),
        );
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byType(AsyncButton));
        expect(tester.takeException(), isNull);
        expect(
          tester.getSize(find.byType(FilledButton)).height,
          greaterThanOrEqualTo(48),
        );
      }
    });
  }
}
