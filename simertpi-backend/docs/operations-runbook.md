# SIMERTPI - Runbook de operacion

CP14 prepara el backend para observabilidad externa.
No despliega Prometheus, Grafana, Loki/ELK,
Alertmanager ni OpenTelemetry Collector.

## Health y acceso

GET /actuator/health: estado agregado sin detalles. GET /actuator/health/liveness: disponibilidad del proceso; no incluye DB ni providers. GET /actuator/health/readiness: readinessState + DB. Un 503 de readiness retira trafico; no justifica reiniciar repetidamente un proceso cuyo liveness esta UP.

GET /actuator/metrics, /actuator/prometheus e /actuator/info requieren SIMERTPI_ADMIN. Configurar el futuro scraper con autenticacion externa; no poner password/Authorization en URL, comandos compartidos ni logs. El endpoint de info solo informa nombre de aplicacion. Env/beans/configprops/dumps/mappings no estan expuestos. No existen dashboards ni plataforma de logs desplegados en este checkpoint.

## Correlation ID y logs

Conservar X-Correlation-ID recibido en responses y errores. Buscar correlationId= en logs y correlation_id en auditoria para una misma operacion. Nunca convertir correlationId en tag Prometheus. IDs invalidos o demasiado largos se reemplazan. Los schedulers generan correlacion propia cuando no hay MDC previo.

Logs de operacion: event=scheduler_run result=SUCCESS/FAILED scheduler=nombre. Control/outbox reportan item fallido con codigo estructurado, sin stacktrace/payload. No registrar ni compartir tokens, contactos completos, headers Authorization, webhooks raw, keys, datos de tarjetas o binarios. Diagnosticar con IDs internos y codigos tecnicos seguros.

## Pagos fallidos o inciertos

Revisar aumento de simertpi.payments.failed/declined, webhooks.rejected y manual_review. Diferenciar rechazo funcional, provider UNCONFIGURED y resultado UNKNOWN. UNCONFIGURED no es un proveedor disponible y no afecta liveness. No aprobar por inferencia ni repetir ciegamente create.

Usar el endpoint administrativo existente POST /api/v1/admin/reconciliation/payments para diagnosticar inconsistencias internas. Revisar findings/auditoria mediante accesos administrativos actuales. Una aprobacion tardia sobre sesion cancelada requiere revision manual; no reactivar arbitrariamente ni alterar montos. Query/refresh CP12 solo aplica provider configurado y transiciones seguras.

## Notificaciones DEAD/UNKNOWN

Revisar simertpi.notifications.dead, permanent_failure, temporary_failure, no_destination, retry y destination_disabled. generated cuenta inbox; delivered cuenta entregas fisicas, por lo que multi-device aumenta delivered sin duplicar inbox.

Confirmar canal/provider/configuracion y preferencias antes de investigar retries. NO_DESTINATION/SUPPRESSED son terminales; no fabricar user:UUID como token. INVALID_DESTINATION desactiva solo ese device. DEAD agota retries; UNKNOWN no admite resend ciego sin garantia de idempotencia. Inbox permanece disponible. No revelar tokens/contactos al diagnosticar. No hay API de reparacion arbitraria o provider externo real.

## Findings de reconciliacion

Revisar simertpi.reconciliation.runs por type/result y simertpi.reconciliation.findings. findings cuenta observaciones del scan, no backlog abierto. Findings persistentes reaparecen en scans sin generar auditoria duplicada de estado.

Endpoints de diagnostico CP11, SIMERTPI_ADMIN: POST /api/v1/admin/reconciliation/payments, /evidence, /outbox. No permiten cambio arbitrario de estado. Evidence reporta metadata sin objeto, objeto sin metadata, hash/size mismatch. NO borrar/reemplazar evidencias o metadata ante incertidumbre. Outbox stale usa token/timeout/retry configurados; no relanzar workers manualmente modificando SQL sin analizar la operacion.

## Integridad de evidencia

Ante simertpi.evidence.integrity_failure o evidence.reconciliation_findings, conservar objeto y metadata, correlacionar descarga/upload y ejecutar diagnostico administrativo de evidencia. Comparar resultado de reconciliacion con auditoria; no registrar storageKey/hash/binarios en tags ni compartir paths. La descarga verifica bytes reales; mismatch impide descarga exitosa. Readiness no inspecciona inventario de storage.

## Scheduler fallido

Revisar simertpi.scheduler.runs con result=FAILED y duration por nombre controlado. Determinar si hubo excepcion global o fallo de item; control/outbox continuan otros items. Verificar configuracion enabled, frecuencias y parametros CP11 positivos. No interpretar ausencia de ejecuciones de un scheduler deshabilitado como fallo. Una duracion creciente puede indicar saturacion/DB lenta: correlacionar HTTP/Hikari y eventos sin payloads.

## DB no disponible

Readiness devuelve 503; liveness puede seguir UP. Verificar acceso/estado de PostgreSQL y datasource mediante herramientas operativas ya autorizadas, sin imprimir credenciales. No desactivar validacion Flyway ni cambiar ddl-auto para resolver un incidente. Recuperar DB y comprobar readiness antes de reintroducir trafico. La version vigente sigue V29.

## Alertas recomendadas para infraestructura futura

- Readiness DOWN sostenido y DB unavailable: retirar trafico/investigar dependencia, sin restart loop por liveness UP.
- HTTP 5xx y latencia: usar rate de http_server_requests_seconds_count y histogramas; umbral/ventana deben acordarse con SLO y volumen real.
- Payment failures y webhook rejection spike: increase/rate de contadores; distinguir rechazo esperado de fallo tecnico/ataque.
- Notification DEAD/UNKNOWN: alertar incrementos; backlog actual requiere gauge muestreado futuro, no inferirlo del contador acumulado.
- Reconciliation FINDINGS/FAILED: tasa de runs por type/result y cambios de findings; persistencia requiere revision.
- Scheduler FAILED o ausencia inesperada: comparar runs/duracion con configuracion habilitada y frecuencia efectiva.
- Evidence integrity failures: investigar cada nuevo fallo; no reparar contenido automaticamente.

No se fijan umbrales normativos ni reglas desplegadas de Alertmanager. Los nombres exportados usan underscores/_total; confirmar series reales en /actuator/prometheus. Los contadores se reinician al arrancar y no reemplazan auditoria/DB. Prometheus/Grafana/logging central/OpenTelemetry y collector autenticado quedan para infraestructura formal posterior.
