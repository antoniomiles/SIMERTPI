# Cierre de cambios pendientes — reingreso configurable

## Estado y secuencia de integración

Inspección remota al iniciar: PR #2 abierto y sin merge, rama review/pending-simertpi-20261013, HEAD `0e55716830dc46fa70ed7b8ec61667915c986503`; develop remota `29b61a38ff4ea334323f589be37842c10a68b998`. PR #2 conserva sus siete commits y 128 archivos.

Nueva rama: `review/close-reentry-20261013`, basada exactamente en PR #2. Publicar como PR encadenado contra `review/pending-simertpi-20261013`. **Integrar primero PR #2 y después este PR**, revisando/reorientando su base a develop cuando corresponda. No integrar este delta aislado sobre el backend anterior ni presentar los 128 archivos de PR #2 como nuevos.

Este cierre publica código y QA; no resuelve ni certifica el E2E físico/remoto pendiente de PR #2. No se hizo merge, deploy, cambio de Render, escritura o migración en PostgreSQL DEV, ni acceso ADB/teléfono. No se generó APK: el cambio de producto es exclusivamente backend. El test Flutter añadido no modifica el binario de aplicación.

## Inventario actual y protección

Se revisaron ramas remotas, PRs abiertos, todos los worktrees, modificaciones tracked/untracked y archivos ignorados. El estado original incluía 168 archivos: 166 diferencias efectivas más dos contratos de pago que Git marcaba modificados pero cuyo contenido publicado era equivalente. El respaldo ZIP fuera del repositorio contiene las 168 entradas y cada SHA-256 fue comprobado al leer el archivo archivado. Hash del ZIP: `8aa63860fd70d7a2cc6fce0c9612d2ce5279b01536ef7164f0e07d08da7511f3`. Parches staged/unstaged, inventario y SHA original también se conservaron fuera de publicación. La rama original continúa en develop/379b820 y todos los bytes originales permanecen intactos.

Clasificación actual de los 168 archivos: **A=135, B=11, C=21, D=1, E=0**. A incluye 11 ya integrados en develop y 124 publicados en PR #2. Los nueve archivos de reingreso y los fragmentos de dos archivos compartidos constituyen B. La diferencia D es un espacio final en una línea de OFL.txt; se conserva la licencia ya publicada y no se modifica el original.

En el worktree de revisión aparecieron además un archivo QA no publicado (B) y dos logs JSONL (C). Se respaldó y recuperó ese archivo QA con un único ajuste de estilo —llaves de for— validado de forma focalizada. La otra rama biométrica no tenía diferencias pendientes. Entre los 6420 archivos ignorados solo se detectó GeneratedPluginRegistrant.java dentro de src/main; es generado, no funcional pendiente. Caches, builds, APK, logs, configuraciones personales y herramientas de los laboratorios siguen excluidos. No hay código funcional recuperable identificado que se haya dejado sin publicar por descuido.

No se copiaron archivos compartidos completos desde una versión anterior: se aplicó exclusivamente el diff reingreso sobre el HEAD del PR #2. Nuevos archivos se recuperaron desde el respaldo verificado. La V36 original se conserva localmente; su SQL byte por byte se publica como V38 después de confirmar que ese número no existía en develop ni PR #2.

## Cambios de la nueva entrega

