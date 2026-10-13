import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/features/citizen_activity/profile_page.dart';

import 'citizen_activity_test.dart' show FakeActivity;
import 'push_lifecycle_test.dart' show FakeRuntime, FakeApi, FakeStore, ownerA;

void main() {
  testWidgets(
    'Android permission and absent Firebase are shown independently',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('simertpi/notification_settings'),
            (_) async => 'granted',
          );
      await tester.pumpWidget(
        MaterialApp(home: NotificationPreferencesPage(gateway: FakeActivity())),
      );
      await tester.pumpAndSettle();
      expect(find.text('Permiso del dispositivo activado.'), findsOneWidget);
      expect(find.textContaining('Firebase no configurado'), findsOneWidget);
      expect(find.textContaining('Push habilitadas'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('simertpi/notification_settings'),
            null,
          );
    },
  );
  for (final fails in [true, false]) {
    testWidgets(
      'preferences announces confirmed registration only when successful: fails=$fails',
      (tester) async {
        final runtime = FakeRuntime(), api = FakeApi(), store = FakeStore();
        if (fails) runtime.currentToken = '';
        final push = PushDeviceLifecycle(
          runtime: runtime,
          api: api,
          store: store,
          isAuthenticated: () => true,
          ownerId: () => ownerA,
          unreadNotifications: ValueNotifier(0),
          citizenTab: ValueNotifier(0),
          parkingSessionTarget: ValueNotifier(null),
        );
        await tester.pumpWidget(
          MaterialApp(
            home: NotificationPreferencesPage(
              gateway: FakeActivity(),
              pushLifecycle: push,
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byType(OutlinedButton));
        await tester.tap(find.byType(OutlinedButton));
        await tester.pumpAndSettle();
        expect(
          push.registrationState,
          fails
              ? PushRegistrationState.failed
              : PushRegistrationState.registered,
        );
        if (fails) {
          expect(find.textContaining('Error de sincronizaci'), findsOneWidget);
          expect(
            find.text('Dispositivo registrado para recibir push.'),
            findsNothing,
          );
        } else {
          expect(
            find.text('Dispositivo registrado para recibir push.'),
            findsOneWidget,
          );
        }
        await tester.pumpWidget(const SizedBox());
        await push.dispose();
        await runtime.dispose();
      },
    );
  }
}
