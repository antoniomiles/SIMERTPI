# CP21.5 — Dataset operativo DEV Piñas

Baseline develop/e60c73d. No datos oficiales municipales ni tarifa normativa.
La geografía (García Moreno, Sucre y Bolívar) se verificó el 2026-10-05 en
[EMGIRZAPP-EP, rutas de Piñas](https://www.emgirzapp.gob.ec/rutas_pinas/).
La dirección de la agencia [BanEcuador Piñas](https://www.banecuador.fin.ec/store/pinas/)
también aparece como García Moreno entre Sucre y Bolívar (índice público;
la apertura directa devolvió timeout). No se deduce autorización para estacionar,
tramos oficiales, inventario de plazas ni tarifas vigentes de esas referencias.

## Activación y protección

No existía seed operativo; V3 solo crea roles y V26 no siembra normativa.
`DevParkingSeed` es ApplicationRunner condicional a `SIMERTPI_DEV_SEED_ENABLED=true`.
Default false en application.yml. Exige perfil activo dev; solo dev y test pueden
coexistir. Si se habilita con prod/qa/uat/otro perfil o sin dev, falla antes de SQL.
No endpoint público, SQL remoto manual, usuario sembrado, credencial ni migración.

Después de revisión humana, desplegar el backend y habilitar únicamente en Render
DEV `SPRING_PROFILES_ACTIVE=dev` y `SIMERTPI_DEV_SEED_ENABLED=true`. El runner se
ejecuta después de Flyway. Este trabajo no cambia Render ni hace commit/push.
No habilitar este flag en QA/UAT/PROD. Se puede deshabilitar después de la carga;
los registros persisten y no se borran automáticamente.

SQL UTF-8 `src/main/resources/dev/pinas-parking.sql`, transacción y advisory lock
PostgreSQL para arranques concurrentes; UUIDs deterministas y ON CONFLICT(id) DO
NOTHING. Restart no duplica ni sobreescribe anotaciones/desactivaciones manuales.
Una colisión de código con un UUID ajeno aborta la transacción: no se reasigna
silenciosamente un espacio. Seed v1 no es mecanismo de reparación ni reset de datos.

Modelo usado: Zone → Street → ParkingSpace, tarifa específica de zona, 7 schedules
por zona. No entidad ParkingRule persistida: CP10 evalúa catálogo/calendario/tarifa.
V5/V6/V7/V16/V21/V22/V23/V26/V30 y FK/unique existentes conservadas.

## Catálogo

| zoneCode | street | spaceCode | qrCode |
|---|---|---|---|
| PIN-DEV-Z01 | García Moreno | PIN-DEV-001 | SIMERTPI-DEV-PIN-001 |
| PIN-DEV-Z01 | García Moreno | PIN-DEV-002 | SIMERTPI-DEV-PIN-002 |
| PIN-DEV-Z01 | García Moreno | PIN-DEV-003 | SIMERTPI-DEV-PIN-003 |
| PIN-DEV-Z02 | Sucre | PIN-DEV-004 | SIMERTPI-DEV-PIN-004 |
| PIN-DEV-Z02 | Sucre | PIN-DEV-005 | SIMERTPI-DEV-PIN-005 |
| PIN-DEV-Z02 | Sucre | PIN-DEV-006 | SIMERTPI-DEV-PIN-006 |
| PIN-DEV-Z03 | Bolívar | PIN-DEV-007 | SIMERTPI-DEV-PIN-007 |
| PIN-DEV-Z03 | Bolívar | PIN-DEV-008 | SIMERTPI-DEV-PIN-008 |
| PIN-DEV-Z03 | Bolívar | PIN-DEV-009 | SIMERTPI-DEV-PIN-009 |

Tres calles, tres zonas DEV, nueve espacios STANDARD con numeración DEV-01/02/03.
Coordenadas NULL: no hay mediciones verificadas de los espacios. Flutter conserva
fallback listado; no se afirma mapa con markers para estos datos. active != available.
QR opaco exacto, no URL, 20 caracteres, no firma/criptografía inventada.

Tarifas PIN-DEV-T01/T02/T03: **TARIFA DE PRUEBA / NO OFICIAL**, USD 0.80 por
40 minutos, fracción mínima 5, máximo continuo 120, grace 3, rounding HALF_UP,
vigencia desde 2026-01-01, sin fin. 5 minutos cotizan 0.10 por backend. Valores
elegidos para validar fracción/redondeo, no representan precios municipales.
Calendario por zona de lunes a domingo 00:00–23:59:59, sin feriados simulados.
El backend rechaza duración que cruce el cierre; no se afirma operación 24h exacta.
America/Guayaquil según configuración existente. Ningún cálculo en Flutter.

## Hoja QR offline

Abrir [dev-pinas-qrs.html](dev-pinas-qrs.html) en navegador, imprimir o mostrar en
otra pantalla. Nueve SVG con quiet zone; sin CDN, requests, URL embebida ni secretos.
Se generó del SQL, no de una segunda lista editable. Regeneración opcional:

```sh
python -m pip install qrcode==8.2
python scripts/dev/print_qrs.py
```

qrcode 8.2 (BSD) es herramienta QA opcional, no dependencia Maven/Flutter/runtime.
[Fuente y API](https://pypi.org/project/qrcode/8.2/). La validación física del scanner
se realizará después de redeploy; generar SVG no equivale a escaneo confirmado.

## Prueba posterior al redeploy

1. APK DEV, Internet y usuario autorizado ya registrado; auth/Home remotos fueron
   confirmados por el propietario. No requieren PC/backend local.
2. Mis vehículos → Registrar → placa de prueba DEV autorizada y campos reales;
   guardar, comprobar listado. No editar/eliminar/principal inventados.
3. Buscar estacionamiento → zona DEV → calle/listado, o buscar PIN-DEV-001.
4. Escanear SIMERTPI-DEV-PIN-001 en la hoja offline; backend resuelve metadata.
5. Seleccionar espacio → vehículo → reglas/duración válida → importe → resumen.
6. Preparar solicitud → PENDING_PAYMENT. No afirmar ACTIVE/pagado. Si 409, elegir
   otro espacio: las solicitudes pendientes también ocupan según backend.
7. Detener aquí para validar CP21 físico antes de probar payment CP22.

Tests de seed: perfil/default, relaciones/códigos/QR, concurrencia/idempotencia,
UTF-8, API por code/QR, registro/login Bearer, vehículo POST/GET, cotización y
creación/replay PENDING_PAYMENT sin Payment. DB efímera PostgreSQL 16.15; no Render.
Validación dirigida: BUILD SUCCESS, 6 tests (3 unitarios + 3 PostgreSQL/Testcontainers),
0 failures, 0 errors, 0 skipped. Flyway migra y valida V1–V30 sobre schema vacío.
El flujo HTTP con registro, Bearer, vehículo, código/QR, reglas y cotización llegó
a PENDING_PAYMENT sin crear pagos. Idempotencia y concurrencia del seed verificadas.
Flutter previo a CP22: analyze limpio y 103 tests verdes. Suite backend global y
validación final Flutter/APK se documentan al cierre de esta ejecución.
Suite backend final después de incorporar los 3 escenarios Bearer CP22:
BUILD SUCCESS, 414 tests, 0 failures, 0 errors y 0 skipped; Flyway validate V30,
PostgreSQL 16.15 Testcontainers. No cambio funcional en contratos de pagos.

Físico pendiente después de redeploy: vehículo, zonas/listado/mapa, code/QR,
cotización y PENDING_PAYMENT. Pendientes pueden bloquear plazas hasta resolución;
timeout CP11 sigue sin activarse automáticamente. No reset/borrado de datos.

## Actualización CP23.1 — mapa y disponibilidad DEV

El seed ahora completa las nueve coordenadas DEV cuando ambas están NULL.
Son DEMO / DEV / NO OFICIALES, no levantamiento municipal ni plazas físicas verificadas.
No reemplaza coordenadas informadas. No se carga en QA/UAT/PROD.
Tabla y fuentes de contexto: [checkpoint-23-1.md](checkpoint-23-1.md).
La disponibilidad se consulta mediante GET /api/v1/parking-spaces/availability
autenticado CITIZEN. active sigue siendo habilitación; no equivale a libre.
SIMERTPI_ENDING_SOON_SECONDS (default 600) es un umbral UX configurable, no ordenanza.
Las tarifas/horarios anteriores siguen siendo exclusivamente datos técnicos DEV.
PARAMETRIZACIÓN NORMATIVA PENDIENTE DE FUENTE VERIFICADA.
