# CP23.2 — Cierre visual y reglas de tiempo/tarifa

Baseline: `develop / e77e692`, CP23.1 versionado. El gate inicial no encontró cambios de código; solamente las referencias visuales aportadas por el usuario.

## Referencias y alcance

La política anterior de bloqueo total de EXPIRED queda reemplazada por [CP23.2.1](checkpoint-23-2-1.md): gracia interna, actuación verbal humana y regularización por vencimiento. Tarifa, opciones y horarios no cambian.

Autoridad visual: `simertpi-citizen-app/docs/reference/boceto.jpeg` (12 paneles). Se inspeccionaron además las 21 capturas WhatsApp del 5 de octubre y el video `WhatsApp Video 2026-10-05 at 21.49.08.mp4`, mediante fotogramas de revisión. No se usan estas imágenes como assets de la aplicación.

La implementación conserva CP18.1, CP20.1, CP21–CP23 y Vehicle lifecycle/V31. No incluye CP24, proveedores nuevos, instalación, despliegue ni operaciones Git de escritura.

## Fuente normativa y límites

Fuente primaria: [Ordenanza sustitutiva publicada por el GAD de Piñas en 2021](https://pinas.gob.ec/images/2021/ORDENANZAS/124_ORDENANZA_SUSTITUTIVA_SIMERTPI-2021.pdf), artículos 15 y 26.

- Tarifa de referencia: USD 0,25/60 minutos; fracción mínima de 30 minutos.
- Máximo continuo: cuatro horas; gracia: diez minutos.
- Lunes–viernes 08:00–18:00; sábado/domingo/feriados nacionales 08:00–13:00.
- Exenciones expresas: 1 de enero, Viernes Santo, 8 y 9 de noviembre, 25 de diciembre.
- Tarjetas de seis horas no autorizan seis horas continuas.

Estos datos describen el texto publicado, sin certificar reformas o ajustes tarifarios posteriores. La comunicación 08:00–13:00 de lunes a viernes encontrada en la página de servicios corresponde a un contexto COVID y no sustituye el artículo 15. Pendiente: confirmación municipal de vigencia consolidada, calendario nacional anual/traslados, excepciones de alcaldía y redondeo monetario aplicable.

## Parametrización segura DEV

Se agrega `dev/pinas-ordinance-2021.sql` y el calendario de exenciones expresas, opt-in con:

```text
SPRING_PROFILES_ACTIVE=dev
SIMERTPI_DEV_SEED_ENABLED=true
SIMERTPI_DEV_PINAS_ORDINANCE_2021_ENABLED=true
```

Ambos flags están deshabilitados por defecto. El guard existente rechaza QA/UAT/PROD y perfiles mezclados antes de acceder a la BD. La ejecución mantiene la transacción y el advisory lock del seed.

Las tres tarifas `PIN-DEV-NORM-T01..03` son nuevas referencias de prueba, claramente identificadas con vigencia por verificar. Su `valid_from=2026-01-02` es una versión técnica DEV para priorizarlas sobre el fixture anterior; no es una fecha de vigencia municipal. Se conservan los IDs y valores de las tarifas anteriores, por lo que las sesiones existentes mantienen su política retenida. Solo se ajustan los 21 horarios con IDs deterministas del fixture DEV. Las exenciones se limitan a esas tres zonas y se crean idempotentemente para el año actual, anterior y dos posteriores. No se inventa el calendario nacional.

El motor BigDecimal/HALF_UP existente sigue calculando los importes; la UI no calcula dinero. Por ejemplo, una media hora produce USD 0,13 bajo ese redondeo existente. Este redondeo de centavos necesita validación municipal antes de usar la referencia en producción. Sin opt-in, USD 0,80/40 min, 5–120 min y calendario 24/7 continúan siendo exclusivamente fixtures técnicos DEV.

## Contratos y acciones

- `GET /api/v1/parking/rules/options?spaceId=...`: cotizaciones agrupadas válidas, filtradas y calculadas por el motor existente, con un instante común. Respuesta acotada a 288 opciones y cálculo sin overflow.
- `GET /api/v1/parking-sessions/{id}/extensions/options`: mismo patrón, con autenticación CITIZEN y ownership; conserva tarifa/política de la sesión.
- Los POST existentes vuelven a validar reglas, importe, horario, espacio/vehículo y concurrencia. Una opción visual no reserva ni garantiza un importe futuro.
- `GET /parking-spaces/availability` añade `endingSoonSeconds`, sin información del ocupante. Se usa el mayor adelanto de los recordatorios EXPIRATION activos: amarillo comienza cuando el primer recordatorio vence. Sin recordatorios configurados, se conserva `SIMERTPI_ENDING_SOON_SECONDS` como fallback UX, sin afirmar que existe una notificación.
- EXPIRED y ACTIVE/EXTENDED cuyo tiempo ya terminó no pueden cerrarse por un ciudadano. La validación es backend, con bloqueo de fila. Staff conserva el cierre operativo existente. COMPLETED conserva cierre repetido idempotente.
- Decisión final de producto: EXPIRED y MAX_TIME_REACHED no ofrecen acciones ciudadanas (ni extensión ni cierre). ACTIVE/EXTENDED solo se extienden antes de expectedEndAt. Gracia no permite recuperar el contrato vencido. Staff conserva el tratamiento operacional; no se inventa liberación automática.

## Flutter y experiencia

Tokens compartidos para superficies verde/amarilla/roja, AppBars azules, acciones azules, padding 16 y navegación compacta. Home conserva su controlador y varias sesiones. Sus tarjetas pasan de verde a amarillo y rojo con timestamps y umbral publicados; no liberan espacios localmente.

QR, zonas, espacios, detalle, vehículo, tiempo, resumen, procesamiento y éxito siguen la composición del boceto. Pines con punta y número, chips con texto, vehículos ocupados bloqueados y radios de selección. Cotizaciones por lote; ningún input técnico principal ni botón Consultar importe. La confirmación de pago requiere APPROVED y ACTIVE; la extensión exige pago aprobado y tiempo ampliado confirmado antes de mostrar éxito. Un pago aprobado sin ampliación confirmada mantiene la intención recuperable.

La navegación inferior retorna al Home existente mediante un notifier propiedad de Bootstrap; no agrega Home al stack. Se oculta durante operaciones bloqueadas. Notificaciones/Perfil siguen siendo placeholders honestos. Los temporizadores CP23 se cancelan en background/dispose y refrescan al agotarse el tiempo.

## Capturas y validación

`simertpi-citizen-app/build/cp23.2-review/`: capturas de widgets reales con fixtures explícitos; no validación física ni transacciones reales. El mapa de captura usa tiles neutros sin red: la cartografía real OSM sigue siendo exclusivamente DEV con su atribución. Las fuentes locales de captura son opt-in; la suite normal no depende de ellas. No se añade infraestructura golden ni paquetes productivos.

Pendiente revisión humana lado a lado y prueba física con el nuevo APK. Las coordenadas DEMO, su guard DEV y V31 permanecen intactos. Ver el reporte Flutter para matriz, resultados finales y APK.


## Cierre funcional final — 2026-10-06

La decisión explícita de producto establece USD 0.25/60 minutos, fracción de 30, máximo continuo 240, gracia operacional 10 y HALF_UP a dos decimales. No se afirma vigencia normativa consolidada 2026 por esa decisión. El seed de referencia se habilita mediante el flag DEV documentado arriba; sin habilitarlo la instalación conserva el dataset técnico anterior. No se modificó Render.

| Minutos | USD |
|---|---|
|30|0.13|
|60|0.25|
|90|0.38|
|120|0.50|
|150|0.63|
|180|0.75|
|210|0.88|
|240|1.00|

`Tariff.roundingMode` es configuración persistida por tarifa. `ParkingRulesService.calculateAmount` es la autoridad monetaria BigDecimal y devuelve escala 2; no se utilizan doubles ni precios Flutter. Sesiones existentes conservan su tarifa referenciada.

La elegibilidad de extensión se revalida al crear una intención bajo bloqueo: solo ACTIVE/EXTENDED con expectedEndAt futuro. Replay de una intención existente conserva idempotencia y recuperación; no constituye una nueva extensión. Un pago que ya estaba en curso mantiene su resolución contractual. Opciones adicionales se filtran por máximo acumulado y horario: 180 contratados admite 30/60; 210 admite 30; 240 ninguna.

La gracia permanece en política de control/sanción: no altera expectedEndAt ni elegibilidad de extensión. La proyección amarilla consume el umbral de reglas EXPIRATION activas (primer aviso temporal, máximo minutes_before); el fallback solo opera si no existe una regla de recordatorio. No se genera una notificación ficticia con ese fallback.

### Preparación del futuro administrador (sin implementar web ni endpoints nuevos)

- Tariff: zona, importe, unidad/duración, moneda, fracción mínima (actualmente minMinutes), máximo continuo, gracePeriodMinutes, roundingMode, active y validFrom/validTo. Las ofertas se derivan de esa fracción y se validan individualmente. Un mínimo independiente o un catálogo arbitrario de duraciones puede extender el contrato administrativo posteriormente, conservando la lista de ofertas que ya consume Flutter.
- Schedule: día de semana individual 1–7, inicio/fin, ámbito general/zona, activo y vigencia. Los horarios acordados siguen 08–18 lunes–viernes y 08–13 fines de semana en el seed de referencia.
- Holiday/fechas especiales: fecha, tipo, tarifa/exención, inicio/fin, zona/general, activo y vigencia. Exención y horario especial ya se resuelven backend; sin horario operacional no se permite iniciar. No se inventó el calendario anual nacional.
- NotificationRule: evento EXPIRATION, minutesBefore, activación, canales y vigencia; umbral Home/availability derivado de la misma configuración.
- Futuro CRUD debe conservar versiones usadas por sesiones anteriores y registrar actor autenticado, fecha y cambios con auditoría. No requiere recompilar Flutter por modificaciones de importes, horarios, umbrales ni ofertas devueltas.

MAX_TIME_REACHED requiere tratamiento operacional y movimiento del vehículo; no se crea cancelación o cierre ciudadano. Flutter muestra mensajes distintos de EXPIRED. Ambas cards son rojas y no contienen controles interactivos. Contador local solo presenta timestamps backend y refresca al vencimiento/resume, sin polling agresivo.
