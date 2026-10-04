import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/app/app.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/state/async_state.dart';
import 'package:simertpi_citizen_app/core/theme/app_tokens.dart';
import 'package:simertpi_citizen_app/core/widgets/app_buttons.dart';
import 'package:simertpi_citizen_app/core/widgets/app_feedback.dart';
import 'package:simertpi_citizen_app/core/widgets/app_skeleton.dart';

void main() {
  test('Environment configuration fails closed without inventing URLs', () {
    expect(AppConfig.parse(environment: 'dev').apiBaseUrl, isNull);
    for (final environment in ['qa', 'uat', 'prod']) {
      expect(
        () => AppConfig.parse(environment: environment),
        throwsFormatException,
      );
      expect(
        AppConfig.parse(
          environment: environment,
          apiBaseUrl: 'https://backend.invalid/api/v1',
        ).environment.name,
        environment,
      );
      expect(
        () => AppConfig.parse(
          environment: environment,
          apiBaseUrl: 'http://backend.invalid',
        ),
        throwsFormatException,
      );
    }
    expect(
      () => AppConfig.parse(environment: 'unknown'),
      throwsFormatException,
    );
    expect(
      () => AppConfig.parse(
        environment: 'prod',
        apiBaseUrl: 'https://user:secret@backend.invalid',
      ),
      throwsFormatException,
    );
    expect(
      () => AppConfig.parse(
        environment: 'dev',
        apiBaseUrl: 'https://backend.invalid?token=secret',
      ),
      throwsFormatException,
    );
  });

  test('Controller distinguishes initial, loading, empty, error, success and refreshing', () async {
    final controller = AsyncController<List<String>>();
    expect(controller.state.phase, LoadPhase.initial);
    final result = Completer<List<String>>();
    final pending = controller.load(
      () => result.future,
      isEmpty: (v) => v.isEmpty,
    );
    expect(controller.state.phase, LoadPhase.loading);
    var duplicateCalls = 0;
    await controller.load(() async {
      duplicateCalls++;
      return [];
    }, isEmpty: (v) => v.isEmpty);
    expect(duplicateCalls, 0);
    result.complete([]);
    await pending;
    expect(controller.state.phase, LoadPhase.empty);
    await controller.load(
      () async => throw const AppFailure(FailureKind.offline),
      isEmpty: (v) => v.isEmpty,
    );
    expect(controller.state.phase, LoadPhase.error);
    await controller.load(() async => ['example'], isEmpty: (v) => v.isEmpty);
    expect(controller.state.phase, LoadPhase.success);
    final refresh = Completer<List<String>>();
    final refreshing = controller.load(
      () => refresh.future,
      isEmpty: (v) => v.isEmpty,
      refresh: true,
    );
    expect(controller.state.phase, LoadPhase.refreshing);
    expect(controller.state.data, ['example']);
    refresh.complete(['updated']);
    await refreshing;
    controller.dispose();
  });

  test('Late load completion after disposal does not notify', () async {
    final controller = AsyncController<String>();
    final result = Completer<String>();
    final pending = controller.load(
      () => result.future,
      isEmpty: (v) => v.isEmpty,
    );
    controller.dispose();
    result.complete('done');
    await pending;
  });

  test('Uncertain write result never claims definitive failure', () {
    expect(
      const AppFailure(FailureKind.unknown, outcomeUnknown: true).message,
      contains('Consulta el estado'),
    );
    expect(
      const AppFailure(FailureKind.unknown).toString(),
      isNot(contains('secret')),
    );
  });

  testWidgets(
    'Bootstrap, splash and provisional theme load without backend calls',
    (tester) async {
      await tester.pumpWidget(const Bootstrap());
      await tester.pumpAndSettle();
      expect(find.text('SIMERTPI'), findsOneWidget);
      expect(find.text('Demostración'), findsOneWidget);
      final context = tester.element(find.text('Demostración'));
      expect(Theme.of(context).colorScheme.primary, AppColors.primary);
      expect(AppScope.of(context).config.environment, AppEnvironment.dev);
    },
  );

  testWidgets('Central router opens gallery and returns to home', (
    tester,
  ) async {
    await tester.pumpWidget(SimertpiApp(initialRoute: AppRoute.home.path));
    await tester.tap(find.text('Ver componentes'));
    await tester.pumpAndSettle();
    expect(find.text('Componentes'), findsOneWidget);
    expect(find.text('Texto de ejemplo'), findsOneWidget);
    expect(
      Navigator.of(tester.element(find.text('Texto de ejemplo'))).canPop(),
      isTrue,
    );
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(find.text('Demostración'), findsOneWidget);
  });

  testWidgets(
    'Skeleton, empty and error states remain distinct and retry provides feedback',
    (tester) async {
      await tester.pumpWidget(SimertpiApp(initialRoute: AppRoute.states.path));
      await tester.tap(find.text('Cargando'));
      await tester.pump();
      expect(find.byType(SkeletonCard), findsNWidgets(2));
      expect(find.byType(EmptyState), findsNothing);
      await tester.tap(find.text('Vacío'));
      await tester.pump();
      expect(find.byType(EmptyState), findsOneWidget);
      expect(find.byType(SkeletonCard), findsNothing);
      await tester.tap(find.text('Error'));
      await tester.pump();
      expect(find.byType(ErrorState), findsOneWidget);
      await tester.ensureVisible(find.text('Reintentar'));
      await tester.tap(find.text('Reintentar'));
      await tester.pump();
      expect(find.text('Vista de demostración lista.'), findsOneWidget);
    },
  );

  testWidgets('AsyncButton shows progress and prevents duplicate interaction', (
    tester,
  ) async {
    final operation = Completer<void>();
    var calls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AsyncButton(
            label: 'Confirmar ejemplo',
            onPressed: () {
              calls++;
              return operation.future;
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('Confirmar ejemplo'));
    await tester.pump();
    expect(find.text('Procesando…'), findsOneWidget);
    await tester.tap(find.text('Procesando…'));
    expect(calls, 1);
    operation.complete();
    await tester.pumpAndSettle();
    expect(find.text('Confirmar ejemplo'), findsOneWidget);
  });

  testWidgets('AsyncButton sanitizes exceptions and is usable again', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AsyncButton(
            label: 'Probar',
            onPressed: () async => throw Exception('FIXTURE_SECRET'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Probar'));
    await tester.pumpAndSettle();
    expect(find.text('No pudimos cargar la información.'), findsOneWidget);
    expect(find.textContaining('FIXTURE_SECRET'), findsNothing);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNotNull,
    );
  });

  testWidgets('Primary action meets touch and contrast requirements', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PrimaryButton(label: 'Acción de ejemplo', onPressed: () {}),
        ),
      ),
    );
    expect(
      tester.getSize(find.byType(FilledButton)).height,
      greaterThanOrEqualTo(48),
    );
    final ratio =
        (Colors.white.computeLuminance() + .05) /
        (AppColors.primary.computeLuminance() + .05);
    expect(ratio, greaterThanOrEqualTo(4.5));
  });

  for (final dimensions in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets('Showcase avoids overflow at $dimensions and 200% text', (
      tester,
    ) async {
      tester.view.physicalSize = dimensions;
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      for (final route in [
        AppRoute.home,
        AppRoute.components,
        AppRoute.states,
      ]) {
        await tester.pumpWidget(
          SimertpiApp(key: ValueKey(route), initialRoute: route.path),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }
    });
  }
}
