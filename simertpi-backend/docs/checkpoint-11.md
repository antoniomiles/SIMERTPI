# Checkpoint 11: reconciliacion y recuperacion operativa

## Diseno y alcance

`PaymentReconciliationService`, `EvidenceReconciliationService` y el procesador existente de outbox implementan recuperaciones internas. Cada resultado contiene recurso, identificador opaco, fecha, motivo, clasificacion, accion y correlation ID. No se integra un proveedor de pagos ni almacenamiento cloud. No se alteran tarifas, horarios ni reglas de CP10.

## Timeout de pagos y sesiones

Se conserva el namespace existente `simertpi.payments.pending-timeout`. `enabled` queda deshabilitado en DEV. `minutes` debe proporcionarse explicitamente; habilitarlo sin un entero positivo falla al arrancar, sin valor oculto. Variables de entorno: `SIMERTPI_PENDING_TIMEOUT_ENABLED` y `SIMERTPI_PAYMENTS_PENDING_TIMEOUT_MINUTES`. Frecuencia: `simertpi.payments.pending-timeout.scan-delay-ms`.

La recuperacion selecciona sesiones PENDING_PAYMENT vencidas, incluidas las que no tienen pago. Revisa nuevamente el estado bajo bloqueo de sesion y luego bloquea los pagos. Un pago APPROVED impide el timeout. Un pago PENDING/PROCESSING con creacion o intento reciente conserva su ventana, incluso cuando se reutiliza el pago de un intento anterior. Al vencer, cancela pagos pendientes/procesando, actualiza el ultimo intento, publica PAYMENT_CANCELLED_TIMEOUT, cancela la sesion y establece endedAt. El indice existente deja de considerar ocupado ese espacio.

`PendingPaymentExpiryService` queda como fachada de compatibilidad del mismo servicio central. No contiene una segunda implementacion.

## Reconciliacion de pagos

- APPROVED inicial + PENDING_PAYMENT: AUTO_RECOVERABLE solamente si paidAt y referencia de transaccion interna estan presentes, importe coincide, vencimiento sigue vigente, no hay endedAt, intento en curso ni diagnostico previo de timeout. Se activa la sesion; **nunca se cambia un pago a APPROVED**.
- APPROVED + CANCELLED, multiples pagos iniciales aprobados, sesion activa sin pago inicial aprobado, pendiente vencido y estados incompatibles: MANUAL_REVIEW_REQUIRED. No se reactiva una sesion cancelada.
- Estado compatible: CONSISTENT, sin escritura ni auditoria.

Los pagos de extensiones se distinguen de pagos iniciales: una sesion puede tener legitimamente un pago inicial y varios pagos de extension aprobados.

## Locking y transacciones

Orden comun: sesion primero, pagos despues. Aprobacion, fallo y declinacion toman el mismo bloqueo de sesion y refrescan el pago despues de adquirirlo para evitar usar el estado JPA anterior al timeout. La aprobacion valida nuevamente la transicion y el estado de sesion; un pago CANCELLED se rechaza explicitamente. Cada recuperacion se confirma junto con sus diagnosticos, eventos y auditoria.

Dos reconciliadores serializan la transicion de una misma sesion. Los diagnosticos tienen unicidad por tipo/recurso/motivo, independiente del actor. Una repeticion con igual clasificacion/accion no audita; un diagnostico manual que pasa a recuperado registra la nueva accion.

## Outbox

Estados: PENDING -> PROCESSING -> PUBLISHED; en error FAILED -> retry, o DEAD al agotar el limite. Se reutiliza `retry_count` como contador de intentos reclamados, incluyendo el primero. Se agregan `last_attempt_at` y `processing_token`; se conserva `next_attempt_at` y `last_error`, este ultimo con codigos tecnicos sanitizados.

La reclamacion se confirma en una transaccion corta con FOR UPDATE SKIP LOCKED. El procesamiento y sus efectos locales se confirman en otra transaccion que mantiene el bloqueo del evento. Un fallo revierte esa transaccion y registra FAILED/DEAD en otra; el lote continua. El token impide que un worker anterior confirme el trabajo de una reclamacion recuperada. La unicidad previa de notificaciones mantiene idempotencia de efectos locales.

Configuracion tecnica explicita de DEV, sustituible por environment:

