# Checkpoint 21 — Preparación de estacionamiento

Baseline develop / 20d5dfd; working tree inicialmente limpio. Backend inspeccionado
sin cambios; no nuevas migraciones, no Maven repetido, no git add/commit/push.

## Descubrimiento y contratos reales

| Capacidad | Método /api/v1/... | Contrato / autorización | Uso |
|---|---|---|---|
| Ubicación | GET parking-spaces/code/{code}, qr/{qrCode}; zones; streets | Catálogos públicos, active y relaciones | Metadata fresca CP20 |
| Reglas/cotización | GET parking/rules?spaceId=...&durationMinutes=... | CITIZEN y roles municipales definidos; ParkingRulesResult | Autoridad de calendario/precio/límites |
| Tarifa identificada | GET tariffs/code/{code} | Público; TariffResponse con UUID, code único V6 | Resolver ID del código elegido por reglas |
| Vehículos propios | GET vehicles/user/{userId} | Usuario autenticado, ownership backend | Reutiliza VehicleService CP19 |
| Crear vehículo | POST vehicles | CITIZEN, CreateVehicleRequest existente | Formulario CP19 y retorno al contexto |
| Crear solicitud | POST parking/sessions | CITIZEN, Idempotency-Key obligatorio | Crear PENDING_PAYMENT, no activar |
| Consultar resultado | GET parking-sessions/user/{userId}; parking-sessions/{id} | Ownership/staff según servicio | Recuperación propia con GET, sin historial UI |
| Pago | POST payments, GET payments/{id}, POST payments/{id}/refresh | Contratos reales inspeccionados | Ninguno consumido; CP22 pendiente |

ParkingSessionController/ParkingRulesController/TariffController, DTOs, servicios,
repositorios, entidades, SecurityConfig, GlobalExceptionHandler, V6/V7/V15/V21/V22/
V23/V26, tests HTTP de creación/concurrencia, ParkingRulesServiceTest y docs CP10
revisados. PaymentController/CreatePaymentService/PaymentService y recovery
inspeccionados únicamente para conocer el orden transaccional.

POST request exacto: parkingSpaceQrCode, vehicleId, tariffId, durationMinutes.
No userId/importe/moneda de cliente. SessionResponse aporta UUIDs, startedAt,
expectedEndAt, endedAt, status, totalAmount, extensionCount, fechas y campos
ciudadanos code/QR/plate/tariffName/durationMinutes cuando aplica.

RulesResult: zoneId/spaceId/evaluatedAt, operational/chargeable/holiday,
applicableSchedule(startTime/endTime/source), applicableTariff (CODE, no UUID),
minimumFractionMinutes, maximumContinuousMinutes, gracePeriodMinutes, currency,
unitPrice/unitDurationMinutes, calculatedAmount, requestedDurationMinutes,
billedDurationMinutes, expiresAt, extensionAllowed y reasonCode.
Tariff UUID está @JsonIgnore: GET tariffs/code resuelve ese código único, sin
replicar selección por zona/vigencia ni elegir una tarifa arbitraria.

## Reglas, calendario y dinero

Motor CP10 resuelve excepciones/feriados antes de calendario zona/general;
tarifa zona/general y vigencias, ambigüedad y configuración ausente fallan cerrado.
Timezone backend SIMERTPI_PARKING_TIME_ZONE (DEV America/Guayaquil configurable).
Frontend omite at: tiempo y operación actuales los decide backend. Muestra ventana
y offset devueltos; instantes de fin/inicio se muestran con hora del dispositivo
etiquetada. No calendario municipal ni tarifa normativa codificados.

Duración: entero de minutos, mínimo min_minutes y máximo max_continuous_minutes
del resultado. Backend acepta no múltiplos y factura por fracciones; input acepta
minutos enteros (incremento contractual de 1 minuto), no presupone incrementos de
30/60. Límites locales solo feedback; GET y POST revalidan. No controles horarios
calculados por Flutter. Fracción/tiempo facturado/precio son valores backend.

Extensión mínima ApiClient: responseDecoder opcional, propagado en retry 401.
Decoder parking preserva lexemas decimales antes de jsonDecode: dinero como String,
sin conversión doble, sin cálculo/redondeo monetario cliente, sin dependencia nueva.
DTOs incompletos/negativos o inesperados no producen cotización utilizable.
Precio es ESTIMACIÓN, nunca compromiso cobrado: POST backend reevalúa tarifa,
CreatePaymentService calculará el importe definitivo en CP22. Session.totalAmount
inicial 0 no se muestra como pago gratuito o importe acordado.

## Orden y consistencia transaccional

SpaceSelectionPage -> ruta protegida ParkingPage -> ubicación revalidada,
vehículos propios/habilitados -> reglas y mínimo inicial -> consulta de importe
tras editar duración -> resumen -> acción explícita Preparar solicitud.
Si un solo vehículo está habilitado se selecciona y muestra placa; varios exigen
selección, sin principal inventado. Vacío permite registro real y retorno sin
perder espacio/duración. Backend valida user/vehicle ownership y active.