| Funcionalidad / archivos | Estado anterior | Estado recuperado | Commit |
| --- | --- | --- | --- |
| ParkingReentryConfigurationService, ParkingReentryAdminController | Local sin publicar | Persistencia, GET/PUT admin, rango y auditoría | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| ParkingSessionService | Delta reingreso excluido | Serialización y validación antes de crear; coordinación con cierre | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| ParkingConflictException, GlobalExceptionHandler | Código sin mensaje de reingreso | 409 mantiene code/status y expone mensaje funcional seguro; otros mensajes permanecen por defecto | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| SecurityConfig | Sin rutas admin reingreso | GET/PUT solo SIMERTPI_ADMIN; preserva rutas de abandono y demás reglas de PR #2 | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| V38__configurable_parking_reentry.sql | V36 local anterior a V37 aplicada | Mismo SQL, nueva versión posterior, cinco minutos iniciales | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| ParkingSessionServiceTest, ParkingSessionCreationHttpIntegrationTest | Pruebas reingreso locales y fragmentos excluidos | Pruebas recuperadas; añade rechazo de GET/PUT anónimo y PUT ciudadano | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| PostgresInfrastructureIntegrationTest, ParkingReentryMigrationIntegrationTest | Expectativa V37; upgrade no comprobado | V38 y 37 migraciones; instalación limpia y upgrade desde V35+V37 sin cambiar checksums | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| CP24.1.3_PARKING_REENTRY_AND_CITIZEN_MAP.md | Documento local con referencias V36 | Documento recuperado, V38 y criterio de contrato/placa exacto | `857fbb891855c98b3ddb74232bbc33c346cd0a0f` |
| final_pr2_integration_test.dart | Cinco casos QA locales fuera del PR | Cobertura de staging/Pagar, concurrencia, persistencia y recuperación; sin cambios de producto Flutter | `accbdfb9b97228bd815b8a34f3b21aeaba0fb924` |
| Este informe | Inventario histórico sin cierre | Inventario completo A/B/C/D/E y evidencias focalizadas | Commit documental de esta rama |

## Reingreso: respuesta obtenida del código

- **Valor inicial real: cinco minutos**, INSERT id=1/cooldown_minutes=5 de la migración original recuperada.
- Configuración: `configuration.parking_reentry_settings`, fila singleton id=1; cooldown entre 1 y 1440, updated_at y updated_by. current()/update() del servicio consultan/modifican PostgreSQL, no Flutter ni un valor hardcoded de UI.
- Identidad existente: **misma placa normalizada upper(btrim(plate)) + mismo parking_space_id**, incluso entre cuentas. Se conserva ese alcance previamente desarrollado; no se añade restricción de zona/sector ni indisponibilidad global del espacio.
- Califica la finalización de una sesión **COMPLETED**, ended_at no nulo, cuyo expected_end_at sea al menos started_at+240 minutos. Se mide contrato acumulado confirmado, no permanencia física ni tiempo desde inicio. Puede calificar un cierre temprano de un contrato de cuatro horas; extensiones cuentan tras aprobación porque actualizan expected_end_at. No se modifica el límite máximo configurado en tarifas/horarios; el umbral del reingreso es 240 minutos.
- Inicio de la espera: ended_at del servidor registrado al completar la sesión. Se busca la última finalización que califica, ordenada ended_at DESC/id DESC, y se calcula until=ended_at+cooldown vigente.
- Bloquea crear cuando now.isBefore(until). **Permite al llegar exactamente a until o después**, y no crea cooldown para sesiones cortas o CANCELLED. Otro espacio u otra placa no se bloquean por esta regla; siguen aplicando las comprobaciones preexistentes de ocupación, propietario, disponibilidad y reglas.
- Las modificaciones administrativas afectan inmediatamente restricciones abiertas: la siguiente creación recalcula ended_at+valor persistido actual. Aumentar/acortar el valor puede extender/acortar el plazo. Este comportamiento se recupera, no se reinventa.
- lockVehicleSpace usa pg_advisory_xact_lock sobre placa normalizada/espacio para coordinar creación y cierre. Se conservan los row locks preexistentes del vehículo/sesión.
- Futuro administrador: `GET /api/v1/admin/parking/reentry-configuration`; `PUT` a la misma ruta con `{"cooldownMinutes":5}`. Requiere SIMERTPI_ADMIN, usuario enabled y rango válido. Actualizar registra actor, valor anterior y nuevo en auditoría. No se desarrolló interfaz administrativa.
- Migración definitiva: **V38__configurable_parking_reentry.sql**, sin publicar V36 ni editar V1–V35/V37, sin repair/outOfOrder. En el historial V35+V37 aplica una única migración nueva. Instalación limpia aplica 37 migraciones V1–V35,V37,V38.

## Protección de lo aprobado

Sin diff de producto respecto a PR #2 en Flutter lib/Android, módulos de pagos/reconciliation, identity/auth y notifications. Tarjeta y Deuna conservan interfaz sin cobros reales; Método DEV permanece sandbox. Espacio→Vehículo→Minutos→Confirmar datos→Método→Resumen→Pagar→Procesamiento→Resultado permanece intacto. Las rutas y el contrato de creación/cierre conservan forma; se recupera exclusivamente la restricción de reingreso prevista.