| Propiedad | Variable | DEV |
|---|---|---|
| simertpi.outbox.max-attempts | SIMERTPI_OUTBOX_MAX_ATTEMPTS | 3 |
| simertpi.outbox.backoff-seconds | SIMERTPI_OUTBOX_BACKOFF_SECONDS | 30 |
| simertpi.outbox.processing-timeout-seconds | SIMERTPI_OUTBOX_PROCESSING_TIMEOUT_SECONDS | 300 |

Backoff lineal: segundos configurados por numero de intento. PROCESSING abandonado se recupera a FAILED con proximo intento, o DEAD si alcanzo el limite. Registros antiguos PENDING/FAILED con limite agotado tambien pasan a DEAD. Un worker vivo que mantiene el bloqueo no es tomado por la recuperacion. DEAD requiere atencion, sin reinicio peligroso por HTTP.

## Evidencias

`ObjectStorage.listKeys()` amplia el contrato generico de inventario. LocalObjectStorage inventaria solamente claves gestionadas por CP9, sin seguir enlaces simbolicos ni devolver rutas fisicas. La reconciliacion usa ese contrato, nunca filesystem directamente.

Se detectan METADATA_WITHOUT_OBJECT, OBJECT_WITHOUT_METADATA, HASH_MISMATCH, SIZE_MISMATCH, INVALID_STORAGE_REFERENCE (metadata antigua incompatible con CP9) y STORAGE_VERIFICATION_UNAVAILABLE. Se cuentan bytes reales y se calcula SHA-256 por streaming. Una referencia invalida no detiene el lote. Antes de decidir si un objeto es huerfano, se toma el mismo bloqueo de infraccion usado por upload y se vuelve a consultar DB y almacenamiento tras esperar el commit.

**Nunca se borran objetos ni metadata, se reemplazan archivos o se reparan contenidos.** El diagnostico conserva los datos para revision. Los resultados y auditorias no incluyen claves, rutas, hashes, bytes ni secretos; el objeto huerfano recibe un identificador opaco estable.

## Schedulers y API

Cada bloque tiene su propio switch y frecuencia, deshabilitado por defecto:

- `simertpi.reconciliation.payments.enabled` / `fixed-delay-ms`: SIMERTPI_PAYMENT_RECONCILIATION_ENABLED / SIMERTPI_PAYMENT_RECONCILIATION_DELAY_MS.
- `simertpi.reconciliation.evidence.enabled` / `fixed-delay-ms`: SIMERTPI_EVIDENCE_RECONCILIATION_ENABLED / SIMERTPI_EVIDENCE_RECONCILIATION_DELAY_MS.
- `simertpi.reconciliation.outbox.enabled` / `fixed-delay-ms`: SIMERTPI_OUTBOX_RECOVERY_ENABLED / SIMERTPI_OUTBOX_RECOVERY_DELAY_MS.

El procesamiento normal conserva `simertpi.notifications.outbox.fixed-delay-ms` y agrega `enabled` (SIMERTPI_OUTBOX_ENABLED). Las frecuencias son tecnicas, no normativas.

POST `/api/v1/admin/reconciliation/payments`, `/evidence`, `/outbox`: exclusivamente SIMERTPI_ADMIN. Payments aplica timeout habilitado y luego revisa; evidence diagnostica; outbox recupera estados abandonados/agotados (sin cambiar estados arbitrariamente). Los errores controlados de disponibilidad/configuracion devuelven 503 con codigo generico. No hay parametros para forzar estados, borrado ni aprobacion.

## Auditoria y observabilidad

Eventos: PAYMENT_PENDING_TIMEOUT, SESSION_CANCELLED_BY_PAYMENT_TIMEOUT, PAYMENT_RECONCILIATION_RECOVERED, PAYMENT_RECONCILIATION_MANUAL_REVIEW, OUTBOX_RETRY_EXHAUSTED, OUTBOX_STALE_PROCESSING_RECOVERED y EVIDENCE_INCONSISTENCY_DETECTED. Se conserva actor, roles y contexto de AuditService. Scans limpios/vacios no generan ruido. HTTP conserva CorrelationIdFilter; jobs generan un correlation ID por ejecucion y restauran MDC al terminar.

`audit.reconciliation_findings` persiste diagnosticos deduplicados y la ultima accion relevante por motivo. Es historial de diagnosticos, no una bandeja con cierre automatico; el resultado del scan expresa el estado actual. El functional_audit_log permanece append-only.

