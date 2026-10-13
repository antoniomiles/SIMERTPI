import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/push/push_lifecycle.dart';

import 'push_lifecycle_test.dart'
    show FakeRuntime, FakeApi, FakeStore, ownerA, ownerB;

void main() {
  for (final scenario in [
    'resume',
    'offline',
    'unavailable',
    'revoked',
    'disabled',
    'account',
    'logout',
  ]) {
    test(
      'same-account revalidation and strict revocation: $scenario',
      () async {
        final runtime = FakeRuntime(), api = FakeApi(), store = FakeStore();
        var owner = ownerA;
        store.consents.add(ownerA);
        final push = PushDeviceLifecycle(
          runtime: runtime,
          api: api,
          store: store,
          isAuthenticated: () => true,
          ownerId: () => owner,
          unreadNotifications: ValueNotifier(0),
          citizenTab: ValueNotifier(0),
          parkingSessionTarget: ValueNotifier(null),
        );
        await push.onAuthenticated();
        expect(runtime.presentationEnabled, isTrue);
        final revocations = runtime.gateRevocations;
        switch (scenario) {
          case 'offline':
            api.failPreference = true;
          case 'unavailable':
            runtime.state = PushPermissionState.unavailable;
          case 'revoked':
            runtime.state = PushPermissionState.denied;
          case 'disabled':
            api.pushEnabled = false;
          case 'account':
            owner = ownerB;
        }
        if (scenario == 'logout') {
          await push.logout(remote: true, ownerId: ownerA);
        } else {
          await push.refreshPermission();
        }
        if (['resume', 'offline', 'unavailable'].contains(scenario)) {
          expect(runtime.presentationEnabled, isTrue);
          expect(runtime.gateRevocations, revocations);
          expect(runtime.deleted, isFalse);
        } else {
          expect(runtime.presentationEnabled, isFalse);
          expect(runtime.gateRevocations, greaterThan(revocations));
        }
        await push.dispose();
        await runtime.dispose();
      },
    );
  }
}
