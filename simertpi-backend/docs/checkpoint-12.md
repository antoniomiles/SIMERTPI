# Checkpoint 12: capa de integracion de pagos

CP12 no implementa una integración real con Banco Pichincha.
La integración concreta requiere documentación oficial,
credenciales/sandbox y mecanismo de autenticación/verificación
proporcionados por el proveedor.

## Contrato y seleccion

PaymentProvider expone createPayment, queryPayment y cancelPayment mediante DTOs propios: UUID internos, importe BigDecimal, moneda backend, referencia, clave de operacion y correlation ID. ProviderPaymentStatus distingue PENDING, APPROVED, DECLINED, FAILED, CANCELLED y UNKNOWN. No hay JSON bancario, SDK, URLs, headers bancarios ni refund real en el contrato.

PaymentProviderRegistry selecciona `simertpi.payments.provider` (SIMERTPI_PAYMENT_PROVIDER), por defecto UNCONFIGURED. Proveedores inexistentes/no configurados devuelven 503 PAYMENT_PROVIDER_UNAVAILABLE, sin llamada externa, pago aprobado ni activacion. Codigos duplicados de adapters fallan al arrancar. BANCO_PICHINCHA no tiene implementacion ni configuracion sembrada.

## Sandbox y produccion

SandboxPaymentProvider requiere perfil dev o test y `simertpi.payments.sandbox.enabled=true` (SIMERTPI_PAYMENT_SANDBOX_ENABLED). La presencia de cualquier otro perfil, combinado con dev/test, se rechaza; sin perfil permitido el bean no se crea. El resultado `simertpi.payments.sandbox.outcome` permite probar deterministicamente APPROVED, DECLINED, PENDING y FAILED. Las referencias sandbox usan UUIDs tecnicos, no tarjetas ni identificadores bancarios.

Solo los recursos de tests activan test + SANDBOX_STUB. La configuracion principal mantiene UNCONFIGURED y sandbox deshabilitado. No se agregan PAN, CVV, API keys, certificados ni secretos.

## Creacion, consulta y transiciones

POST /api/v1/payments conserva Idempotency-Key y validaciones de propiedad/sesion/tarifa existentes. Monto y moneda provienen de CreatePaymentService; datos adicionales del cliente no alteran el precio. PaymentIntegrationService separa:

1. Persistir pago/attempt e idempotencia.
2. Reclamar una unica operacion bajo bloqueo de sesion/pago, con UUID estable del attempt.
3. Llamar al adapter fuera de transaccion DB.
4. Aplicar respuesta en una nueva transaccion, validando identidad y generacion de operacion.

create/refresh suspenden cualquier transaccion del llamador. Un error externo incierto deja UNKNOWN tecnico + PROCESSING funcional; no se repite create. Un proceso caido tras REQUESTED tampoco redispara create: debe consultar/revisar. queryPayment recibe identificador externo cuando se conoce y el request interno con clave estable para correlacionar resultados inciertos; el futuro adapter debe soportar una consulta documentada por referencia o devolver UNKNOWN.

GET /api/v1/payments/{id} y POST /api/v1/payments/{id}/refresh son CITIZEN y validan propietario. Query usa el proveedor persistido, no la seleccion global vigente. Una respuesta PENDING confirma espera sin activar; APPROVED usa PaymentService y sus invariantes de sesion/extension; DECLINED/FAILED no activan; CANCELLED libera una sesion inicial pendiente. UNKNOWN requiere revision, nunca aprueba.

PaymentStatus mantiene la politica central y permite PROCESSING -> PENDING cuando el proveedor confirma espera. APPROVED/REFUNDED/CANCELLED no retroceden automaticamente. Se refresca el estado bajo locking y se confirma JPA antes de devolver metadata JDBC actualizada.

## Idempotencia, retries e identidades externas

Se reutilizan idempotencia y attempts existentes. El UUID de attempt es la clave estable de operacion enviada al adapter; replays/concurrencia no generan otra llamada. Un retry explicito tras FAILED/DECLINED confirmado puede crear otro attempt; nunca tras resultado incierto.

