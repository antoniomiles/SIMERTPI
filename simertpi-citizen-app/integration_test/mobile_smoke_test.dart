import 'dart:convert';
import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:simertpi_citizen_app/app/app.dart';
import 'package:simertpi_citizen_app/app/router/app_router.dart';
import 'package:simertpi_citizen_app/features/discovery/data/parking_catalog.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/auth/data/auth_service.dart';
import 'package:simertpi_citizen_app/features/auth/data/session_store.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';

// Controlled HTTP fixture on the emulator itself. No production accounts or APIs.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'Native storage, login, restore, Home, vehicles, form and logout',
    (tester) async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      const userId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
      var remoteLogouts = 0;
      var parkingPosts = 0;
      var parkingVehicles = true;
      const vehicleId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
      const tariffId = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
      const zoneId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
      const streetId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
      const realMapSmoke = bool.fromEnvironment('MAP_SMOKE_REAL_TILES');
      final space = {
        'id': 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        'streetId': streetId,
        'code': 'SMOKE-SPACE',
        'qrCode': 'SMOKE-QR',
        'spaceNumber': '1',
        'active': true,
        'latitude': realMapSmoke ? 51.5074 : null,
        'longitude': realMapSmoke ? -0.1278 : null,
      };
      server.listen((request) async {
        expectSync(
          request.headers.value(correlationHeader),
          matches(RegExp(r'^[A-Za-z0-9._:-]{1,128}$')),
        );
        if (request.uri.path == '/api/v1/auth/login') {
          expectSync(jsonDecode(await utf8.decoder.bind(request).join()), {
            'username': 'smoke-citizen',
            'password': 'smoke-password',
          });
          request.response.write(
            jsonEncode({
              'userId': userId,
              'tokenType': 'Bearer',
              'accessToken': 'A' * 43,
              'refreshToken': 'R' * 43,
              'accessExpiresAt': DateTime.now()
                  .toUtc()
                  .add(const Duration(minutes: 15))
                  .toIso8601String(),
              'refreshExpiresAt': DateTime.now()
                  .toUtc()
                  .add(const Duration(days: 1))
                  .toIso8601String(),
            }),
          );
        } else if (request.uri.path == '/api/v1/vehicles/user/$userId') {
          expectSync(
            request.headers.value('Authorization'),
            'Bearer ${'A' * 43}',
          );
          request.response.write(
            jsonEncode(
              parkingVehicles
                  ? [
                      {
                        'id': vehicleId,
                        'userId': userId,
                        'plate': 'SMOKE-123',
                        'active': true,
                      },
                    ]
                  : [],
            ),
          );
        } else if (request.uri.path == '/api/v1/parking/rules') {
          final minutes = int.tryParse(
            request.uri.queryParameters['durationMinutes'] ?? '',
          );
          expectSync(request.uri.queryParameters['spaceId'], space['id']);
          final now = DateTime.now().toUtc();
          request.response.write(
            jsonEncode({
              'spaceId': space['id'],
              'zoneId': zoneId,
              'evaluatedAt': now.toIso8601String(),
              'operational': true,
              'chargeable': true,
              'holiday': false,
              'reasonCode': 'RULES_RESOLVED',
              'minimumFractionMinutes': 15,
              'maximumContinuousMinutes': 180,
              'gracePeriodMinutes': 7,
              'currency': 'USD',
              'applicableTariff': 'SMOKE-TARIFF',
              'unitPrice': 1.23,
              'unitDurationMinutes': 60,
              'requestedDurationMinutes': minutes,
              'billedDurationMinutes': minutes == null ? null : 15,
              'calculatedAmount': minutes == null ? null : 0.31,
              'expiresAt': minutes == null
                  ? null
                  : now.add(Duration(minutes: minutes)).toIso8601String(),
              'applicableSchedule': {
                'startTime': '00:00:00',
                'endTime': '23:59:59',
                'source': 'ZONE',
              },
            }),
          );
        } else if (request.uri.path == '/api/v1/tariffs/code/SMOKE-TARIFF') {
          request.response.write(
            jsonEncode({
              'id': tariffId,
              'code': 'SMOKE-TARIFF',
              'active': true,
            }),
          );
        } else if (request.uri.path == '/api/v1/parking/sessions') {
          expectSync(request.method, 'POST');
          expectSync(
            request.headers.value('Idempotency-Key'),
            matches(RegExp(r'^[a-f0-9]{32}$')),
          );
          expectSync(jsonDecode(await utf8.decoder.bind(request).join()), {
            'parkingSpaceQrCode': 'SMOKE-QR',
            'vehicleId': vehicleId,
            'tariffId': tariffId,
            'durationMinutes': 15,
          });
          parkingPosts++;
          final now = DateTime.now().toUtc();
          request.response.statusCode = 201;
          request.response.write(
            jsonEncode({
              'id': '11111111-1111-4111-8111-111111111111',
              'userId': userId,
              'vehicleId': vehicleId,
              'parkingSpaceId': space['id'],
              'tariffId': tariffId,
              'status': 'PENDING_PAYMENT',
              'totalAmount': 0,
              'durationMinutes': 15,
              'startedAt': now.toIso8601String(),
              'expectedEndAt': now
                  .add(const Duration(minutes: 15))
                  .toIso8601String(),
            }),
          );
        } else if (request.uri.path == '/api/v1/zones') {
          request.response.write(
            jsonEncode([
              {
                'id': zoneId,
                'code': 'SMOKE-ZONE',
                'name': 'Zona fixture smoke',
                'active': true,
              },
            ]),
          );
        } else if (request.uri.path == '/api/v1/streets') {
          request.response.write(
            jsonEncode([
              {
                'id': streetId,
                'zoneId': zoneId,
                'name': 'Calle fixture smoke',
                'active': true,
              },
            ]),
          );
        } else if (request.uri.path == '/api/v1/parking-spaces') {
          request.response.write(jsonEncode([space]));
        } else if (request.uri.path ==
                '/api/v1/parking-spaces/code/SMOKE-SPACE' ||
            request.uri.path == '/api/v1/parking-spaces/qr/SMOKE-QR') {
          request.response.write(jsonEncode(space));
        } else if (request.uri.path == '/api/v1/auth/logout') {
          remoteLogouts++;
          request.response.statusCode = 204;
        } else {
          request.response.statusCode = 404;
        }
        await request.response.close();
      });
      final config = AppConfig.parse(
        environment: 'dev',
        apiBaseUrl: 'http://127.0.0.1:${server.port}/api/v1',
      );
      final store = SecureSessionStore('smoke:${server.port}');
      await store.clear();
      late AuthController auth;
      final api = ApiClient(
        baseUrl: config.apiBaseUrl!,
        headersProvider: () => auth.headers(),
        onUnauthorized: (header) => auth.recoverUnauthorized(header),
        onSessionRejected: () => auth.logout(expired: true, remote: false),
      );
      AuthController controller() =>
          AuthController(AuthService(api), store: store);
      auth = controller();
      Widget app(String key) => AppScope(
        config: config,
        api: api,
        auth: auth,
        child: SimertpiApp(key: ValueKey(key), auth: auth),
      );
      Future<void> visible(Finder target) async {
        final limit = DateTime.now().add(const Duration(seconds: 30));
        while (target.evaluate().isEmpty && DateTime.now().isBefore(limit)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expectSync(target, findsWidgets);
        await tester.pumpAndSettle();
      }

      try {
        await tester.pumpWidget(app('first'));
        await visible(find.text('Inicia sesión'));
        await tester.enterText(
          find.byType(TextFormField).at(0),
          'smoke-citizen',
        );
        await tester.enterText(
          find.byType(TextFormField).at(1),
          'smoke-password',
        );
        await tester.ensureVisible(find.text('INGRESAR'));
        await tester.tap(find.text('INGRESAR'));
        await visible(find.text('¿Dónde vas a estacionar?'));
        expectSync((await store.read())!.userId, userId);
        // Rebuild a fresh controller, using native persisted tokens rather than password.
        await tester.pumpWidget(const SizedBox.shrink());
        auth.dispose();
        auth = controller();
        await auth.restore();
        expectSync(auth.isAuthenticated, true);
        await tester.pumpWidget(app('restored'));
        await visible(find.text('¿Dónde vas a estacionar?'));
        await tester.ensureVisible(find.text('BUSCAR ESTACIONAMIENTO'));
        await tester.tap(find.text('BUSCAR ESTACIONAMIENTO'));
        await visible(find.text('Zona fixture smoke'));
        if (realMapSmoke) {
          await tester.ensureVisible(find.byType(FlutterMap));
          final deadline = DateTime.now().add(const Duration(seconds: 30));
          final rendered = find.descendant(
            of: find.byType(FlutterMap),
            matching: find.byWidgetPredicate(
              (w) => w is RawImage && w.image != null,
            ),
          );
          while (rendered.evaluate().isEmpty &&
              DateTime.now().isBefore(deadline)) {
            await tester.pump(const Duration(milliseconds: 100));
          }
          expectSync(rendered, findsWidgets);
          expectSync(
            find.textContaining('© OpenStreetMap contributors'),
            findsOneWidget,
          );
          expectSync(
            find.textContaining('No pudimos cargar la cartografía'),
            findsNothing,
          );
          await tester.tap(find.byTooltip('Espacio 1'));
        } else {
          await tester.ensureVisible(find.text('Ver espacio 1'));
          await tester.tap(find.text('Ver espacio 1'));
        }
        await visible(find.text('Detalle del espacio'));
        await tester.ensureVisible(find.text('Seleccionar espacio'));
        await tester.tap(find.text('Seleccionar espacio'));
        await visible(find.text('Espacio seleccionado'));
        await tester.ensureVisible(find.text('Continuar con este espacio'));
        await tester.tap(find.text('Continuar con este espacio'));
        await visible(find.text('SMOKE-123'));
        await tester.ensureVisible(find.text('Revisar resumen'));
        await tester.tap(find.text('Revisar resumen'));
        await visible(find.text('Resumen de la solicitud'));
        await tester.ensureVisible(find.text('Preparar solicitud'));
        await tester.tap(find.text('Preparar solicitud'));
        await visible(find.text('Solicitud pendiente de pago'));
        expectSync(parkingPosts, 1);
        await tester.binding.handlePopRoute();
        await visible(find.text('Espacio seleccionado'));
        await tester.binding.handlePopRoute();
        await visible(find.text('Buscar estacionamiento'));
        await tester.binding.handlePopRoute();
        await visible(find.text('¿Dónde vas a estacionar?'));
        await tester.ensureVisible(find.text('ESCANEAR QR'));
        await tester.tap(find.text('ESCANEAR QR'));
        await visible(find.text('Escanear QR'));
        expectSync(find.bySemanticsLabel('Área de escaneo QR'), findsOneWidget);
        await tester.binding.handlePopRoute();
        await visible(find.text('¿Dónde vas a estacionar?'));
        // Controlled QR lookup uses the same real API contract; no physical decode claim.
        final qrSpace = await ParkingCatalogService(api)
            .identify('SMOKE-QR', qr: true);
        unawaited(
          tester
              .state<NavigatorState>(find.byType(Navigator).first)
              .pushNamed<void>(AppRoute.space.path, arguments: qrSpace),
        );
        await visible(find.text('Detalle del espacio'));
        await tester.ensureVisible(find.text('Seleccionar espacio'));
        await tester.tap(find.text('Seleccionar espacio'));
        await visible(find.text('Espacio seleccionado'));
        await tester.ensureVisible(find.text('Continuar con este espacio'));
        await tester.tap(find.text('Continuar con este espacio'));
        await visible(find.text('SMOKE-123'));
        await tester.ensureVisible(find.text('Revisar resumen'));
        await tester.tap(find.text('Revisar resumen'));
        await visible(find.text('Resumen de la solicitud'));
        expectSync(
          parkingPosts,
          1,
        ); // Stop before any second operation or payment.
        await tester.binding.handlePopRoute();
        await visible(find.text('Espacio seleccionado'));
        await tester.binding.handlePopRoute();
        await visible(find.text('¿Dónde vas a estacionar?'));
        parkingVehicles = false;
        await tester.ensureVisible(find.text('Mis vehículos'));
        await tester.tap(find.text('Mis vehículos'));
        await visible(find.text('Agregar vehículo'));
        await visible(find.text('No tienes vehículos registrados.'));
        await tester.tap(find.text('Agregar vehículo'));
        await visible(find.text('Datos del vehículo'));
        await tester.tap(find.byType(TextFormField).first);
        await tester.enterText(find.byType(TextFormField).first, 'smoke');
        await tester.pumpAndSettle();
        expectSync(tester.takeException(), isNull);
        await tester.binding.handlePopRoute();
        await visible(find.text('Agregar vehículo'));
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Cerrar sesión'));
        await visible(find.text('Inicia sesión'));
        // Wait for native deletion and remote logout to finish.
        await auth.logout(remote: false);
        final limit = DateTime.now().add(const Duration(seconds: 10));
        while (remoteLogouts == 0 && DateTime.now().isBefore(limit)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expectSync(remoteLogouts, 1);
        expectSync(await store.read(), isNull);
        expectSync(tester.takeException(), isNull);
      } finally {
        await store.clear();
        api.close();
        auth.dispose();
        await server.close(force: true);
      }
    },
  );
}
