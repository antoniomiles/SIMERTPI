# CP23.1 — Rediseño operacional App Ciudadano

## Baseline y alcance

develop / 2fb466c — feat: add active parking extension and completion flow.
Gate 0: limpio; CP23 versionado; diff check limpio. No git add/commit/push.
La composición textual aprobada de 12 pantallas tiene prioridad visual.
No CP24 completo: Historial/Notificaciones/Perfil son accesos honestos sin datos ficticios.
No se modificaron Render, auth, VehiclePlate, uppercase, baja lógica, V31 ni proveedores.

## Ordenanza

PARAMETRIZACIÓN NORMATIVA PENDIENTE DE FUENTE VERIFICADA.
No se encontró documentación normativa verificable en repositorio. Ningún valor del
boceto se convirtió en tarifa/tiempo municipal. Duración/fracción/límites/horario,
moneda y cotización provienen de GET /api/v1/parking/rules, validado nuevamente por
backend. Flutter no calcula dinero ni el máximo/nuevo expectedEndAt.
Los valores CP21.5 siguen siendo prueba NO OFICIAL (USD .80/40 min, mínimo 5,
máximo 120, gracia 3 y horarios diarios DEV). No se atribuyen a la ordenanza.

## Contratos y disponibilidad

Detalles backend, tabla de coordenadas y fuentes:
[backend checkpoint-23-1](../../simertpi-backend/docs/checkpoint-23-1.md).

ParkingCatalogService incorpora GET /api/v1/parking-spaces/availability en la carga
real de zonas/calles/espacios. El DTO se une por ID/código a un único snapshot:
operationalStatus, selectable, expectedEndAt, remainingSeconds y coordenadas.
Faltantes/duplicados/valores contradictorios fallan de forma segura.
Lista/detalle/markers utilizan SpaceStatus y spaceColor compartidos.

| Proyección | Texto | Selección |
| --- | --- | --- |
| AVAILABLE | Disponible (verde) | Sí |
| ENDING_SOON | Próximo a finalizar (ámbar) | No |
| OCCUPIED | Ocupado (rojo) | No |
| DISABLED | No habilitado (gris) | No |
| UNKNOWN | No disponible (gris) | No |

PENDING_PAYMENT reserva, no muestra información financiera de otros ciudadanos.
EXPIRED/MAX_TIME_REACHED siguen ocupados. COMPLETED/CANCELLED liberan.
active sigue siendo habilitación administrativa, nunca disponibilidad.
El umbral backend SIMERTPI_ENDING_SOON_SECONDS=600 por default es criterio UX,
no norma municipal. Tiempo contratado restante nunca promete liberación.
No se muestran placa/usuario/email/Payment/sessionId del ocupante.

## Mapa

Reutiliza flutter_map, MapConfig y OSM público exclusivamente DEV; atribución visible.
No Google Maps, nueva key, proveedor PROD ni GPS obligatorio.
Nueve puntos aproximados del seed backend son DEMO/DEV/NO OFICIALES, autorizados
para pruebas. No están en widgets. No representan un levantamiento de plazas.
Los espacios sin coordenadas/proveedor siguen consultables en Lista.
Bounds se derivan de coordenadas reales entregadas por backend.
Markers >=48, texto/semántica e icono además de color; tap permite revisar ocupados,
pero su botón No disponible está deshabilitado. Verde puede continuar al formulario.
Refresh al abrir/volver/resume/cambiar zona; sin polling de disponibilidad.
Si falla refresh, contenido anterior queda advertido explícitamente como no confirmado,
con selección deshabilitada. Código/QR resuelven exclusivamente mediante backend.

## Pantallas y navegación

1. Inicio: saludo de GET /users/mine (principal autenticado), Qué deseas hacer,
   CP23 ActiveParkingPanel primero si existe, ESTACIONAR, vehículos/mapa y accesos futuros.
