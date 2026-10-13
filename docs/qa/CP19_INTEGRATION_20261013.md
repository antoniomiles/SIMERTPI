# Integracion local CP19.1 + CP19.2 - 2026-10-13

Copia: `C:\Windows\Temp\simertpi-cp191-cp192-integration-20261013`
Rama: `integration/cp19.1-cp19.2-dev-20261013`
Base remota comprobada: `37bb3e29c2d16a13353aa103dcd875c45701ac60`. Sin commits nuevos.

## Alcance y conservacion

46 archivos de origen integrados. CP19.1 se incorporo primero; CP19.2 despues. Los archivos no compartidos coinciden byte por byte con sus origenes. Se conservaron documentos y evidencias historicas; sus resultados anteriores no se presentan como pruebas nuevas.

Se verificaron SHA-256 de 616 archivos CP19.1 y 605 CP19.2: ninguna diferencia respecto al inventario anterior. Workspace original sin escrituras. Inventario de preservacion externo: `C:\Windows\Temp\simertpi-cp191-cp192-origin-evidence-20261013`.

## Solapamientos resueltos por fragmentos

- `home_page.dart`: onboarding de CP19.1 conservado; saludo, control de sesion, revision de callbacks y manejo de pesta?as/objetivo de CP19.2 incorporados.
- `push_lifecycle.dart`: preferencias, permiso inicial, registro, OFF voluntario y revocacion de CP19.1 conservados. Navegacion de CP19.2 incorporada: null no navega, propietario/revision validan callbacks, logout limpia objetivos. Vehiculos navegan a Perfil y estacionamientos a Home.
- `push_lifecycle_test.dart`: fakes y pruebas CP19.1 conservados; payload ausente/invalido deja pesta?a actual, conforme a CP19.2.
- `bootstrap.dart` no presento solapamiento: cambio CP19.2 conservado byte por byte.

## Validacion focalizada

- Flutter: 44 casos reutilizados pasaron en la copia integrada. El proceso fue interrumpido por espera de limpieza de un nuevo fixture widget; no se declara ese proceso completo como PASS. Se sustituyo exclusivamente el fixture nuevo por dos pruebas de controladores integrados, conservando las pruebas de interfaz reutilizadas. Dos pruebas nuevas de controladores: PASS, comando final exit 0. Total Flutter: 46 PASS.
- Backend: `mvn -q -Dmaven.repo.local=C:\Users\antox\.m2\repository -Dtest=VehiclePushIntegrationTest test`: 8 PASS, 0 fallos, 0 errores, 0 omitidos. FCM simulado en la frontera del proveedor; no envio real.
- Android: Gradle `:app:testDebugUnitTest --tests *PushPresentationPolicyTest --tests *PushPresentationChecksTest :app:processReleaseMainManifest`: 18 PASS. Manifiesto release con servicio y receptor protegidos; receptor original Flutter reemplazado. No se ensamblo APK.
- Dart analyze: archivos Flutter integrados, sin incidencias. Fixture final: PASS, sin incidencias estaticas.
- `git diff --check`: PASS; avisos CRLF sin errores de whitespace.

## Migracion

`V40__vehicle_push_notifications.sql` intacta. SHA-256: `cc78ed29cde8b5e3817a72c5a91a3ba59291b088ca24ad3774ee2ad0aeeb1a75`. Flyway en PostgreSQL 16.15 aislado aplico V1-V35 y V37-V40: 39 migraciones exitosas, esquema v40; V36 ausente. No se consulto ni modifico DEV.

## Riesgos y proximo paso

Recepcion y visualizacion FCM en Android fisico siguen pendientes: primer plano, segundo plano, cierre normal, canal bloqueado, TTL, deduplicacion y cambio de cuenta. Aceptacion FCM no acredita presentacion Android. La evidencia automatizada nativa comprueba politica y condiciones, no un telefono real.

Antes de publicar: revalidar origin/develop y disponibilidad V40; revisar diff integrado y autorizar commit/publicacion. No se modificaron pagos, reglas de estacionamiento, campanita, credenciales Firebase ni datos/EXPIRED de DEV. No commit, push, merge, despliegue ni APK.

## Archivos integrados

