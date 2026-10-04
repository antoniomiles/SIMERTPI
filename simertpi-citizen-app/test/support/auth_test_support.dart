import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:simertpi_citizen_app/app/app.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

class FakeAuthGateway implements AuthGateway {
  int calls = 0, registrations = 0;
  Completer<void>? pending;
  Object? failure;
  RegistrationRequest? registered;
  @override
  Future<void> verifyCitizen(String username, String password) async {
    calls++;
    if (pending != null) await pending!.future;
    if (failure != null) throw failure!;
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
  final auth = AuthController(FakeAuthGateway());
  await auth.login('fixture-citizen', 'fixture-password');
  return authTestApp(auth, route: route, key: key);
}
