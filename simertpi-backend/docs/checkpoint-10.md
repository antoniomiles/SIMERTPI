# Checkpoint 10 — Motor de reglas operativas

## Diseño

`ParkingRulesService` centraliza calendario, selección de tarifa, cálculo, vencimiento, máximo continuo y gracia. `ParkingRulesResult` devuelve metadata funcional y códigos de motivo. Creación, solicitud/aprobación de extensión y control consumen el motor. Las consultas no generan auditoría funcional.

## Precedencia y vigencia

1. Excepción/feriado válido de zona; en su ausencia, excepción general de V23.
2. Horario excepcional, si existe. Un feriado no tarifado impide vender una sesión tarifada.
3. Calendario semanal de zona vigente para la fecha; solo en su ausencia se usa calendario general. Un horario de zona cerrado no habilita fallback general.
4. Tarifa activa de zona vigente al instante; en su ausencia, tarifa general vigente.
5. Dentro del mismo alcance se elige la tarifa con `valid_from` más reciente. Dos tarifas del mismo alcance con idéntico inicio de vigencia producen `AMBIGUOUS_ACTIVE_TARIFF`.

Los horarios incluyen apertura y excluyen cierre. La vigencia de calendario/feriados y tarifa incluye sus extremos, conservando la semántica previa. No hay reglas especiales quemadas para sábados, domingos ni feriados. Las ventanas semanales múltiples se conservan; no se vende tiempo que cruce el cierre de la ventana seleccionada.

## Parámetros y cálculo monetario

Se reutilizan `amount`, `duration_minutes`, `min_minutes` y `max_continuous_minutes` de Tariff. `min_minutes` representa duración mínima aceptada y fracción de cobro. Una duración inferior se rechaza; las demás se facturan por fracciones completas:

```text
minutosFacturados = ceil(minutosSolicitados / min_minutes) * min_minutes
importe = amount * minutosFacturados / duration_minutes
```

Se usa exclusivamente BigDecimal, escala 2 compatible con NUMERIC(12,2), y `rounding_mode` configurado para el redondeo monetario final. La aritmética de fracciones usa long y operaciones exactas. No se aceptan importes negativos. El vencimiento usa los minutos solicitados, no los minutos redondeados para facturación. `unitPrice` corresponde a `unitDurationMinutes`.

La ausencia de tarifa, máximo, gracia, moneda o redondeo impide operar; se devuelven códigos como `NO_ACTIVE_TARIFF`, `NO_MAX_CONTINUOUS_CONFIGURATION`, `NO_GRACE_CONFIGURATION`, `NO_CURRENCY_CONFIGURATION` y `NO_ROUNDING_CONFIGURATION`. No se inventan valores normativos.

## Sesiones, máximo y gracia

Las sesiones nuevas validan el motor y que la tarifa enviada coincida con la seleccionada por precedencia. Se conserva PENDING_PAYMENT y el total cero hasta crear el pago. El pago inicial de una tarifa configurada usa el mismo cálculo del motor; tarifas históricas sin redondeo configurado conservan el comportamiento previo para sesiones ya existentes.

Las extensiones conservan la tarifa referenciada por la sesión, que debe seguir habilitada y vigente para nuevas ventas. Se bloquea la sesión al solicitar extensión y se valida el tiempo exacto entre `startedAt` y el nuevo vencimiento: no se truncan segundos al comprobar máximo/gracia. El nuevo vencimiento debe ser futuro y respetar el cierre. La aprobación vuelve a validar el motor y el vencimiento persistido.

La gracia se lee de `tariffs.grace_period_minutes`, tanto en extensiones como en control; cero solo tiene significado si se configura explícitamente. El control de sesiones existentes conserva la política de su tarifa referenciada aunque haya finalizado su vigencia comercial. Si falta política de control, puede registrar la expiración del tiempo comprado, pero no supone gracia ni emite sanción por exceso; devuelve `NO_CONTROL_CONFIGURATION`.

Por compatibilidad estricta se ajustó el pago de extensión para aceptar una sesión expirada autorizada por el motor y para permitir una extensión después del pago inicial aprobado. Se mantiene el rechazo de pagos pendientes simultáneos. No se integró ni implementó proveedor de pago.

## Fecha y API

Los instantes de evaluación/vencimiento se representan con Instant; el calendario se resuelve mediante ZonedDateTime. Propiedad `simertpi.parking.rules.time-zone`, configurable con `SIMERTPI_PARKING_TIME_ZONE`; el valor DEV del YAML es America/Guayaquil.

`GET /api/v1/parking/rules`: exactamente uno de `spaceId` o `qrCode`; `at` opcional en ISO-8601 con offset/UTC y `durationMinutes` opcional. Sin duración devuelve la política sin importe ni vencimiento. Las reglas no resueltas devuelven HTTP 200 con `operational=false` y `reasonCode`; selectores inválidos devuelven 400 y recurso inexistente 404. Las operaciones de sesión rechazan reglas no resueltas antes de persistir.