No cambian checkout, idempotencia, conciliación, reserva incierta, abandono, sesiones/tokens, renovación, logout, inactividad, biometría ni notificaciones. ParkingSessionService sí integra el nuevo bloqueo previsto; sus flujos preexistentes de creación/cierre y las extensiones confirmadas se verificaron en las pruebas focalizadas del módulo afectado.

Contratos protegidos:

- payment_contract.dart SHA256 `d5abd2860e86dfcdcf9112fec5a23e371287fd1c425e63dd3a004993902bef97`.
- payment_intent_store.dart SHA256 `082259bb963f8cbed334c8ac0cd98e9bda0ea364a624e13cf9a6e237728731bd`.

## Validación ejecutada, sin suites completas

```text
mvn --batch-mode -Dtest=ParkingSessionServiceTest,ParkingSessionCreationHttpIntegrationTest,PostgresInfrastructureIntegrationTest,ParkingReentryMigrationIntegrationTest test
```

Java 21 y PostgreSQL 16.15 Testcontainers desechable. **53 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED; BUILD SUCCESS / exit 0**:

| Clase | PASS |
| --- | ---: |
| ParkingSessionCreationHttpIntegrationTest | 37 |
| ParkingSessionServiceTest | 12 |
| ParkingReentryMigrationIntegrationTest | 2 |
| PostgresInfrastructureIntegrationTest | 2 |

Cobertura de espera, otro espacio/placa, misma placa otra cuenta, exclusión de contrato corto/cancelado, límite de cinco minutos, configuración dinámica, rango 0/1441 rechazado, auditoría/roles, extensiones aprobadas acumulando cuatro horas y cierre temprano. Nuevo caso de permisos comprueba GET/PUT anónimo=401 y PUT ciudadano=403 sin alterar el valor. Tests de migración comprueban upgrade de V35+V37, checksums previos intactos, cinco minutos por defecto, constraints y preservación de un valor persistido al volver a ejecutar migrate.

```text
dart analyze --fatal-infos test/final_pr2_integration_test.dart
flutter test --no-pub test/final_pr2_integration_test.dart --reporter json
```

Análisis final **No issues found / exit 0** y **5 PASS / 0 FAIL / exit 0** del único archivo QA recuperado. El primer análisis detectó una sugerencia de llaves de for, corregida únicamente en la copia. El primer intento de test no llegó a ejecutar casos por faltar package_graph.json en la copia nueva; se reutilizó metadata/caché existente y se ejecutaron los cinco casos. No se ejecutó flutter clean, ni se descargaron paquetes ni se tocó el workspace original para resolverlo.

git diff --check PASS, staging por listas explícitas, comparación contra inventario inicial, escaneo de secretos y archivos accidentales, comparación de migraciones y de contratos protegidos. Los 168 archivos originales se revalidaron por hash antes de los commits.

Evidencia previa reutilizada, **no reejecutada**: Maven 523 PASS; Flutter 483 PASS/1 captura optativa SKIPPED; Android JVM 7 PASS; flutter analyze PASS del PR #2. Sus números no se suman a la validación focalizada. No se realizaron pruebas físicas ni se exigió conectar un teléfono.

## Riesgos y qué falta

- Publicación no significa merge/deploy: requiere revisión del PR encadenado y la secuencia PR #2→nuevo PR. Los pendientes de integración de PR #2 se mantienen visibles y deben resolverse/aceptarse explícitamente antes de merge según su alcance; la ausencia de USB no impide esta publicación backend.
- Antes del despliegue autorizado: respaldo DEV reciente con restauración probada, revalidar HEAD/live/checksums y configuración administrativa; comprobar que el entorno objetivo tiene V35+V37 y no una V36 que ya creó parking_reentry_settings. V38 no oculta objetos incompatibles con IF NOT EXISTS ni altera historia. Otros historiales requieren revisión, no repair ni renumeración automática.
- Tras desplegar ambos PRs, comprobar Flyway V38, configuración=5 salvo cambio persistido aprobado, salud, permisos GET/PUT y respuesta de reingreso. No activar AutoDeploy como parte del cierre.
- No se certifica huella física. No se genera APK nuevo para un backend que todavía no está desplegado. Laboratorios/artefactos privados y evidencias locales anteriores no entran al PR.

