# CP23.1 — Disponibilidad operacional

Baseline: develop / 2fb466c (`feat: add active parking extension and completion flow`).
Gate 0: working tree limpio, CP23 versionado, diff check limpio. No staging/commit/push.

## Autoridad normativa

PARAMETRIZACIÓN NORMATIVA PENDIENTE DE FUENTE VERIFICADA.

No se encontró una ordenanza verificable en los documentos/configuración del repositorio.
El motor CP10 sigue siendo autoridad contractual. No se parametrizó una tarifa municipal
sin evidencia. El seed conserva únicamente sus valores técnicos NO OFICIALES:
USD 0.80 por 40 minutos, fracción mínima 5 minutos, máximo continuo 120,
gracia 3 y siete horarios diarios 00:00–23:59:59. No representan una ordenanza,
zonas tarifarias oficiales ni autorización municipal para estacionar en estas calles.

## Contrato

`GET /api/v1/parking-spaces/availability`, autenticación y autoridad CITIZEN.
La excepción específica precede al matcher histórico de catálogos públicos.
No acepta userId ni consulta datos privados del ocupante.

Respuesta: lista de DTO con parkingSpaceId, streetId, zoneId, spaceCode, active,
operationalStatus, selectable, expectedEndAt, remainingSeconds, latitude, longitude,
evaluatedAt. Una consulta SQL proyecta el mismo snapshot para lista/mapa.
No contiene sessionId, vehicleId, placa, nombre, username, correo, userId ni Payment.

| Condición backend | Proyección | Seleccionable |
| --- | --- | --- |
| Espacio/calle/zona habilitados, sin sesión ocupante | AVAILABLE | Sí |
| ACTIVE/EXTENDED, remaining > 0 y <= umbral | ENDING_SOON | No |
| PENDING_PAYMENT | OCCUPIED, sin tiempo financiero | No |
| ACTIVE/EXTENDED restantes | OCCUPIED | No |
| EXPIRED/MAX_TIME_REACHED | OCCUPIED, incluso con tiempo agotado | No |
| Espacio/calle/zona inactivos | DISABLED | No |
| COMPLETED/CANCELLED | No ocupan; AVAILABLE si habilitado | Sí |

`SIMERTPI_ENDING_SOON_SECONDS` configura `simertpi.parking.availability.ending-soon-seconds`.
Default 600 segundos. Criterio UX, NO ordenanza. Un valor negativo impide arrancar.
Tiempo restante significa tiempo contratado, nunca promesa de liberación.
`active != available` sigue vigente; active es habilitación administrativa.

`GET /api/v1/users/mine` entrega solo username/firstName del principal autenticado
CITIZEN para el saludo del dashboard. No implementa el perfil CP24.

## Seguridad transaccional

Create conserva ownership, vehículo activo, tarifa/calendario/reglas backend,
Idempotency-Key y PENDING_PAYMENT. Añade exclusión de sesiones ocupantes por vehicleId
bajo el bloqueo pesimista de la fila Vehicle que ya existía. Dos vehículos del mismo
usuario siguen pudiendo crear operaciones distintas. La matrícula no es identidad global.
V21 mantiene la exclusión concurrente por espacio. V31/lifecycle no se modificaron.
Errores 409 incorporan código seguro VEHICLE_OCCUPIED/SPACE_UNAVAILABLE/SPACE_DISABLED;
Flutter traduce esos códigos, nunca muestra SQL/excepciones ni datos de otro usuario.
No se agregó migración ni se cambió Flyway V1–V31.

## Coordenadas DEMO / DEV / NO OFICIALES

Autorizadas expresamente por el usuario para probar el mapa. Son puntos urbanos
aproximados, no levantamiento de las calles ni posiciones verificadas de plazas físicas.
El seed sigue protegido por perfil DEV + enabled explícito; QA/UAT/PROD excluidos.
Se actualizan solo códigos y QR DEV conocidos con ambas coordenadas NULL.
No reemplaza coordenadas ya informadas, no duplica registros ni cambia IDs/historial.

| Zona | Calle | SpaceCode | Latitude | Longitude |
| --- | --- | --- | --- | --- |
| PIN-DEV-Z01 | García Moreno | PIN-DEV-001 | -3.6801000 | -79.6816500 |
| PIN-DEV-Z01 | García Moreno | PIN-DEV-002 | -3.6802000 | -79.6816500 |
| PIN-DEV-Z01 | García Moreno | PIN-DEV-003 | -3.6803000 | -79.6816500 |
| PIN-DEV-Z02 | Sucre | PIN-DEV-004 | -3.6799800 | -79.6815500 |
| PIN-DEV-Z02 | Sucre | PIN-DEV-005 | -3.6799800 | -79.6814500 |
| PIN-DEV-Z02 | Sucre | PIN-DEV-006 | -3.6799800 | -79.6813500 |
| PIN-DEV-Z03 | Bolívar | PIN-DEV-007 | -3.6804000 | -79.6815500 |
| PIN-DEV-Z03 | Bolívar | PIN-DEV-008 | -3.6804000 | -79.6814500 |
| PIN-DEV-Z03 | Bolívar | PIN-DEV-009 | -3.6804000 | -79.6813500 |

Fuentes geográficas de contexto, no evidencia de parqueo oficial:
- [Puntos públicos de recaudación](https://www.derechosintelectuales.gob.ec/wp-content/uploads/2022/06/PUNTOS_DE_RECAUDACION.pdf): García Moreno entre Sucre/Bolívar.
- [Directorio notarial El Oro](https://www.funcionjudicial.gob.ec/images/directorio-notarial/notarias%20ELORO2018.pdf): Sucre entre García Moreno/Juan León Mera.
- [Referencia urbana de Piñas](https://www.geodatos.net/coordenadas/ecuador/pinas): centro aproximado -3.68017,-79.68169.

Flutter recibe estas coordenadas mediante el DTO; ninguna está hardcodeada en widgets.
Sin coordenadas o proveedor válido, sigue disponible la lista con estados backend.
OSM público conserva atribución y limitación exclusiva DEV; no constituye proveedor PROD.

## Validación backend

Dirigidos: 27 PASS (proyección, creación HTTP/PostgreSQL y seed).
Suite completa después del último cambio productivo: Maven BUILD SUCCESS,
436 tests, 0 failures, 0 errors, 0 skipped (PostgreSQL Testcontainers).
Incluye estados ocupantes/terminales, threshold, privacidad, autenticación,
exclusión por vehículo y carreras por vehículo/espacio, replay idempotente,
9 coordenadas distintas, relaciones/seed DEV e idempotencia.
Los escenarios independientes del test histórico de creación ahora cierran sus
fixtures entre operaciones para respetar la exclusión por vehículo.
No se modificó Render ni se ejecutaron operaciones remotas.

## Observaciones

No elimina automáticamente reservas PENDING_PAYMENT. Resolución/cancelación/timeout
son responsabilidad de contratos y configuración existentes CP11/CP12.
La proyección es una foto del estado; la creación backend revalida bajo concurrencia.
No garantiza cuándo se liberará un espacio. Se requiere redeploy y validación física
con sandbox DEV controlado; no se declara E2E físico PASS.
Figma MCP Starter rechazó el intento con límite de llamadas. VALIDACIÓN FIGMA PENDIENTE.
La composición aprobada de 12 estados y el Design System existente guían Flutter.
