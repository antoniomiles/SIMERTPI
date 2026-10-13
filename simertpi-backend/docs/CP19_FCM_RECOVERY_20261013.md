# CP19 - Recuperacion FCM y contenido por operacion

Base: develop `199de4cbba63f01b5de36d8c67a71e59064f4a85`.
Rama local: `fix/cp19-fcm-recovery-20261013`.
Estado: implementado para revision; sin commit, push, despliegue ni APK.

## Cambios

- `application/notifications/ParkingPushDetails.java`: proyeccion de solo lectura
  con placa y espacio propios, fecha/hora Ecuador, contrato inicial y pago exacto
  del evento outbox. Extensiones se resuelven por payment_id, nunca por la ultima
  extension de la sesion. Operaciones no verificables o no aprobadas se suprimen.
- `NotificationDispatcher.java`: delega el contenido, suprime vencimientos de
  contratos anteriores y eventos de exito sin confirmacion verificable.
- `FirebaseAdminMessagingClient.java`: mantiene data-only, prioridad HIGH y TTL
  limitado por validUntil. Recordatorios: hasta el vencimiento contractual;
  tiempo finalizado: 30 minutos tras ese vencimiento; confirmaciones: 6 horas
  desde la fecha del evento, sin ampliar la ventana al reintentar.
- `V39__parking_ending_soon_push.sql`: agrega exclusivamente la regla PUSH
  opcional faltante. No cambia las reglas existentes, los 10 minutos efectivos
  configurables ni migraciones historicas. V36 permanece ausente.
- `GuardedFirebaseMessagingService.kt`: texto especifico, privacidad en pantalla
  bloqueada, deduplicacion persistente limitada por cuenta/notificacion y rechazo
  por validUntil. Si una ampliacion ya recibida avanza el contrato, se descartan
  recordatorios/vencimientos del contrato anterior. Se conserva el receptor
  broadcast FlutterFire que entrega onMessage al contador existente.
- `push_lifecycle.dart`: expone estado observable de registro. Permiso por si
  solo no equivale a registro; conserva consentimiento, rotacion y logout.
- `profile_page.dart`: muestra permiso Android incluso sin runtime Firebase;
  diferencia configuracion, registro, error y preferencia. No afirma entrega
  fisica validada cuando solo existe permiso o registro.
- `firebase_push_runtime.dart`, `android/app/build.gradle.kts`: FCM_REQUIRED
  rechaza configuraciones Android incompletas en compilacion y en runtime.
- `scripts/build-dev-push.ps1`: utiliza solo google-services.json existente,
  verifica paquete y proyecto simertpi-dev y aporta los cuatro parametros por
  dart-define-from-file. Nunca lee la credencial Admin.

## Procedimiento seguro de compilacion (pendiente de autorizacion)

Desde simertpi-citizen-app:

```powershell
.\scripts\build-dev-push.ps1 -ValidateOnly
# Ejecutar solo cuando se autorice generar la APK:
.\scripts\build-dev-push.ps1
```

El archivo por defecto es Downloads/google-services.json. Se puede pasar otra
ruta mediante -FirebaseConfig; nunca una clave Admin. El script usa un archivo
transitorio de configuracion publica y lo elimina al terminar. No registra
valores. La clave privada no se copia al repositorio ni se incorpora a la APK.

Una compilacion ordinaria sin FCM_REQUIRED sigue disponible para otros usos,
pero no debe etiquetarse apta para push. La compilacion DEV push falla si falta
cualquiera de FCM_FIREBASE_API_KEY, FCM_FIREBASE_APP_ID,
FCM_FIREBASE_SENDER_ID o FCM_FIREBASE_PROJECT_ID.

## Configuracion externa pendiente

Verificar sin exponer secretos en Render DEV:
- SIMERTPI_NOTIFICATION_FCM_ENABLED=true.
- SIMERTPI_NOTIFICATION_PUSH_PROVIDER=FCM.
- SIMERTPI_FCM_PROJECT_ID=simertpi-dev.
- ADC/GOOGLE_APPLICATION_CREDENTIALS apunta al archivo secreto correcto, con
  identidad autorizada para enviar en ese proyecto y API HTTP v1 habilitada.
- Dispatcher/outbox activos, preferencias PUSH y dispositivo activo tras login.

No se modifico Render ni PostgreSQL DEV. La presencia del archivo Admin local
no acredita la validez actual de su clave o de los permisos del servidor.

## Validaciones focalizadas

Resultados definitivos se registran en CP19_FCM_VALIDATION_20261013.json.
Pruebas nuevas: operacion exacta con multiples extensiones, contrato inicial,
fechas Ecuador, pagos no aprobados, TTL decreciente, supresion tras extension,
upgrade V38->V39, permiso separado de Firebase y registro fallido.
Se reutilizan pruebas existentes de proveedor, payload, recordatorios,
consentimiento, rotacion, logout, aislamiento y navegacion.

Comandos:
- Maven: ParkingPushDetailsTest, FirebasePushTtlTest,
  NotificationDispatcherReminderValidityTest, NotificationDispatcherPushPayloadTest,
  FcmPushNotificationProviderTest, NotificationReminderSchedulerTest,
  PushReminderMigrationIntegrationTest (PostgreSQL 16.15 Testcontainers aislado).
- Flutter: push_lifecycle_test.dart, push_onboarding_home_test.dart,
  push_preferences_test.dart.
- Analisis Flutter focalizado: lib/core/push y profile_page.dart.
- Gradle: :app:testDebugUnitTest --tests '*PushPresentationPolicyTest'.
- Gradle help con FCM_REQUIRED sin parametros: rechazo esperado, sin APK.
- Script -ValidateOnly con archivo existente: PASS sin imprimir valores.
- git diff --check y revision de archivos/secretos.

## Limites y pruebas fisicas pendientes

DELIVERED acredita aceptacion FCM, no recepcion ni presentacion Android.
TTL y cache nativa reducen mensajes obsoletos; un telefono que no recibio una
extension tampoco conoce ese contrato nuevo. No se garantiza entrega exacta,
inmediata ni ausencia absoluta de duplicados. La cache es acotada.
Un equipo detenido forzosamente necesita reabrir la app. Render free puede
suspender el backend y retrasar schedulers: no se promete el aviso exactamente
10 minutos antes mientras el servicio este dormido.

Tras autorizacion independiente, verificar APK y backend compatibles y probar
primer plano, segundo plano, cierre normal, pantalla bloqueada, reconexion,
toque autenticado, varias extensiones, logout y cambio de cuenta. La APK se
transferira manualmente; no se exige USB/ADB. No se afirma prueba fisica realizada.

Las dos sesiones EXPIRED, las reglas de estacionamiento, los pagos y el inbox
interno permanecen intactos. La migracion solo se probo en PostgreSQL aislado.