## Inventario completo del workspace original

A=ya publicado; B=funcional pendiente recuperado; C=local/generado; D=equivalente/formato; E=decisión funcional (ningún caso identificado).

| Archivo | Clase | Motivo |
| --- | --- | --- |
| `simertpi-backend/docs/CP24.1.3_PARKING_REENTRY_AND_CITIZEN_MAP.md` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/GlobalExceptionHandler.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/ParkingConflictException.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/identity/DeviceCredentialController.java` | A | Already published in develop |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/ParkingReentryAdminController.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/ParkingSessionController.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/identity/auth/DeviceCredentialService.java` | A | Already published in develop |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/identity/auth/MobileAuthService.java` | A | Already published in develop |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/notifications/NotificationDispatcher.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/AbandonUnpaidCheckoutService.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingReentryConfigurationService.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingSessionService.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/UnstartedCheckoutCleanupScheduler.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/payments/PaymentIntegrationService.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/payments/PaymentService.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/payments/PendingPaymentExpiryScheduler.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/application/reconciliation/PaymentReconciliationService.java` | A | Already published in PR #2 |
| `simertpi-backend/src/main/java/ec/gob/simertpi/config/SecurityConfig.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/resources/db/migration/V36__configurable_parking_reentry.sql` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/main/resources/db/migration/V37__device_biometric_credentials.sql` | A | Already published in develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/notifications/NotificationProviderHttpIntegrationTest.java` | A | Already published in develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/operations/OperationalHttpIntegrationTest.java` | A | Already published in develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/payments/OperationalRecoveryPostgresIntegrationTest.java` | A | Already published in PR #2 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/payments/PaymentCreationHttpPostgresIntegrationTest.java` | A | Already published in PR #2 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/payments/PaymentProviderHttpIntegrationTest.java` | A | Already published in PR #2 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/security/DeviceCredentialPostgresIntegrationTest.java` | A | Already published in develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/api/security/HttpBasicSecurityTest.java` | A | Already published in PR #2 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/identity/auth/DeviceCredentialCryptoTest.java` | A | Already published in develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/AbandonUnpaidCheckoutServiceTest.java` | A | Already published in PR #2 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/ParkingSessionServiceTest.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/application/payments/PaymentServiceTest.java` | A | Already published in PR #2 |
| `simertpi-backend/src/test/java/ec/gob/simertpi/testsupport/CitizenInboxMigrationIntegrationTest.java` | A | Already published in develop |
| `simertpi-backend/src/test/java/ec/gob/simertpi/testsupport/PostgresInfrastructureIntegrationTest.java` | B | Functional reentry delta to recover; V36 transferred to available V38 |
| `simertpi-citizen-app/CP24.5.2_BASELINE.json` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.2_INFORME.md` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.2_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_AUTH_FINAL_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_BASELINE.json` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_BASELINE_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_DIAG2_BASELINE.json` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_DIAG2_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_DIAG3_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_INFORME.md` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_TARGET_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5.3_VISUAL_TESTS.jsonl` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/CP24.5_DIAGNOSTICO.md` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/android/app/build.gradle.kts` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/AndroidManifest.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/DeviceCredentialChannel.kt` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/DeviceSecurityChannel.kt` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/GuardedFirebaseMessagingService.kt` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/kotlin/ec/gob/simertpi/simertpi_citizen_app/MainActivity.kt` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-hdpi/simertpi_splash_icon.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-mdpi/simertpi_splash_icon.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-v21/launch_background.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-xhdpi/simertpi_splash_icon.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-xxhdpi/simertpi_splash_icon.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable-xxxhdpi/simertpi_splash_icon.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/drawable/launch_background.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-hdpi/ic_launcher.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-mdpi/ic_launcher.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xhdpi/ic_launcher.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/values-night-v31/styles.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/values-night/colors.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/values-night/styles.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/values-v31/styles.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/values/colors.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/main/res/values/styles.xml` | A | Already published in PR #2 |
| `simertpi-citizen-app/android/app/src/test/kotlin/ec/gob/simertpi/simertpi_citizen_app/PushPresentationPolicyTest.kt` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/branding/SIMERTPI_logotipo_blanco.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/branding/SIMERTPI_logotipo_negro.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/branding/simertpi_icono.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/branding/simertpi_logo_blanco.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/branding/simertpi_logo_principal.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/fonts/OFL.txt` | D | Equivalent content; only trailing whitespace differs from published license |
| `simertpi-citizen-app/assets/fonts/Poppins-Medium.ttf` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/fonts/Poppins-Regular.ttf` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/fonts/Poppins-SemiBold.ttf` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/welcome/simertpidia.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/assets/welcome/simertpinoche.png` | A | Already published in PR #2 |
| `simertpi-citizen-app/desing/references/Guía de marca y pantallas SIMERTPI.png` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/desing/references/SIMERTPI_logotipo_blanco.png` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/desing/references/SIMERTPI_logotipo_negro.png` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/desing/references/simertpi_icono.png` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/desing/references/simertpidia.png` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/desing/references/simertpinoche.png` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/desing/references/xxx.jpeg` | C | Local evidence/reference intentionally excluded from product |
| `simertpi-citizen-app/lib/app/app.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/app/bootstrap/bootstrap.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/app/router/app_router.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/errors/app_failure.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/logging/app_log.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/logging/biometric_diagnostic.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/network/api_client.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/push/firebase_push_runtime.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/push/push_lifecycle.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/security/human_activity.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/theme/app_theme.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/theme/app_tokens.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/theme/app_typography.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/widgets/app_buttons.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/core/widgets/app_layout.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/active_parking/presentation/active_parking_panel.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/data/device_credential.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/data/device_security.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/data/session_store.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/presentation/auth_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/presentation/biometric_diagnostic_dialog.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/presentation/biometric_invitation.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/presentation/login_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/presentation/security_settings.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/state/auth_controller.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/auth/state/inactivity_lock.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/citizen_activity/profile_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/discovery/presentation/parking_map.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/discovery/presentation/parking_map_marker.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/home/home_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/parking/data/parking_contract.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/parking/presentation/parking_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/parking/state/parking_controller.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/payments/data/checkout_abandonment.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/payments/data/payment_contract.dart` | A | Already published in develop |
| `simertpi-citizen-app/lib/features/payments/data/payment_intent_store.dart` | A | Already published in develop |
| `simertpi-citizen-app/lib/features/payments/presentation/payments_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/payments/state/payments_controller.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/splash/splash_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/vehicles/presentation/vehicle_plate.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/welcome/welcome_page.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/lib/features/welcome/welcome_theme.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/pubspec.yaml` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/active_parking_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/api_client_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/auth_controller_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/auth_widget_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/biometric_diagnostic_dialog_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/biometric_diagnostics_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/citizen_activity_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/cp2441_flow_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/cp2452_auth_http_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/cp2452_security_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/cp2453_auth_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/cp245_flow_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/dev_auth_config_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/discovery_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/final_parking_rules_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/foundation_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/grace_lifecycle_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/operational_redesign_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/parking_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/payments_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/push_lifecycle_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/push_onboarding_home_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/session_persistence_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/support/auth_test_support.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/visual_fidelity_test.dart` | A | Already published in PR #2 |
| `simertpi-citizen-app/test/welcome_test.dart` | A | Already published in PR #2 |

### Worktree de revisión adicional

| Archivo | Clase | Motivo |
| --- | --- | --- |
| `simertpi-citizen-app/test/final_pr2_integration_test.dart` | B | Five focused regression tests already PASS; unpublished QA source recovered unchanged |
| `final-directed-tests.jsonl` | C | Generated local test log |
| `final-new-tests.jsonl` | C | Generated local test log |

El ajuste de estilo del archivo QA se verificó con cinco PASS nuevos; el hash del original recuperable permanece en su respaldo. Todos los archivos originales/excluidos se conservaron, sin borrado ni sobrescritura.
