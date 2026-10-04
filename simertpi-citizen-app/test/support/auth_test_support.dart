import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:simertpi_citizen_app/app/app.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';

class FakeAuthGateway implements AuthGateway {
  int calls = 0, registrations = 0, refreshes = 0, logouts = 0;
  Completer<void>? pending;
  Object? failure;
  RegistrationRequest? registered;
  @override
  Future<MobileSession> login(String username, String password) async {
    calls++;
    if (pending != null) await pending!.future;
    if (failure != null) throw failure!;
    return fixtureSession(access: calls == 1 ? 'A' : 'B');
  }

  @override
  Future<MobileSession> refresh(String token) async {
    refreshes++;
    if (pending != null) await pending!.future;
    if (failure != null) throw failure!;
    return fixtureSession(access: 'C');
  }

  @override
  Future<void> logout(String token) async {
    logouts++;
  }

  @override
  Future<void> register(RegistrationRequest request) async {
    registrations++;
    registered = request;
    if (pending != null) await pending!.future;
    if (failure != null) throw failure!;
  }
}

Widget authTestApp(
  AuthController auth, {
  AppRoute route = AppRoute.login,
  Key? key,
}) => AppScope(
  config: AppConfig.parse(environment: 'dev'),
  auth: auth,
  child: SimertpiApp(key: key, auth: auth, initialRoute: route.path),
);
Future<Widget> citizenApp({AppRoute route = AppRoute.home, Key? key}) async {
  final auth = AuthController(FakeAuthGateway(), store: MemorySessionStore());
  await auth.login('fixture-citizen', 'fixture-password');
  return authTestApp(auth, route: route, key: key);
}

MobileSession fixtureSession({
  String access = 'A',
  DateTime? accessExpiry,
  DateTime? refreshExpiry,
}) => MobileSession(
  userId: 'fixture-citizen',
  accessToken: access * 43,
  refreshToken: 'R' * 43,
  accessExpiresAt:
      accessExpiry ?? DateTime.now().toUtc().add(const Duration(minutes: 15)),
  refreshExpiresAt:
      refreshExpiry ?? DateTime.now().toUtc().add(const Duration(days: 30)),
);

class MemorySessionStore implements SessionStore {
  MobileSession? session;
  @override
  Future<MobileSession?> read() async => session;
  @override
  Future<void> write(MobileSession value) async {
    session = value;
  }

  @override
  Future<void> clear() async {
    session = null;
  }
}
