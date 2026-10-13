# CP19.1 - Correcciones integrales PUSH (2026-10-13)

Implementado localmente en fix/cp19.1-vehicle-push-20261013, base 37bb3e29c2d16a13353aa103dcd875c45701ac60. Sin commit, push, merge, despliegue ni APK. Render DEV, PostgreSQL DEV, credenciales y workspace original no se modificaron.

## Cambios

- Firebase acknowledgement tratado como identificador opaco. Identificadores largos o con controles conservan fingerprint SHA-256 dentro del limite de almacenamiento existente. Ausencia de acknowledgement sigue UNKNOWN. No se reenvian historicos UNKNOWN automaticamente.
- Dispatcher conserva exclusivamente codigos tecnicos permitidos FCM_RESULT_INVALID, FCM_TOKEN_INVALID, FCM_TEMPORARY_FAILURE y FCM_PERMANENT_FAILURE. Audit distingue PROVIDER_ACCEPTED, PROVIDER_REJECTED y UNCONFIRMED, siempre androidReception=NOT_VERIFIED. DELIVERED sigue siendo aceptacion del proveedor, no prueba de visualizacion.
- Receptor FlutterFire protegido conserva el reenvio Flutter existente y comparte NativePushPresenter con el servicio nativo. Presentador sincronizado y deduplicacion persistida por propietario/notificacion evitan dos avisos por las dos rutas. Mantiene TTL, contrato vigente, aislamiento y navegacion autenticada.
- Diagnosticos de vocabulario fijo: RECEIVED, ACCOUNT_GATE_BLOCKED, PERMISSION_BLOCKED, CHANNEL_BLOCKED, EXPIRED, DUPLICATE, STALE_CONTRACT, UNSUPPORTED_CONTENT, PRESENTATION_REQUESTED, PRESENTATION_FAILED y DEDUP_STORE_FAILED. Sin tokens, placas, propietarios ni contenido. Logs SIMERTPI_PUSH y contadores locales limitados; MethodChannel pushDiagnostics permite lectura tecnica sin secretos. No es un endpoint remoto ni un control visible nuevo de Perfil.
- Canal simertpi_updates comprobado; icono monocromatico dedicado. PRESENTATION_REQUESTED solo acredita retorno de notify, no percepcion del usuario.
- Revalidacion del mismo propietario conserva autorizacion confirmada ante fallos transitorios. Logout, cambio de cuenta, permiso revocado y preferencia OFF cierran la puerta nativa. Reabrir la misma identidad confirmada no borra preferencias ni avisos.
- Perfil expone un unico interruptor Permitir notificaciones. ON requiere permiso, preferencia y registro confirmado. Guarda automaticamente solo el cambio PUSH conservando otros valores del backend; no toca preferencias IN_APP. Ajustes y regreso actualizan estado; fallos muestran activacion pendiente. Firebase ausente no muestra funcionamiento exitoso.

## Eventos y migracion

Conservados VEHICLE_REGISTERED, VEHICLE_DELETED, PARKING_STARTED, PARKING_ENDING_SOON, PARKING_TIME_EXPIRED y PARKING_EXTENSION_CONFIRMED. Tambien se conserva PARKING_COMPLETED existente. No se crean aliases ni duplicados.

V40__vehicle_push_notifications.sql intacta, SHA-256 cc78ed29cde8b5e3817a72c5a91a3ba59291b088ca24ad3774ee2ad0aeeb1a75. No se necesita otra migracion. VehicleService, VehicleEventPublisher y NotificationOutboxProcessor mantienen hash previo a esta correccion. La campanita no recibe nuevas proyecciones ni cambios de contratos.

## Validacion focalizada

64 casos unicos PASS: backend 18, Flutter 28, Android JVM 18. Sin suites completas. Se revalidaron solamente contratos modificados y los casos afectados por incidencias locales. Detalles, incidentes y hashes en CP19_1_PUSH_RECOVERY_VALIDATION_20261013.json.

Comandos principales:

```text
mvn -q -Dmaven.repo.local=C:/Users/antox/.m2/repository -Dtest=FcmPushNotificationProviderTest,ProviderDeliveryEvidenceTest,VehiclePushIntegrationTest test
mvn -q -Dmaven.repo.local=C:/Users/antox/.m2/repository -Dtest=VehiclePushIntegrationTest test
flutter test test/push_revalidation_test.dart test/push_preferences_test.dart test/push_lifecycle_test.dart --reporter expanded
flutter test test/push_preferences_test.dart test/citizen_activity_test.dart --no-pub --name "(one switch|single push switch|Preferences exposes)" --reporter expanded
./gradlew.bat :app:testDebugUnitTest --tests "*PushPresentationPolicyTest" --tests "*PushPresentationChecksTest" :app:processReleaseMainManifest
```

Flutter tambien se ejecuto directamente con el flutter_tools.snapshot instalado. Analisis focalizado de los tres archivos productivos Flutter y dos archivos nuevos/modificados de pruebas: No issues found. git diff --check PASS. Manifiesto release combinado revisado; no se ensamblaron APK. PostgreSQL16.15 desechable aplico V1-V35,V37-V40 con FCM simulado. Primera ejecucion de integracion tuvo timeout de infraestructura; reintento de ocho casos PASS.

Advertencias existentes de Gradle/Kotlin y Commons Logging no bloquean estas validaciones; no se cambiaron dependencias para ocultarlas.

## Evidencia fisica pendiente (requiere autorizacion separada para integrar/desplegar/APK)

1. Usar backend y APK compatibles con CP19.1/V40, Firebase DEV existente y cuenta de prueba propia. Transferencia manual al telefono; no requiere USB ni ADB.
2. Comprobar interruptor ON con permiso y canal habilitados. Ejecutar registro/eliminacion de vehiculo propios y las cuatro operaciones de estacionamiento con pagos de prueba aprobados. No tocar las dos sesiones EXPIRED retenidas.
3. Para cada evento, registrar hora y estado de aplicacion (primer plano, segundo plano, cierre normal, pantalla bloqueada), entrega backend/resultado FCM y captura visible de Android. Aceptacion FCM y solicitud notify no sustituyen esa captura.
4. Correlacionar contadores/rutas locales si se dispone de instrumentacion de soporte; no hay exportacion visible en Perfil en esta entrega. Sin esa lectura, una captura acredita visualizacion pero la ausencia no identifica por si sola la ruta de descarte.
5. Verificar un solo aviso por evento, toque autenticado correcto, OFF sin avisos nuevos y aislamiento al cambiar cuenta/logout. Bloquear canal/permiso y comprobar mensaje de Ajustes; permitir de nuevo y revalidar.
6. Proximo vencimiento requiere una sesion elegible del propietario registrado. Extender antes del vencimiento y comprobar descarte de avisos del contrato anterior. No reenviar automaticamente UNKNOWN historicos para probar.

No se acredita FCM real, recepcion ni visualizacion fisica en esta validacion local. Ahorro de bateria, falta de red y detencion forzada siguen condicionados por Android/FCM.

## Archivos de esta correccion

- `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/NotificationDispatcher.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/ProviderDeliveryEvidence.java`
- `simertpi-backend/src/main/java/ec/gob/simertpi/infrastructure/notifications/FcmPushNotificationProvider.java`
- `simertpi-backend/src/test/java/ec/gob/simertpi/api/vehicles/VehiclePushIntegrationTest.java`
- `simertpi-backend/src/test/java/ec/gob/simertpi/application/notifications/FcmPushNotificationProviderTest.java`
- `simertpi-backend/src/test/java/ec/gob/simertpi/application/notifications/ProviderDeliveryEvidenceTest.java`
- `simertpi-citizen-app/android/app/src/main/AndroidManifest.xml`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/GuardedFirebaseMessagingService.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/GuardedFlutterFirebaseMessagingReceiver.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/MainActivity.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushDiagnostics.kt`
- `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/SimertpiPushGate.kt`
- `simertpi-citizen-app/android/app/src/main/res/drawable/ic_notification.xml`
- `simertpi-citizen-app/android/app/src/test/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushPresentationChecksTest.kt`
- `simertpi-citizen-app/lib/core/push/firebase_push_runtime.dart`
- `simertpi-citizen-app/lib/core/push/push_lifecycle.dart`
- `simertpi-citizen-app/lib/features/citizen_activity/profile_page.dart`
- `simertpi-citizen-app/test/citizen_activity_test.dart`
- `simertpi-citizen-app/test/push_lifecycle_test.dart`
- `simertpi-citizen-app/test/push_preferences_test.dart`
- `simertpi-citizen-app/test/push_revalidation_test.dart`