V28 agrega provider_payment_id, provider_operation_key y provider_operation_status a payments.payments, con indices unicos para identidad externa por proveedor y clave de operacion. El UUID SIMERTPI sigue siendo PK. No cambia V1-V27 ni siembra configuracion bancaria.

Cada respuesta create/query queda vinculada al attempt consultado. Si ya existe un retry posterior, la respuesta anterior se registra para revision sin cambiar la operacion actual, incluso si su estado es UNKNOWN. Se conserva el identificador externo en payment_attempts.provider_transaction_id; el identificador de transaccion/referencia aprobado del pago conserva su significado existente. El proveedor de un pago logico se mantiene entre attempts. Un bloqueo asesor por proveedor/identidad comprueba tambien referencias historicas, evitando reutilizarlas en otras operaciones. Las referencias solo pueden ser identificadores seguros, acotados y sin caracteres de control; los futuros adapters no deben devolver tokens de checkout o credenciales como referencia.

## Webhook

POST /api/v1/payments/webhooks/{provider} permite recibir callbacks sin HTTP Basic; la autorizacion efectiva exige proveedor reconocido y exactamente un verifier que lo soporte. ClosedPaymentWebhookVerifier nunca reconoce/confia en proveedores. No existe verifier productivo en CP12.

PaymentWebhookVerifier recibe codigo, headers y RAW BODY. Solo un adapter verificado produce VerifiedPaymentEvent. Puede vincular la clave de operacion a partir de correlacion autentica del proveedor; sin ella debe existir una identidad externa ya persistida. Un callback sin vinculacion suficiente se rechaza, sin aprobar por inferencia. El verifier de tests esta exclusivamente en src/test y no representa un algoritmo bancario.

El cuerpo se lee acotado (64 KiB tecnicos, configurable por simertpi.payments.webhooks.max-body-bytes). Se reutiliza la unicidad provider + externalEventId de V8 y locking asesor para concurrencia. La columna payload guarda SHA-256 de bytes originales, nunca raw body ni headers. Replays identicos no transicionan/auditan dos veces; contenido distinto con el mismo evento devuelve 409. Fechas UTC con precision de microsegundos conservan respuesta estable tras persistencia PostgreSQL.

## CP11, auditoria y seguridad

Timeout, query y webhook siguen el orden de bloqueo sesion -> pago. Una aprobacion tardia no reactiva CANCELLED: preserva referencia en el attempt y registra revision manual. CP11 detecta incertidumbre, aprobaciones tardias y conflictos del proveedor. No se agrega consulta bancaria al scheduler interno de CP11.

Eventos relevantes: PAYMENT_PROVIDER_REQUESTED, PAYMENT_PROVIDER_PENDING, PAYMENT_APPROVED, PAYMENT_DECLINED, PAYMENT_FAILED, PAYMENT_PROVIDER_CANCELLED, PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED, PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED y PAYMENT_WEBHOOK_REJECTED. Se conserva actor/correlation ID. Replays y scans consistentes no duplican eventos. No se registran raw responses, cuerpos, Authorization, firmas, mensajes arbitrarios del proveedor ni secretos. DTO publico mantiene solo metadata funcional existente.

PaymentTransportSettings prepara connect-timeout/read-timeout explicitos, sin defaults productivos. Environment: SIMERTPI_PAYMENTS_CONNECT_TIMEOUT / SIMERTPI_PAYMENTS_READ_TIMEOUT, formato ISO Duration. Ejemplos exclusivamente DEV: PT3S y PT20S. No existe cliente HTTP externo ni retries ciegos.

## Pruebas y deuda

Pruebas HTTP/PostgreSQL y unitarias cubren outcomes, fail-closed, perfiles, precios backend, ownership, create concurrente, incertidumbre, query, verificadores, replays conflictivos, auditoria segura, query vs webhook, timeout vs approval y respuestas de attempts antiguos sobre retries nuevos. Las regresiones se mantienen; CP11 acepta Flyway posterior a V27. PostgreSQL local 16.15, sin Testcontainers. No se crean temporales ni archivos de evidencia para CP12.

