# Checkpoint 13 - Capa de integracion de notificaciones

CP13 no integra proveedores externos reales de Push,
WhatsApp ni Email. Las integraciones concretas requieren
selección oficial del proveedor, credenciales, sandbox y
documentación técnica.

## Arquitectura y proveedores

Los eventos siguen entrando por el outbox de CP7/CP11. NotificationGenerationService crea el inbox y prepara deliveries en la misma transaccion local. NotificationDeliveryService es una fachada del NotificationDispatcher; no conserva un segundo flujo legacy de envio. Se retiraron los tres senders cerrados y sus contratos anteriores, sustituidos por NotificationProvider y DTOs propios. El dominio no depende de SDK, HTTP ni credenciales de un proveedor.

El registry resuelve por canal y codigo configurado. PUSH, WHATSAPP y EMAIL son canales independientes. UNCONFIGURED es el valor predeterminado en los tres. Un provider desconocido o de otro canal nunca recibe la solicitud ni produce DELIVERED. UNCONFIGURED produce un fallo tecnico con retry acotado para permitir corregir configuracion; un codigo desconocido produce fallo permanente.

SandboxNotificationProvider no realiza llamadas externas. Requiere habilitacion explicita y que todos los perfiles activos sean dev/test; perfiles vacios, productivos o mezclados rechazan su habilitacion al iniciar y su operacion. Su outcome configurable admite DELIVERED, TEMPORARY_FAILURE, PERMANENT_FAILURE, INVALID_DESTINATION y UNKNOWN; una configuracion desconocida tambien queda UNKNOWN. Las referencias sandbox se derivan de la delivery, nunca aparentan mensajes de un proveedor real.

## Devices y preferencias

POST/GET /api/v1/notifications/devices y DELETE /api/v1/notifications/devices/{id} requieren CITIZEN. El propietario proviene de la autenticacion. Plataformas: ANDROID, IOS, WEB. El token se valida como dato opaco limitado a 4096 caracteres sin controles. SHA-256 y un constraint global unico impiden duplicados; advisory lock transaccional serializa registros simultaneos del mismo token. No se permite apropiarse de un token de otro usuario ni cambiar su plataforma mediante replay. Re-registro propio reutiliza UUID, actualiza lastSeenAt y permite reactivacion. DELETE desactiva logicamente. Las lecturas devuelven una mascara constante ***, nunca el token.

GET/PUT /api/v1/notifications/preferences operan exclusivamente sobre el usuario autenticado. PUT acepta un mapa canal/boolean, por ejemplo {"PUSH":true,"EMAIL":false}. Ausencia de preferencia significa envio externo deshabilitado; el inbox sigue existiendo. Actualizaciones se serializan por usuario, auditando solo cambios efectivos.

notification_rules incorpora classification (INFORMATIONAL, TRANSACTIONAL, SECURITY, LEGAL) y mandatory. Ninguna regla se vuelve obligatoria por su nombre o clasificacion: solo mandatory configurado prevalece sobre preferencias. No se decide obligatoriedad municipal ni se siembran reglas normativas. El dispatcher vuelve a evaluar vigencia, canal/evento de regla, preferencia y destino antes de cada intento. Un envio ya iniciado no puede retirarse si la preferencia cambia durante la llamada; los siguientes intentos si respetan el cambio.

## Deliveries y dispatcher

Una notificacion funcional PUSH produce una delivery por dispositivo activo; el inbox no se multiplica. EMAIL y WHATSAPP requieren un contacto valido (formato basico email y telefono internacional + seguido de 8-15 digitos). No hay destino ficticio user:UUID. NO_DESTINATION y SUPPRESSED son terminales; reactivar una preferencia no reenvia retroactivamente notificaciones ya suprimidas.

El dispatcher adopta tambien pendientes antiguos sin deliveries: conserva intentos, encola solo cuando no hubo envio o el provider no estaba configurado, y deja UNKNOWN/auditoria cuando el resultado anterior es incierto. No vuelve a enviar notificaciones SENT/READ. Mientras una delivery siga UNCONFIGURED puede resolver configuracion nueva sin cambiar su UUID; una operacion que ya uso un provider mantiene el codigo original.

Una delivery guarda referencia interna de dispositivo o fingerprint del contacto, nunca el destino completo. El token operativo reside exclusivamente en devices; email/telefono permanecen en users. Los cambios de contacto no redirigen silenciosamente una delivery anterior: si no coincide el fingerprint, queda NO_DESTINATION. El constraint notification_id/channel/destination_reference evita duplicados y cada retry conserva UUID. Los registros se reclaman con FOR UPDATE SKIP LOCKED y processing_token, confirmando PROCESSING antes de llamar al provider. La llamada externa se ejecuta fuera de una transaccion DB. La finalizacion comprueba el token de reclamacion y persiste resultado/auditoria atomicamente.

DELIVERED es terminal. INVALID_DESTINATION desactiva solo el dispositivo correspondiente y evita retry. Fallos permanentes no reintentan. Fallos temporales usan NotificationRetryPolicy, compartida con NotificationOutboxProcessor: limite y backoff lineal configurados en simertpi.outbox. Al agotarlos se pasa a DEAD. Excepciones, resultados nulos/desconocidos o respuestas incoherentes quedan UNKNOWN y requieren revision; no se interpreta exito.

PROCESSING abandonado usa processing-timeout-seconds de CP11. Solo providers que declaren supportsIdempotency permiten retry usando la misma referencia; otros quedan UNKNOWN sin repetir automaticamente una operacion de resultado incierto. El token evita que un worker tardio sobrescriba la recuperacion. Esto no promete exactly-once externo sin garantias reales del proveedor.