Acceso para CITIZEN, INSPECTOR, SUPERVISOR y SIMERTPI_ADMIN; anónimo 401. Se expone código funcional de tarifa, no tariffId, entidades JPA, timestamps de administración ni referencias normativas internas.

## Flyway y compatibilidad

V26 es aditiva y necesaria: permite `schedules.zone_id=NULL` para calendario general, añade vigencia opcional de calendario y añade `tariffs.zone_id`, `currency`, `rounding_mode` y `grace_period_minutes`. Tiene constraints e índice, sin tarifas, horarios, feriados ni valores normativos sembrados. V1–V25 no se modifican. Filas existentes conservan sus datos y los nuevos campos quedan NULL hasta configurarse formalmente.

La única adaptación en el test de CP9 es comprobar una versión Flyway mínima de V25 en lugar de exigir que siga siendo la última. No se modificó el código ni la cobertura funcional de evidencia.

## Pruebas y validación

32 tests del motor cubren horario, feriados tarifados/no tarifados, excepciones, vigencia, prioridades, fracciones, cálculo BigDecimal, redondeo, máximo, gracia, configuración incompleta, espacio/QR, timezone, cierre y extensiones. Se añaden dos casos de control (gracia parametrizada y ausente) y cuatro de integración HTTP/PostgreSQL: consulta sin ruido de auditoría, autorización/selectores, rechazo de creación y coherencia cálculo/pago/extensión.

Se conservan los escenarios anteriores, adaptando sus fixtures para delegación al motor. Una ejecución acotada inicial tuvo ocho errores de stubbing anidado de Mockito; las fixtures se corrigieron sin quitar pruebas.

Resultado final: `mvn test` — BUILD SUCCESS, 173 tests, 0 failures, 0 errors, 0 skipped; duración 02:35 min, finalizado 2026-10-03T15:44:07-05:00. PostgreSQL local 16.15, sin Testcontainers. Flyway validó las 26 migraciones y confirmó V26. `git diff --check`: exit code 0. Las 135 pruebas previas se mantienen y se añaden 38 casos.

## Archivos de esta revisión

Creado:

- `src/main/java/ec/gob/simertpi/api/parking/ParkingRulesController.java`
- `src/main/java/ec/gob/simertpi/application/parking/rules/ParkingRulesService.java`
- `src/main/java/ec/gob/simertpi/application/parking/rules/ParkingRulesResult.java`
- `src/main/resources/db/migration/V26__add_operational_rules_configuration.sql`
- `src/test/java/ec/gob/simertpi/application/parking/rules/ParkingRulesServiceTest.java`
- `docs/checkpoint-10.md`

Modificado:

- `src/main/java/ec/gob/simertpi/application/parking/ParkingSessionService.java`
- `src/main/java/ec/gob/simertpi/application/parking/calendar/ParkingCalendarService.java`
- `src/main/java/ec/gob/simertpi/application/parking/control/ParkingControlEvaluationService.java`
- `src/main/java/ec/gob/simertpi/application/parking/extension/ParkingSessionExtensionService.java`
- `src/main/java/ec/gob/simertpi/application/payments/CreatePaymentService.java`
- `src/main/java/ec/gob/simertpi/application/payments/PaymentService.java`
- `src/main/java/ec/gob/simertpi/config/SecurityConfig.java`
- `src/main/java/ec/gob/simertpi/domain/parking/entity/Schedule.java`
- `src/main/java/ec/gob/simertpi/domain/parking/entity/Tariff.java`
- `src/main/java/ec/gob/simertpi/domain/parking/repository/ScheduleRepository.java`
- `src/main/resources/application.yml`
- `src/test/java/ec/gob/simertpi/api/enforcement/EnforcementHttpPostgresIntegrationTest.java` (solo expectativa Flyway)
- `src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/application/parking/ParkingSessionServiceTest.java`
- `src/test/java/ec/gob/simertpi/application/parking/control/ParkingControlEvaluationServiceTest.java`
- `src/test/java/ec/gob/simertpi/application/parking/extension/ParkingSessionExtensionServiceTest.java`
- `src/test/java/ec/gob/simertpi/application/payments/PaymentServiceTest.java`

## Deuda técnica

- La normativa 2026 requiere confirmación formal y carga administrativa de parámetros. CP10 no certifica ni inventa esos valores.
- Los campos nuevos se configuran en DB: no se añade un CRUD administrativo en este alcance. El endpoint administrativo previo de tarifa no completa por sí solo esos campos; una fila incompleta no habilita nuevas sesiones.
- No hay snapshot inmutable de toda la política en la sesión; se conserva la tarifa por referencia. La administración debe versionar tarifas y evitar mutar filas referenciadas.
- Los códigos y bandas históricas de exceso y el aviso previo del control de CP7 permanecen; parametrizarlos completamente queda fuera de este alcance.
- No se añaden horarios nocturnos que crucen medianoche; se conserva el modelo existente start_time < end_time.

No se ejecutaron git add, commit ni push. Alcance limitado al Checkpoint 10.