Pendiente: adapter/verifier real, protocolo oficial de consulta por referencia, autenticacion, timeouts apropiados, garantias de idempotencia externa y pruebas contractuales en sandbox oficial. La proteccion local evita redispatch, pero no sustituye la garantia idempotente del proveedor ni permite inferir resultados externos. Refund real y resolucion financiera de conflictos quedan fuera del alcance. Filas legacy sin operacion externa no se despachan ni refrescan automaticamente.

## Resultado final

Estado: PASS.

- mvn test: BUILD SUCCESS, 272 tests, 0 failures, 0 errors, 0 skipped.
- Se agregan 30 casos HTTP/integracion y 10 unitarios; CP1-CP11 siguen verdes.
- Flyway valida 28 migraciones; ultima aplicada V28. V1-V27 sin modificaciones.
- PostgreSQL local 16.15 (localhost:5432/simertpi), sin Testcontainers.
- git diff --check sin incidencias; sin temporales nuevos en el repositorio.
- Las ejecuciones preliminares detectaron el mapping PENDING y problemas de fixture/precision temporal en replay; se corrigieron y no quedan tests fallidos.
- No se agregaron secretos o credenciales de proveedor ni endpoints bancarios.
- No se ejecutaron git add, git commit ni git push.


## Archivos creados (12)

- docs/checkpoint-12.md
- src/main/java/ec/gob/simertpi/application/payments/PaymentIntegrationService.java
- src/main/java/ec/gob/simertpi/application/payments/PaymentProviderRegistry.java
- src/main/java/ec/gob/simertpi/application/payments/PaymentProviderRequest.java
- src/main/java/ec/gob/simertpi/application/payments/PaymentProviderUnavailableException.java
- src/main/java/ec/gob/simertpi/application/payments/PaymentTransportSettings.java
- src/main/java/ec/gob/simertpi/application/payments/ProviderPaymentStatus.java
- src/main/java/ec/gob/simertpi/application/payments/webhook/VerifiedPaymentEvent.java
- src/main/java/ec/gob/simertpi/infrastructure/payments/SandboxPaymentProvider.java
- src/main/resources/db/migration/V28__payment_provider_operations.sql
- src/test/java/ec/gob/simertpi/api/payments/PaymentProviderHttpIntegrationTest.java
- src/test/java/ec/gob/simertpi/application/payments/PaymentProviderContractTest.java

## Archivos modificados (17)

- src/main/java/ec/gob/simertpi/api/payments/PaymentController.java
- src/main/java/ec/gob/simertpi/api/payments/webhook/PaymentWebhookController.java
- src/main/java/ec/gob/simertpi/application/payments/CreatePaymentService.java
- src/main/java/ec/gob/simertpi/application/payments/PaymentProvider.java
- src/main/java/ec/gob/simertpi/application/payments/PaymentProviderResult.java
- src/main/java/ec/gob/simertpi/application/payments/webhook/ClosedPaymentWebhookVerifier.java
- src/main/java/ec/gob/simertpi/application/payments/webhook/PaymentWebhookService.java
- src/main/java/ec/gob/simertpi/application/payments/webhook/PaymentWebhookVerifier.java
- src/main/java/ec/gob/simertpi/application/reconciliation/PaymentReconciliationService.java
- src/main/java/ec/gob/simertpi/config/SecurityConfig.java
- src/main/java/ec/gob/simertpi/domain/payments/entity/Payment.java
- src/main/java/ec/gob/simertpi/domain/payments/entity/PaymentStatus.java
- src/main/resources/application.yml
- src/test/java/ec/gob/simertpi/api/payments/OperationalRecoveryPostgresIntegrationTest.java
- src/test/java/ec/gob/simertpi/application/payments/webhook/PaymentWebhookServiceTest.java
- src/test/java/ec/gob/simertpi/domain/payments/PaymentStatusTest.java
- src/test/resources/application.properties