- `simertpi-backend/docs/CP19_1_INITIAL_PERMISSION_SYNC_20261013.md`
- `simertpi-backend/docs/CP19_1_INITIAL_PERMISSION_SYNC_VALIDATION_20261013.json`
- `simertpi-backend/docs/CP19_1_PUSH_RECOVERY_20261013.md`
- `simertpi-backend/docs/CP19_1_PUSH_RECOVERY_VALIDATION_20261013.json`
- `simertpi-backend/docs/CP19_1_VEHICLE_PUSH_20261013.md`
- `simertpi-backend/docs/CP19_1_VEHICLE_PUSH_VALIDATION_20261013.json`
- `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/NotificationDispatcher.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/NotificationOutboxProcessor.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/ProviderDeliveryEvidence.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/application/vehicles/VehicleEventPublisher.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/application/vehicles/VehicleService.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/infrastructure/notifications/FcmPushNotificationProvider.java`
- `simertpi-backend/src/main/resources/db/migration/V40__vehicle_push_notifications.sql`
- `simertpi-backend/src/test/java/ec/gob/simertpi/api/vehicles/VehiclePushIntegrationTest.java`
- `simertpi-backend/src/test/java/ec/gob/simertpi/application/notifications/FcmPushNotificationProviderTest.java`
- `simertpi-backend/src/test/java/ec/gob/simertpi/application/notifications/ProviderDeliveryEvidenceTest.java`
- `simertpi-backend/src/test/java/ec/gob/simertpi/testsupport/VehiclePushMigrationIntegrationTest.java`
- `simertpi-citizen-app/android/app/src/main/AndroidManifest.xml`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/GuardedFirebaseMessagingService.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/GuardedFlutterFirebaseMessagingReceiver.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/MainActivity.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushDiagnostics.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/SimertpiPushGate.kt`
- `simertpi-citizen-app/android/app/src/main/res/drawable/ic_notification.xml`
- `simertpi-citizen-app/android/app/src/test/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushPresentationChecksTest.kt`
- `simertpi-citizen-app/android/app/src/test/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushPresentationPolicyTest.kt`
- `simertpi-citizen-app/docs/CP19_2_HOME_IMPLEMENTATION_20261013.md`
- `simertpi-citizen-app/docs/CP19_2_HOME_VALIDATION_20261013.json`
- `simertpi-citizen-app/lib/app/bootstrap/bootstrap.dart`
- `simertpi-citizen-app/lib/core/push/firebase_push_runtime.dart`
- `simertpi-citizen-app/lib/core/push/push_lifecycle.dart`
- `simertpi-citizen-app/lib/features/active_parking/presentation/active_parking_panel.dart`
- `simertpi-citizen-app/lib/features/active_parking/state/active_parking_controller.dart`
- `simertpi-citizen-app/lib/features/citizen_activity/profile_page.dart`
- `simertpi-citizen-app/lib/features/home/home_greeting_controller.dart`
- `simertpi-citizen-app/lib/features/home/home_page.dart`
- `simertpi-citizen-app/test/citizen_activity_test.dart`
- `simertpi-citizen-app/test/cp192_greeting_test.dart`
- `simertpi-citizen-app/test/cp192_home_support.dart`
- `simertpi-citizen-app/test/cp192_navigation_test.dart`
- `simertpi-citizen-app/test/cp192_parking_order_test.dart`
- `simertpi-citizen-app/test/push_initial_permission_sync_test.dart`
- `simertpi-citizen-app/test/push_lifecycle_test.dart`
- `simertpi-citizen-app/test/push_onboarding_home_test.dart`
- `simertpi-citizen-app/test/push_preferences_test.dart`
- `simertpi-citizen-app/test/push_revalidation_test.dart`

Nuevo caso focalizado: `simertpi-citizen-app/test/cp19_combined_integration_test.dart`.
Excluido: GeneratedPluginRegistrant.java generado/ignorado; no codigo funcional recuperado perdido.

## Resultado final

72 pruebas focalizadas PASS: Flutter 46, Android JVM 18, backend 8. Ningun fallo en las ejecuciones finales. Los intentos iniciales del fixture nuevo y su correccion se registran en el JSON, sin atribuirlos a fallos del producto. Copias originales nuevamente verificadas: 1221 archivos sin diferencias. Cambios preparados para revision local; no publicados.