2. Estacionar: Dónde vas a estacionar, QR o búsqueda manual, lógica existente CP20.
3. Zonas: Lista/Mapa; nombres y cantidades reales derivadas del catálogo.
4. Espacios lista: código/calle/estado/tiempo backend; no promesas de liberación.
5. Mapa: misma proyección/colores; bottom sheet con selección o detalle bloqueado.
6. Detalle ocupado: estado/tiempo/no seleccionable, sin información del ocupante.
7. Vehículo: selección propia real, candado y Estacionado actualmente si existe operación.
8. Duración: límites dinámicos e input validado; consultar importe backend.
9. Resumen/pago: zona/espacio/vehículo/duración/total backend. Continuar al pago crea
   PENDING_PAYMENT con contrato CP21 y abre CP22, sin afirmar estacionamiento iniciado.
10. Procesando: spinner, Procesando tu pago..., espera/no cerrar y autoridad backend.
11. Éxito: exclusivamente Payment APPROVED + ParkingSession ACTIVE reconsultados.
    Texto exacto: Vehículo estacionado correctamente. Espacio/vehículo/tiempo reales.
12. Aceptar vuelve a Inicio; Home conserva su instancia y refresca CP23 mediante
    señal de confirmación scoped al bootstrap, sin otro modelo de parking activo.

NavigationBar persistente en las ramas principales Inicio/Mapa/Notificaciones/Perfil.
Mapa abre la instancia de discovery existente en IndexedStack, creada al primer acceso.
Cambiar tabs no empuja rutas ni duplica Home/auth/controllers. Android Back de otro tab
regresa a Inicio; vehículo/duración/resumen conservan contexto al retroceder.
Notificaciones/Perfil muestran únicamente estará disponible próximamente; Historial
informa lo mismo. Logout permanece en Home. Rutas mantienen guards CP18.1.
Formularios/detalles transaccionales conservan navegación jerárquica, sin nav duplicada.

## Vehículos y concurrencia

No cambió Mis vehículos: título limpio, placa/datos/menú y un solo Agregar inferior
para 0/1/N, VehicleFormPage existente, baja lógica/uppercase/historial intactos.
Seleccionar vehículo consulta sesiones propias por userId autenticado y trabaja por
vehicleId; cinco estados ocupantes bloquean la asociación, no el usuario completo.
Dos vehículos distintos del mismo ciudadano siguen permitidos.
Antes de crear, se reconsulta espacio, vehículos, ocupación, reglas e importe.
Backend mantiene bloqueo de Vehicle, exclusión por espacio V21 e idempotencia;
añade exclusión por vehículo bajo ese bloqueo. 409 semánticos se traducen a:
- Este espacio ya no está disponible. Selecciona otro estacionamiento.
- Este vehículo ya tiene un estacionamiento en curso.
- Este espacio no está habilitado actualmente.
Tras conflicto se reconsulta el snapshot; no POST repetido automático.

## Pago / CP23

No cambia método/proveedor/importe de CP12/CP22. Conserva intención durable, clave
idempotente, double-submit, recovery y UNKNOWN/timeout honestos.
Pago APPROVED sin ACTIVE no muestra Vehículo estacionado correctamente.
PENDING/UNKNOWN/DECLINED/FAILED nunca muestran ese éxito.
CP23 extender/cerrar siguen usando sus contratos; expectedEndAt/cierre son backend.
Tras aceptar pago se refrescan Home y mapa conservado; al entrar en selección se
reconsulta ocupación del vehículo. Finalizar/Extender refrescan sesión CP23;
volver/entrar en Mapa o selección reconsulta disponibilidad sin reiniciar app.
EXPIRED/MAX no se liberan por contador. Contador CP23 deriva de expectedEndAt,
resincroniza al cero/resume y no determina estado backend.

## Design System / accesibilidad

