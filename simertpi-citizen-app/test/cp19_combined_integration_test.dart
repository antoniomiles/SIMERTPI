import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';
import 'package:simertpi_citizen_app/features/home/home_greeting_controller.dart';

import 'push_lifecycle_test.dart' as f;
import 'active_parking_test.dart' as parking;
import 'cp192_home_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'initial grant and greeting synchronize; voluntary OFF survives resume',
    () async {
      final runtime = f.FakeRuntime();
      final api = f.FakeApi()..pushEnabled = false;
      final store = f.FakeStore()..welcomePrompted = true;
      final tab = ValueNotifier(0);
      final target = ValueNotifier<String?>(null);
      final push = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => f.ownerA,
        unreadNotifications: ValueNotifier(0),
        citizenTab: tab,
        parkingSessionTarget: target,
      )..start();
      final greeting = HomeGreetingController(
        read: () async => {'firstName': 'Antonio'},
        isCurrentSession: () => true,
      );
      await Future.wait([push.onAuthenticated(), greeting.load()]);
      expect(greeting.name, 'Antonio');
      expect(tab.value, 0);
      expect(api.pushEnabled, isTrue);
      expect(push.registrationState, PushRegistrationState.registered);
      expect(runtime.prompts, 0);
      expect(await push.shouldShowOnboarding(), isFalse);
      await push.disablePushVoluntarily();
      await push.onResumed();
      expect(api.pushEnabled, isFalse);
      expect(runtime.presentationEnabled, isFalse);
      await push.logout(remote: true, ownerId: f.ownerA);
      greeting.invalidate();
      expect(greeting.name, isNull);
      expect(target.value, isNull);
      await push.dispose();
      await runtime.dispose();
      greeting.dispose();
    },
  );
  test(
    'all six explicit event taps preserve navigation and chronological focus',
    () async {
      final runtime = f.FakeRuntime();
      final api = f.FakeApi();
      final store = f.FakeStore()..consents.add(f.ownerA);
      final tab = ValueNotifier(0);
      final target = ValueNotifier<String?>(null);
      final push = PushDeviceLifecycle(
        runtime: runtime,
        api: api,
        store: store,
        isAuthenticated: () => true,
        ownerId: () => f.ownerA,
        unreadNotifications: ValueNotifier(0),
        citizenTab: tab,
        parkingSessionTarget: target,
      )..start();
      final gateway = OrderedGateway()
        ..values = [
          receipt(f.sessionId, 'ACTIVE', 1),
          receipt('00000000-0000-4000-8000-000000000009', 'ACTIVE', 5),
        ];
      final active = parking.controller(gateway);
      void focus() {
        if (target.value != null) active.prioritizeSession(target.value!);
      }

      target.addListener(focus);
      await active.load();
      await push.onAuthenticated();
      final events = [
        'VEHICLE_REGISTERED',
        'VEHICLE_DELETED',
        'PARKING_STARTED',
        'PARKING_ENDING_SOON',
        'PARKING_TIME_EXPIRED',
        'PARKING_EXTENSION_CONFIRMED',
      ];
      for (var i = 0; i < events.length; i++) {
        final vehicle = i < 2;
        runtime.opened.add(
          PushNavigationTarget(
            notificationId: '00000000-0000-4000-8000-00000000000${i + 1}',
            eventType: events[i],
            resourceType: vehicle ? 'VEHICLE' : 'PARKING_SESSION',
            resourceId: f.sessionId,
          ),
        );
        await Future<void>.delayed(Duration.zero);
        expect(tab.value, vehicle ? 3 : 0);
        if (vehicle) {
          expect(target.value, isNull);
        } else {
          expect(active.focusedSessionId, f.sessionId);
          expect(
            active.sessions.first.id,
            '00000000-0000-4000-8000-000000000009',
          );
        }
      }
      target.removeListener(focus);
      await push.dispose();
      await runtime.dispose();
      active.dispose();
    },
  );
}