El scheduler existente de retry ahora procesa deliveries y recupera stale processing. simertpi.notifications.dispatcher.enabled permite deshabilitarlo; frecuencia: simertpi.notifications.retry.fixed-delay-ms. El outbox mantiene su scheduler y su recuperacion CP11. Tests deshabilitan ejecucion automatica y disparan workers de forma controlada.

## Templates, inbox, privacidad y auditoria

Templates internos de texto plano mantienen title/message y variables. NotificationTemplateRenderer exige variables presentes y escalares, sustituye una sola vez, escapa etiquetas en valores y limita longitud. eventType lo establece el backend. No hay IDs externos de templates ni interpretacion HTML. Una configuracion de template incompleta falla controladamente en outbox; no inventa variables.

GET /mine y PATCH /{id}/read se conservan. READ/readAt no se sobrescriben al completar envios. El estado agregado SENT indica al menos una entrega confirmada; las deliveries individuales conservan fallos parciales. Una entrega fallida nunca elimina el inbox.

No se registran tokens, contactos completos, cuerpos ni payloads de proveedor. Request.toString omite destino/cuerpo. Se persisten solo codigos tecnicos sanitizados y referencias externas limitadas. Auditoria relevante: DEVICE_REGISTERED, DEVICE_DISABLED, PREFERENCE_CHANGED, DELIVERED, PERMANENT_FAILURE, RETRY_EXHAUSTED, DESTINATION_DISABLED y DELIVERY_REVIEW_REQUIRED (con prefijo NOTIFICATION_). Incluye IDs, canal, proveedor y resultado, sin destinos ni secretos. Locks/transiciones terminales evitan duplicacion por replay; lecturas/polling vacio no generan auditoria. Se conserva correlationId de HTTP/scheduler.

## Configuracion y migracion

simertpi.notifications.providers.push/whatsapp/email aceptan codigo; todos UNCONFIGURED por defecto. simertpi.notifications.sandbox.enabled=false por defecto. No se agregaron URLs, cuentas, templates externos ni credenciales. Los futuros adaptadores deben recibir secretos desde configuracion externa y respetar idempotencia/privacidad.

V29 es aditiva: devices, preferences, deliveries y campos classification/mandatory de reglas, con constraints e indices para propiedad, deduplicacion, due retries y stale processing. V1-V28 no cambian. Las tablas nuevas usan JDBC transaccional como el outbox; no almacenan raw responses.

## Validacion y deuda tecnica

Pruebas unitarias de contrato/providers/perfiles/templates y HTTP/PostgreSQL de devices, preferences, multicanal, multi-device, no destino, invalidacion, backoff, DEAD, resultados inciertos, auditoria, propiedad, concurrencia y Flyway. Se mantiene cobertura existente adaptando generacion para encolado local. La prueba CP12 de version Flyway acepta versiones posteriores a V28 por compatibilidad estricta.

Resultado final: `mvn test` BUILD SUCCESS, 342 tests (70 nuevos), 0 failures, 0 errors, 0 skipped. PostgreSQL local 16.15 en localhost:5432/simertpi, sin Testcontainers. Flyway valida las 29 migraciones, version final V29. `git diff --check` termina con codigo 0. No se agregaron credenciales, endpoints externos ni archivos temporales; no se ejecutaron git add/commit/push.

Pendientes: seleccion y adaptadores reales; credenciales externas; verificacion de contactos/consentimiento conforme politica oficial; cifrado de tokens en reposo/rotacion y retencion operativa; definicion normativa de reglas obligatorias; gestion administrativa de UNKNOWN/DEAD y alertas; garantias reales de idempotencia y delivery receipts. El sandbox no prueba latencia, cuotas ni entrega fisica externa.

## Inventario de cambios

### Archivos creados (14)

- `docs/checkpoint-13.md`
- `src/main/java/ec/gob/simertpi/api/notifications/NotificationSettingsController.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationDispatcher.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationProvider.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationProviderRegistry.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationProviderRequest.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationProviderResult.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationRetryPolicy.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationSettingsService.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationTemplateRenderer.java`
- `src/main/java/ec/gob/simertpi/infrastructure/notifications/SandboxNotificationProvider.java`
- `src/main/resources/db/migration/V29__notification_provider_deliveries.sql`
- `src/test/java/ec/gob/simertpi/api/notifications/NotificationProviderHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/application/notifications/NotificationProviderContractTest.java`

### Archivos modificados (12)

- `src/main/java/ec/gob/simertpi/application/notifications/NotificationDeliveryService.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationGenerationService.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationOutboxProcessor.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationRetryScheduler.java`
- `src/main/java/ec/gob/simertpi/application/reconciliation/RecoveryConfiguration.java`
- `src/main/java/ec/gob/simertpi/config/SecurityConfig.java`
- `src/main/java/ec/gob/simertpi/domain/configuration/entity/NotificationRule.java`
- `src/main/resources/application.yml`
- `src/test/java/ec/gob/simertpi/api/notifications/NotificationHttpPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/payments/PaymentProviderHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/application/notifications/NotificationGenerationServiceTest.java`
- `src/test/resources/application.properties`

### Contratos/senders retirados (6)

- `src/main/java/ec/gob/simertpi/application/notifications/NotificationChannelSender.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationSendRequest.java`
- `src/main/java/ec/gob/simertpi/application/notifications/NotificationSendResult.java`
- `src/main/java/ec/gob/simertpi/infrastructure/notifications/EmailNotificationSender.java`
- `src/main/java/ec/gob/simertpi/infrastructure/notifications/PushNotificationSender.java`
- `src/main/java/ec/gob/simertpi/infrastructure/notifications/WhatsAppNotificationSender.java`