Antes del POST: nueva metadata del espacio y relaciones, vehículos actualizados,
reglas actuales; si cambian precio/moneda/tarifa/límites/horario, exige nueva revisión.
Espacio distinto/inactivo o vehículo cambiado no generan POST. Backend autoridad
final: índice UNIQUE parcial V21 protege espacio para PENDING_PAYMENT/ACTIVE/
EXTENDED/EXPIRED/MAX_TIME_REACHED; violación y precheck -> 409. No bloqueo cliente
pretende resolver la carrera. No constraint de unicidad por vehículo/usuario:
no se inventa esa política. active != available, no etiqueta Libre.

Al crear: PENDING_PAYMENT, startedAt=ahora y expectedEndAt según reglas. El tiempo
previsto empieza ANTES de pago y el espacio queda ocupado según backend; resumen
lo explica. Back tras crear no cancela. No endpoint para cancelar pendiente se
implementa; close existente solo permite estados activos/expirados. Backend CP11
puede recuperar pendientes con timeout configurable (no se configura/inventa aquí).
Aprobación de pago activa ACTIVE en backend; CP21 no invoca pagos ni activa sesión.

## Idempotencia, fallo y persistencia

Key aleatoria criptográfica por intención, payload congelado; backend scope
user+operation+hash y TTL 24 h según implementación actual. AsyncButton y
controlador bloquean doble submit. Nunca retry automático por timeout/5xx.
Antes de POST, intención mínima en flutter_secure_storage ya instalado, aislada
por environment/API/user/space. Sin password/tokens ni logging del payload.
Una respuesta POST perdida/inválida es UNCERTAIN, no fallo definitivo ni éxito.
Reentrada restaura intención y consulta GET propios, no reenvía POST. Coincidencia
única PENDING_PAYMENT de user/space/vehicle/tariff/duración permite mostrar solicitud
backend existente; no prueba identidad exacta de key, no infiere aprobación.
Vacío/otro estado/múltiples coincidencias/error conservan incertidumbre y registro,
impiden nueva creación sobre esa intención y ofrecen consulta manual/soporte.
Sin endpoint lookup por Idempotency-Key no se puede demostrar rollback desde un GET
vacío; se documenta gap, sin workaround que duplique operaciones. Key no se reusa
tras TTL porque no se reenvía en recuperación. Persistencia fallida bloquea POST.
Session owner/tariff/space/state/timestamps validados; legacy tariffId null permitido
solo en lectura y nunca satisface intención nueva. Logout/guards previos intactos.

## UX, Figma y pruebas

Un intento get_metadata oficial devolvió límite MCP Starter. No frames CP21
obtenidos; PENDIENTE VALIDACIÓN VISUAL FIGMA. Design System CP17–20 conservado.
Campo común AppTextField amplía opcionalmente onChanged/inputFormatters para minutos.
Skeleton de carga inicial; consulta de duración conserva secciones, progreso contextual,
errores/retry/conflicto e incertidumbre explícitos. Sin delays/datos demo en app.
PopScope impide abandonar durante submit; Back previo no muta backend.

Pruebas nuevas controlador/widgets/HTTP: cotización exacta, límites/no múltiplos,
reglas cambiantes, metadata/vehículo deshabilitados, ownership, vacío/selección,
doble acción, conflictos, almacenamiento fallido, offline, respuesta perdida y
restauración, GET de recuperación, decimal decoder conservado durante refresh,
resumen y responsive 320×640/640×320 a 200% con teclado.
Smoke AVD existente: HTTP fixture real dentro del test, login/secure restore,
mapa/lista/espacio -> vehículo/reglas/resumen -> un POST de prueba PENDING_PAYMENT,
QR lookup controlado -> resumen sin segundo POST; vehículos/formulario/logout.
No pago/proveedor/productivo ni datos creados en PostgreSQL. QR físico no validado.

Validación final real: flutter pub get OK; dart format --set-exit-if-changed .
61 archivos, 0 cambios; flutter analyze: No issues found; flutter test: 99 PASS
(78 anteriores + 21 nuevos), 0 failures/errors/skipped; flutter build apk --debug
OK, assembleDebug 269,6 s. Advertencia de acceso nativo Gradle/JDK existente,
sin error ni cambio de toolchain. Smoke Android integration_test/mobile_smoke_test.dart
PASS (1 prueba) en emulator-5554; fixtures HTTP controlados, sin backend productivo.
git diff --check OK; cambios sin stage únicamente en Flutter y esta documentación.
Backend sin cambios: Maven no repetido. Sin nuevas dependencias ni migraciones.
Estado: PASS CON OBSERVACIONES por Figma MCP e iOS no validable desde Windows;
QR físico pendiente, fuera del alcance transaccional CP21. Artefactos de build y
capturas temporales de tests limpiados al finalizar; sin git add/commit/push.

## Gaps y siguiente frontera

CP21.1 opcional: lookup ciudadano por operation/key con resultado inequívoco,
identidad/vigencia de cotización si se necesita precio vinculante, recuperación de
intención antes de POST tras cierre abrupto y cancelación explícita de pendiente.
Un intento durable cuyo POST nunca llegó puede requerir soporte; se mantiene
bloqueado ante incertidumbre, no se borra para volver a crear automáticamente.
No historial completo ni recuperación de pago incluidos. Configurar timeout de
pendientes antes de piloto con ciudadanos, según política autorizada, fuera de CP21.
Figma, QR físico e iOS siguen pendientes; proveedor PROD cartográfico externo no
cambia. CP22 deberá consumir sesión real pendiente y revalidar estado/importe con
backend; methods/payment/result/receipts NO implementados. No CP21.1/CP22 automático.
