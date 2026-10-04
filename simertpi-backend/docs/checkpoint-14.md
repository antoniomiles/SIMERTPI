# Checkpoint 14 - Observabilidad y operacion

CP14 prepara el backend para observabilidad externa.
No despliega Prometheus, Grafana, Loki/ELK,
Alertmanager ni OpenTelemetry Collector.

## Actuator y seguridad

Se agrega solamente micrometer-registry-prometheus, version administrada por Spring Boot (1.15.4). No hay cliente manual ni SDK externo. GET /actuator/health, /health/liveness y /health/readiness son publicos y no muestran componentes/detalles. Liveness incluye exclusivamente livenessState: un fallo DB/proveedor no provoca restart loops. Readiness incluye readinessState y db; DB DOWN devuelve 503. No se llama a providers durante probes.

GET /actuator/info, /metrics, /metrics/** y /prometheus requieren SIMERTPI_ADMIN. La politica de auditoria se hace explicita: GET para ADMIN, otros metodos denegados (append-only); conserva la regresion anterior sin depender del despacho /error. Otros endpoints Actuator quedan deny-by-default y no se exponen env, beans, configprops, dumps ni mappings. InfoContributor devuelve unicamente application.name. El contributor de environment esta deshabilitado. Prometheus esta preparado para scrape autenticado; no se instala infraestructura ni se crean credenciales.

UNCONFIGURED es intencional en providers CP12/CP13 y no implica DOWN. No se declara AVAILABLE sin comunicacion real. No se agrega health de ObjectStorage: su contrato generico no ofrece un probe barato fiable; listar/verificar evidencias seria costoso y no pertenece a readiness. Fallos de storage/integridad se observan mediante eventos y reconciliacion.

## Metricas y semantica

OperationalMetrics centraliza nombres y valida tags. Dominio puro y contratos de provider permanecen independientes de Micrometer. Los contadores funcionales se actualizan despues del commit; rollback no cuenta exito. AuditService comunica solo eventos efectivamente insertados (ON CONFLICT no genera otro incremento). Generacion y transiciones adicionales usan hooks en el punto real de cambio, conservando idempotencia. Webhooks received/rejected y fallos de upload/integridad cuentan intentos incluso ante rollback.

Familias simertpi.parking.sessions: created, activated, completed, expired, max_time_reached. Extensions: requested (solicitudes aceptadas/persistidas), approved. Payment approval de extension cuenta extension.approved, no otra sesion activada. Families simertpi.payments: created, approved, declined, failed, pending, manual_review, webhooks.received/rejected y reconciliation.findings.

Familias simertpi.notifications: generated, delivered, temporary_failure, permanent_failure, no_destination, dead, retry, destination_disabled. generated cuenta notificaciones funcionales; delivered cuenta deliveries fisicas. retry cuenta reclamaciones posteriores al primer intento, sobre el mismo UUID de delivery. INVALID_DESTINATION forma parte de permanent_failure; desactivar un device tambien cuenta destination_disabled. SUPPRESSED no es un envio ni un fallo de provider.

Familias simertpi.evidence: uploaded, upload_failed (flujo HTTP seguro), downloaded, integrity_failure, reconciliation_findings. Integridad fallida no registra una descarga exitosa. Findings de evidencia contabilizan cambios diagnosticados/auditados, no cada lectura repetida.

simertpi.reconciliation.runs usa type=PAYMENTS/EVIDENCE/OUTBOX y result=SUCCESS/FINDINGS/FAILED. simertpi.reconciliation.findings cuenta observaciones por scan, incluso findings persistentes; no representa backlog actual ni findings unicos. payments.reconciliation.findings tiene igual semantica para pagos. Conteos no consultan DB durante scrape.

simertpi.scheduler.runs y simertpi.scheduler.duration miden ejecuciones/duracion/resultado. Nombres controlados: PARKING_CONTROL, PENDING_PAYMENT, PAYMENT_RECONCILIATION, EVIDENCE_RECONCILIATION, OUTBOX_RECOVERY, NOTIFICATION_RETRY, NOTIFICATION_REMINDERS, NOTIFICATION_OUTBOX, PERMIT_EXPIRY. Schedulers deshabilitados no se cuentan como trabajos ejecutados. Errores de items capturados por control/outbox marcan FAILED sin detener el resto del lote. La instrumentacion conserva excepciones y comportamiento funcional.

HTTP/JVM/process/Hikari usan instrumentacion estandar de Spring Boot/Micrometer. Se habilitan histogramas http.server.requests; no se duplican requests, latencia ni status. HTTP usa URI templada, no IDs individuales. Custom tags solo type/result/scheduler con whitelist; contadores funcionales omiten tags variables. No userId, plate, sessionId, paymentId, providerPaymentId, correlationId, keys, hashes, destinos ni tokens.

Los counters/timers son memoria de proceso y se reinician con el proceso. El exporter Prometheus transforma puntos en underscores y counters en _total; consultar /metrics para nombre original y /prometheus para formato real. No sirven como ledger financiero ni estado durable.

## Trazabilidad, errores y logs

CorrelationIdFilter existente acepta [A-Za-z0-9._:-] de 1-128 caracteres, reemplaza entradas invalidas, genera UUID cuando falta, devuelve X-Correlation-ID y limpia/restaura MDC en finally. Schedulers generan/restauran MDC. Logging conserva correlationId= en el patron; eventos de scheduler usan event=, result=, scheduler=, sin argumentos sensibles ni excepciones completas.

Errores funcionales mantienen contratos anteriores y agregan correlationId al cuerpo cuando es un mapa con status numerico >=400. Errores operativos inesperados devuelven mensaje generico 500 sin SQL, paths ni secretos. Denegaciones de filtros mantienen el header de correlacion, aunque no siempre tengan cuerpo JSON. Solo se sanitizan logs de componentes tocados.

No se imprimen payloads/webhooks, credenciales, tarjetas, device tokens, telefonos/emails, archivos ni claves de idempotencia. No se agregan secretos. Los defaults DEV existentes no se utilizan como credenciales de scrape.

## Operacion y deuda

Runbook: docs/operations-runbook.md. Summary administrativo diferido: los findings actuales no modelan resolucion/open de forma completa, y counters no equivalen a backlog. Un summary futuro requiere semantica durable, indices y medicion acotada; no se implementan counts masivos durante scrape.

OpenTelemetry diferido hasta definir collector/propagacion/exportacion/autenticacion oficialmente. No se inventa URL de collector. CorrelationId es trazabilidad minima actual. Proximos trabajos: alertas desplegadas, dashboards y logs centralizados, gauges de backlog con muestreo acotado, ultimo-run durable y SLOs/umbrales acordados.

## Validacion

No hay migracion nueva; V1-V29 intactas. Tests de contrato/commit/rollback, seguridad HTTP, probes con DB DOWN simulado, correlationId/MDC, cardinalidad y flujos reales de providers sandbox. Validacion final: mvn test BUILD SUCCESS, 391 tests, 0 failures, 0 errors, 0 skipped (49 tests nuevos, regresiones preservadas). PostgreSQL local 16.15, sin Testcontainers; Flyway valida las 29 migraciones y mantiene V29. git diff --check sin errores; sin temporales nuevos ni secretos agregados.

## Inventario

### Archivos creados (9)

- `docs/checkpoint-14.md`
- `docs/operations-runbook.md`
- `src/main/java/ec/gob/simertpi/api/OperationalErrorAdvice.java`
- `src/main/java/ec/gob/simertpi/application/operations/OperationalInstrumentation.java`
- `src/main/java/ec/gob/simertpi/application/operations/OperationalMetrics.java`
- `src/main/java/ec/gob/simertpi/config/OperationalInfoConfiguration.java`
- `src/test/java/ec/gob/simertpi/api/operations/OperationalHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/application/operations/CorrelationIdFilterTest.java`
- `src/test/java/ec/gob/simertpi/application/operations/OperationalMetricsTest.java`

### Archivos modificados (14)

- `pom.xml`
- `src/main/java/ec/gob/simertpi/api/GlobalExceptionHandler.java`
- `src/main/java/ec/gob/simertpi/application/audit/AuditService.java`
- `src/main/java/ec/gob/simertpi/application/enforcement/EvidenceService.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationDispatcher.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationGenerationService.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationOutboxProcessor.java`
- `src/main/java/ec/gob/simertpi/application/parking/ParkingSessionService.java`
- `src/main/java/ec/gob/simertpi/application/parking/control/ParkingControlScheduler.java`
- `src/main/java/ec/gob/simertpi/application/payments/PaymentService.java`
- `src/main/java/ec/gob/simertpi/config/SecurityConfig.java`
- `src/main/resources/application.yml`
- `src/test/java/ec/gob/simertpi/api/notifications/NotificationProviderHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/payments/PaymentProviderHttpIntegrationTest.java`
