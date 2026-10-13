# Revision de cambios locales SIMERTPI

Base: `29b61a38ff4ea334323f589be37842c10a68b998` (`origin/develop`, PR #1 ya integrado).

Rama: `review/pending-simertpi-20261013`. La combinacion completa del candidato es la unidad validada; los commits tematicos forman una misma entrega y no deben desplegarse por separado.

## Proteccion y alcance

Workspace original en develop/379b820, conservado sin checkout, pull, reset, clean ni descarte. Inventario inicial: 85 archivos tracked indicados por status (83 con diferencias efectivas), 83 archivos no rastreados, 0 eliminados y 0 staged. Copia local de todos los archivos pendientes y parches staged/unstaged conservada fuera del repositorio.

No se modifican Render ni PostgreSQL DEV. No se crea un APK de entrega. V1-V35 y V37 permanecen intactas. No se duplica la biometria backend integrada.

## Modulos incluidos

- Checkout inicial y liberacion de rechazos definitivos; conciliacion conserva retenciones inciertas; abandono seguro por propietario y limpieza de checkout sin pago.
- Contexto de estacionamiento en notificaciones, sin cambios en entrega o preferencias.
- Flutter: identidad visual y recursos aprobados de Pinas, Poppins con OFL, mapa, navegacion de estacionamiento y pago DEV.
- Flutter/Android: sesiones, refresh coordinado, bloqueo de cinco minutos, Keystore, invitacion por instalacion, bienvenida y diagnostico exclusivo debug DEV.
- Ciclo de vida y aislamiento de notificaciones por cuenta.

Flujo conservado: Espacio -> Vehiculo -> Minutos -> Confirmar datos -> Metodo DEV -> Resumen final -> Pagar -> Procesamiento -> Resultado. Abrir metodos o resumen prepara un borrador local; solo Pagar inicia checkout. Se conserva idempotencia y no se considera confirmado un estacionamiento sin pago aprobado y ACTIVE.

## Pendiente B: reingreso configurable

V36 local aun no publicada antecede a la V37 ya aplicada en DEV. Se excluye el modulo completo, sin renumerar migraciones ni activar outOfOrder/repair. Requiere decidir una migracion posterior compatible y probar upgrade desde V35+V37; revisar tambien otros entornos antes de renumerar una migracion. Se excluyen los fragmentos de reingreso en SecurityConfig y ParkingSessionCreationHttpIntegrationTest; se conservan sus fragmentos de abandono.

## Validacion

| Comando | PASS | FAIL | SKIPPED |
| --- | --- | --- | --- |
| mvn --batch-mode verify (compilacion + PostgreSQL 16.15 Testcontainers) | 523 | 0 | 0 |
| flutter analyze | Sin problemas | 0 | - |
| flutter test --reporter json | 483 | 0 | 1 |
| gradlew.bat :app:testDebugUnitTest --no-daemon | 7 | 0 | 0 |

Maven BUILD SUCCESS; Flyway valida 36 migraciones V1-V35 + V37 en PostgreSQL aislado. La captura visual optativa es el unico test omitido. No se conectaron estas pruebas a PostgreSQL DEV. git diff --check paso sobre el candidato y se ejecuta tambien antes de cada commit.

Los intentos iniciales dentro del sandbox no pudieron acceder a cache Maven/Docker; se reiniciaron con acceso autorizado. Los resultados de la tabla corresponden a esas ejecuciones completas exitosas, no a intentos bloqueados. Se verificaron los hashes de los 168 archivos originales, sin cambios.

## Archivos protegidos

- payment_contract.dart SHA256: D5ABD2860E86DFCDCF9112FEC5A23E371287FD1C425E63DD3A004993902BEF97.
- payment_intent_store.dart SHA256: 082259BB963F8CBED334C8AC0CD98E9BDA0EA364A624E13CF9A6E237728731BD.

## Inventario A/B/C/D

A: incluido tras validaciones. B: requiere trabajo adicional, no publicado. C: evidencia generada o referencia local, no publicada. D: ya integrado o sin diferencias efectivas.

| Archivo original | Clase | Observacion |
| --- | --- | --- |
| `simertpi-backend/docs/CP24.1.3_PARKING_REENTRY_AND_CITIZEN_MAP.md` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/GlobalExceptionHandler.java` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/ParkingConflictException.java` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/identity/DeviceCredentialController.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/ParkingReentryAdminController.java` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/ParkingSessionController.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/identity/auth/DeviceCredentialService.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/identity/auth/MobileAuthService.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/NotificationDispatcher.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/AbandonUnpaidCheckoutService.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingReentryConfigurationService.java` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingSessionService.java` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/UnstartedCheckoutCleanupScheduler.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/payments/PaymentIntegrationService.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/payments/PaymentService.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/payments/PendingPaymentExpiryScheduler.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/reconciliation/PaymentReconciliationService.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/main/java/ec/gob/simertpi/config/SecurityConfig.java` | A | Partial: checkout changes included; reentry changes deferred |
| `simertpi-backend/src/main/resources/db/migration/V36__configurable_parking_reentry.sql` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/main/resources/db/migration/V37__device_biometric_credentials.sql` | D | Already integrated in origin/develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/notifications/NotificationProviderHttpIntegrationTest.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/operations/OperationalHttpIntegrationTest.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java` | A | Partial: checkout changes included; reentry changes deferred |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/payments/OperationalRecoveryPostgresIntegrationTest.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/payments/PaymentCreationHttpPostgresIntegrationTest.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/payments/PaymentProviderHttpIntegrationTest.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/security/DeviceCredentialPostgresIntegrationTest.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/security/HttpBasicSecurityTest.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/identity/auth/DeviceCredentialCryptoTest.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/AbandonUnpaidCheckoutServiceTest.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/ParkingSessionServiceTest.java` | B | Deferred: reentry feature depends on unapplied V36 preceding already deployed V37 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/payments/PaymentServiceTest.java` | A | Included; current isolated candidate passed validation |
| `simertpi-backend/src/test/java/ec/gob/simertpi/testsupport/CitizenInboxMigrationIntegrationTest.java` | D | Already integrated in origin/develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/testsupport/PostgresInfrastructureIntegrationTest.java` | B | Excluded count change depended on deferred V36; retain current develop migration assertions |
| `simertpi-citizen-app/CP24.5.2_BASELINE.json` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.2_INFORME.md` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.2_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_AUTH_FINAL_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_BASELINE.json` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_BASELINE_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_DIAG2_BASELINE.json` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_DIAG2_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_DIAG3_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_INFORME.md` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_TARGET_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5.3_VISUAL_TESTS.jsonl` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/CP24.5_DIAGNOSTICO.md` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/android/app/build.gradle.kts` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/AndroidManifest.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/DeviceCredentialChannel.kt` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/DeviceSecurityChannel.kt` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/GuardedFirebaseMessagingService.kt` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/MainActivity.kt` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-hdpi/simertpi_splash_icon.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-mdpi/simertpi_splash_icon.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-v21/launch_background.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-xhdpi/simertpi_splash_icon.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-xxhdpi/simertpi_splash_icon.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable-xxxhdpi/simertpi_splash_icon.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/drawable/launch_background.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-hdpi/ic_launcher.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-mdpi/ic_launcher.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xhdpi/ic_launcher.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/values-night-v31/styles.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/values-night/colors.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/values-night/styles.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/values-v31/styles.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/values/colors.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/main/res/values/styles.xml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/android/app/src/test/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushPresentationPolicyTest.kt` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/branding/SIMERTPI_logotipo_blanco.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/branding/SIMERTPI_logotipo_negro.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/branding/simertpi_icono.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/branding/simertpi_logo_blanco.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/branding/simertpi_logo_principal.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/fonts/OFL.txt` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/fonts/Poppins-Medium.ttf` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/fonts/Poppins-Regular.ttf` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/fonts/Poppins-SemiBold.ttf` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/welcome/simertpidia.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/assets/welcome/simertpinoche.png` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/desing/references/GuÃ­a de marca y pantallas SIMERTPI.png` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/desing/references/SIMERTPI_logotipo_blanco.png` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/desing/references/SIMERTPI_logotipo_negro.png` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/desing/references/simertpi_icono.png` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/desing/references/simertpidia.png` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/desing/references/simertpinoche.png` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/desing/references/xxx.jpeg` | C | Local checkpoint evidence or design reference; not product source |
| `simertpi-citizen-app/lib/app/app.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/app/bootstrap/bootstrap.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/app/router/app_router.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/errors/app_failure.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/logging/app_log.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/logging/biometric_diagnostic.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/network/api_client.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/push/firebase_push_runtime.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/push/push_lifecycle.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/security/human_activity.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/theme/app_theme.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/theme/app_tokens.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/theme/app_typography.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/widgets/app_buttons.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/core/widgets/app_layout.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/active_parking/presentation/active_parking_panel.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/data/device_credential.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/data/device_security.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/data/session_store.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/presentation/auth_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/presentation/biometric_diagnostic_dialog.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/presentation/biometric_invitation.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/presentation/login_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/presentation/security_settings.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/state/auth_controller.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/auth/state/inactivity_lock.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/citizen_activity/profile_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/discovery/presentation/parking_map.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/discovery/presentation/parking_map_marker.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/home/home_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/parking/data/parking_contract.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/parking/presentation/parking_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/parking/state/parking_controller.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/payments/data/checkout_abandonment.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/payments/data/payment_contract.dart` | D | No effective source diff; protected payment file remains identical to develop |
| `simertpi-citizen-app/lib/features/payments/data/payment_intent_store.dart` | D | No effective source diff; protected payment file remains identical to develop |
| `simertpi-citizen-app/lib/features/payments/presentation/payments_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/payments/state/payments_controller.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/splash/splash_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/vehicles/presentation/vehicle_plate.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/welcome/welcome_page.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/lib/features/welcome/welcome_theme.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/pubspec.yaml` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/active_parking_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/api_client_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/auth_controller_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/auth_widget_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/biometric_diagnostic_dialog_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/biometric_diagnostics_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/citizen_activity_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/cp2441_flow_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/cp2452_auth_http_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/cp2452_security_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/cp2453_auth_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/cp245_flow_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/dev_auth_config_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/discovery_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/final_parking_rules_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/foundation_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/grace_lifecycle_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/operational_redesign_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/parking_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/payments_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/push_lifecycle_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/push_onboarding_home_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/session_persistence_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/support/auth_test_support.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/visual_fidelity_test.dart` | A | Included; current isolated candidate passed validation |
| `simertpi-citizen-app/test/welcome_test.dart` | A | Included; current isolated candidate passed validation |

## Ignorados (fuera de publicacion)

- `.idea/`.
- `simertpi-backend/.idea/`.
- `simertpi-backend/CP24.5.3_AUTH_TESTS.log`.
- `simertpi-backend/CP24.5.3_BACKEND_TESTS.log`.
- `simertpi-backend/CP24.5.3_MIGRATION_RECHECK.log`.
- `simertpi-backend/target/`.
- `simertpi-citizen-app/.dart_tool/`.
- `simertpi-citizen-app/.flutter-plugins-dependencies`.
- `simertpi-citizen-app/.idea/`.
- `simertpi-citizen-app/CP24.5.3_ANDROID_TESTS.log`.
- `simertpi-citizen-app/CP24.5.3_ANDROID_TESTS_FINAL.log`.
- `simertpi-citizen-app/CP24.5.3_DIAG2_ANDROID_TESTS.log`.
- `simertpi-citizen-app/android/.gradle/`.
- `simertpi-citizen-app/android/app/src/main/java/`.
- `simertpi-citizen-app/android/gradle/wrapper/gradle-wrapper.jar`.
- `simertpi-citizen-app/android/gradlew`.
- `simertpi-citizen-app/android/gradlew.bat`.
- `simertpi-citizen-app/android/local.properties`.
- `simertpi-citizen-app/android/simertpi_citizen_app_android.iml`.
- `simertpi-citizen-app/build/`.
- `simertpi-citizen-app/cp231-directed-final.log`.
- `simertpi-citizen-app/cp231-directed.log`.
- `simertpi-citizen-app/cp231-final-tests.log`.
- `simertpi-citizen-app/cp231-handoff.log`.
- `simertpi-citizen-app/cp231-operational.log`.
- `simertpi-citizen-app/cp231-tests.log`.
- `simertpi-citizen-app/docs/reference/`.
- `simertpi-citizen-app/ios/Flutter/Generated.xcconfig`.
- `simertpi-citizen-app/ios/Flutter/ephemeral/`.
- `simertpi-citizen-app/ios/Flutter/flutter_export_environment.sh`.
- `simertpi-citizen-app/ios/Runner/GeneratedPluginRegistrant.h`.
- `simertpi-citizen-app/ios/Runner/GeneratedPluginRegistrant.m`.
- `simertpi-citizen-app/simertpi_citizen_app.iml`.

Configuraciones locales, wrappers ignorados, caches, logs, generated registrants, build/target, APK y capturas de referencia no se publican. Se copio el wrapper Gradle y local.properties solo para QA en el worktree; permanecen ignorados.

## Limitaciones y pendientes

- Validacion fisica Android y E2E remota de esta combinacion no ejecutadas.
- Captura visual optativa preexistente omitida en Flutter.
- No se ejecutan migraciones en DEV ni se modifica la configuracion de despliegue.
- PR requiere revision; no hay merge automatico.
- Advertencias de depreciacion existentes de Gradle/Kotlin se registran, sin actualizar dependencias ni herramientas.

Se retiro un unico espacio final de OFL.txt en el candidato, sin alterar el texto de la licencia. El original permanece intacto.