Tokens operacionales centralizados; sin sombras pesadas/gradientes/DS paralelo.
AppCard usa Material para mantener tinta visible de ListTile.
Controles existentes >=48, labels/estado textual/semántica, contador CP23 accesible.
Layout scroll y adaptación a 320x640, 640x320 landscape y textScale 200% mediante
tests de Home/nav, selección/duración/resumen, payment, discovery y lifecycle vehículos.
Figma: intento metadata rechazado por MCP Starter tool call limit; sin reintentos.
VALIDACIÓN FIGMA PENDIENTE. Boceto aprobado + tokens existentes utilizados.

## Validación

Backend dirigido: 27 PASS. Suite Maven completa: 436 PASS, 0 errores/fallos/skips.
Frontend final: flutter test 205 PASS (174 existentes + 31 nuevos), flutter analyze limpio.
Regresión dirigida final: 92 PASS. dart format lib test: 78 archivos, 0 cambios al cierre.
APK DEV: PASS, assembleDebug 150.6 s, build/app/outputs/flutter-apk/app-debug.apk.
ENVIRONMENT=dev; API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1; MAP_SOURCE=osm-dev.
No instalada automáticamente. Sin prueba E2E física en esta ejecución.
git diff --check: PASS. Índice vacío; HEAD 2fb466c sin cambio.
Working tree final: 32 modificados + 9 nuevos, exclusivamente CP23.1; sin staging.
Tests nuevos cubren disponibilidad/markers/privacidad indirecta, estados ocupantes,
vehicleId, estados terminales, cambios entre selección y POST, Android Back,
éxito condicionado, tabs, Home retenido y responsive. Fixtures HTTP actualizados
para availability y consulta de ocupación; no se eliminaron tests existentes.

Build DEV (PowerShell, desde simertpi-citizen-app):
```powershell
flutter build apk --debug `
  --dart-define=ENVIRONMENT=dev `
  --dart-define=API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1 `
  --dart-define=MAP_SOURCE=osm-dev
```
Salida esperada: build/app/outputs/flutter-apk/app-debug.apk. No instalación automática.

## Deuda / validación física posterior

- Fuente normativa, parametrización oficial y posiciones levantadas pendientes.
- OSM público no es configuración PROD; proveedor contratado permanece pendiente.
- Figma limitado, iOS no validado desde Windows.
- Contador CP23 depende del reloj del dispositivo, sin autoridad transaccional.
- Proyección actual carga catálogo completo; paginación/viewport futuro si crece.
- Reservas PENDING requieren resolución backend existente, no cancelación local inventada.
- Redeploy necesario para availability/exclusión por vehículo/seed; no Render modificado.
- E2E físico: PENDIENTE. Instalar manualmente, auth, vehículo, Lista/Mapa, QR,
  seleccionar, duración, quote, PENDING_PAYMENT, payment sandbox DEV controlado,
  APPROVED+ACTIVE, Aceptar/Home, espacio ocupado/vehículo bloqueado, Extender/Finalizar
  y reconsulta de espacio/vehículo. Sin proveedor real inventado ni pago productivo.
No CP24, git add, commit ni push.

## Revisión PRE-COMMIT final

Baseline conservado: develop / 2fb466c; índice vacío.
Corregido: Ver detalle del mapa abre el detalle, no la selección de vehículo.
El panel conserva el resultado consultado durante un refresh del catálogo.
Prueba de regresión dirigida: PASS. Suite Flutter final: 206 PASS; analyze limpio.
Maven final: 436 PASS, sin fallos, errores ni omitidos.
Format final: 78 archivos, 0 cambios. No se recompiló ni instaló APK en esta revisión.
No se cambiaron tarifas, tiempos, horarios, ordenanza ni migraciones.
Deuda explícita: Parametrizar tarifas, tiempos, horarios y demás reglas conforme a la
Ordenanza SIMERTPI vigente de Piñas.
Sin git add, commit, push, deploy ni CP24.