## Migracion y pruebas

V27 es necesaria para estados/campos de recuperacion outbox, sus indices y diagnosticos persistentes. Es compatible con datos existentes, no contiene datos normativos ni modifica V1-V26.

Pruebas nuevas: integracion HTTP/PostgreSQL con sesiones/pagos reales, carreras de aprobacion/timeout, dos recuperadores, dos reconciliadores, workers y retries concurrentes, evidencia durante commit, corrupcion y ausencia de objetos, no modificacion de metadata/contenido, deduplicacion de auditoria, permisos y correlation ID. Unitarias para configuracion, schedulers y contexto. Se mantienen las regresiones; la comprobacion CP10 acepta versiones Flyway posteriores a V26. El scheduler outbox se deshabilita en tests para evitar interferencia entre contextos Spring; se invoca explicitamente.

PostgreSQL local 16.15, no Testcontainers. Evidencias de CP11 usan un directorio creado en `%TEMP%/simertpi-cp11-*`, con limpieza por test y al terminar. No se crean archivos de evidencia en el repositorio. La auditoria append-only de ejecuciones de tests se conserva en la base local.

## Deuda tecnica

- Proveedor externo y verificaciones criptograficas siguen pendientes: webhook permanece cerrado; no banco, refunds ni aprobaciones inferidas.
- Paginar inventarios/scans y agregar metricas/alertas y una bandeja de revision con cierre explicito para volumen productivo.
- Implementar inventario paginado en el futuro adapter cloud. La garantia de efectos idempotentes actual cubre DB/local; un futuro envio externo necesitara su propia clave idempotente.
- Revisar manualmente referencias antiguas incompatibles y metadata sin objeto. Cambiar el directorio de almacenamiento exige asegurar que contiene los objetos correspondientes antes de interpretar ausencias.
- No se expone reparacion destructiva, cierre de incidencias ni reintento manual de DEAD en esta API.

## Resultado final

Estado: PASS CON OBSERVACIONES (referencias antiguas requieren revision manual; inventarios productivos necesitaran paginacion/alertas).

- `mvn test`: BUILD SUCCESS; 232 tests, 0 failures, 0 errors, 0 skipped. Se agregan 52 pruebas de integracion y 7 unitarias; las regresiones siguen verdes.
- Flyway valida 27 migraciones; ultima aplicada V27. V1-V26 sin cambios.
- PostgreSQL local 16.15 (`localhost:5432/simertpi`), no Testcontainers.
- `git diff --check`: limpio.
- Directorios temporales CP11 retirados al terminar; sin evidencia ni archivos temporales nuevos en el repositorio.
- La primera ejecucion tuvo 1 failure y 8 errors por referencias legacy que detenian el scan; se corrigio la clasificacion y todas las pruebas finales pasan.
- No se ejecutaron git add, commit o push.

## Archivos

Creados (12):

- application/reconciliation/PaymentReconciliationService.java
- application/reconciliation/EvidenceReconciliationService.java
- application/reconciliation/ReconciliationFindings.java
- application/reconciliation/ReconciliationResult.java
- application/reconciliation/RecoveryConfiguration.java
- application/reconciliation/ReconciliationSchedulers.java
- api/reconciliation/ReconciliationController.java
- src/main/resources/db/migration/V27__operational_recovery.sql
- src/test/java/ec/gob/simertpi/api/payments/OperationalRecoveryPostgresIntegrationTest.java
- src/test/java/ec/gob/simertpi/application/reconciliation/RecoveryConfigurationTest.java
- src/test/resources/application.properties
- docs/checkpoint-11.md

Modificados (10):

- application/enforcement/storage/ObjectStorage.java
- infrastructure/storage/LocalObjectStorage.java
- application/notifications/NotificationOutboxProcessor.java
- application/payments/PaymentService.java
- application/payments/PendingPaymentExpiryService.java
- application/payments/PendingPaymentExpiryScheduler.java
- config/SecurityConfig.java
- src/main/resources/application.yml
- src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java
- src/test/java/ec/gob/simertpi/application/payments/PendingPaymentExpiryServiceTest.java

Las rutas abreviadas de codigo productivo son relativas a src/main/java/ec/gob/simertpi.

