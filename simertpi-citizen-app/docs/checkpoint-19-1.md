# Checkpoint 19.1 — Cierre visual y UX

Estado: PASS CON OBSERVACIONES (Figma/assets y limitación ambiental del emulador).

Baseline comprobado: `develop / f6c3135`, working tree limpio. Alcance exclusivo
Home/Vehículos y validación UX. Backend, autenticación, contratos y payloads no
se modificaron. No se ejecutaron git add/commit/push ni se inició CP20.

## Figma y diferencias

Se reintentaron contexto y screenshot de Home `5:627` y Vehículos `5:673`,
archivo oficial SIMERTPI, página `01 — App Ciudadano`. Todas las llamadas fueron
rechazadas: **"You've reached the Figma MCP tool call limit on the Starter plan."**
No se recorrieron otras páginas ni se afirma una nueva inspección del diseño.
Home usa exclusivamente la referencia y valores verificados durante CP19.
**PENDIENTE VALIDACIÓN VISUAL FIGMA** para listado, vacío, formulario y estados.

Correcciones verificables frente a la referencia Home de CP19: branding 23 bold,
secciones 19 bold, placa 17 bold y descripción 13; tokens tipográficos centralizados
en AppTypography. Enlaces de Home alineados con el margen de contenido sin perder
su target táctil; separación del marcador y texto QR con spacing existente.
No se agregan colores, assets, navegación ni funcionalidad. Cards, inputs,
botones, radios y estados reutilizan el Design System CP17–CP19.

OBSERVACIÓN UX/FIGMA mantenida: CTA con texto oscuro sobre cyan para contraste,
centrado según convención existente, layout adaptable y vacío honesto en lugar
del vehículo ficticio del diseño. No nuevas excepciones de identidad.
Inter permanece pendiente: no existe asset oficial/licenciado en el proyecto y
el límite impide reconfirmación en este checkpoint. **TIPOGRAFÍA INTER PENDIENTE
DE ASSET OFICIAL**; fallback de plataforma, sin descarga dinámica o nueva dependencia.
Branding textual SIMERTPI conservado; no logo municipal inventado.

## Formulario, estados y accesibilidad

Formulario conserva placa obligatoria y marca/modelo/color opcionales, límites
10/100/100/50 y valores exactos; no normalización normativa inventada. No campos
nuevos ni edición/eliminación/desactivación/vehículo principal. Estos últimos son
**GAPS FUNCIONALES FUTUROS**, no defectos CP19.1. Sin acceso nuevo a Figma, no puede
confirmarse un gap de campos de su formulario: no se infiere ni implementa.

INITIAL/LOADING con skeleton, SUCCESS/EMPTY/ERROR, REFRESHING que conserva contenido
y PROCESSING con AsyncButton se mantienen. Retry y prevención de doble acción
cubiertos; sin datos ficticios en aplicación. Fixtures únicamente en tests.

Pruebas reforzadas en 320×640 y 640×320, texto 200% e inset de teclado: primer
campo, acción Siguiente/foco, último campo, scroll, botón Guardar alcanzable y
error requerido visible; sin overflow. Lectura semántica de placa, acciones
etiquetadas, Android touch targets y contraste del CTA comprobados. Renders de
Home, Vehículos vacío y formulario revisados a 390×844 con Roboto del SDK solo
para tests, sin redistribuir fuentes. Estas capturas no equivalen a teclado nativo.

## Emulador y limitación ambiental

Se intentó smoke controlado en el AVD existente emulator-5554, sin cuentas ni
servicios productivos. Su APK compiló (225,6 s) y se instaló (15,3 s), pero el
smoke no comenzó sus aserciones: Android mostró bootanim `running`, servicio
input_method no disponible, screencap `Transport endpoint is not connected` y
adb pull `Input/output error`. Se detuvo únicamente el proceso de esa prueba.
show_ime_with_hard_keyboard volvió al valor original 0, confirmado por lectura.
No se reinstaló SDK ni se creó/reinició otro AVD.

La instrumentación experimental se retiró; el smoke CP19 existente permanece
idéntico. **Inspección nativa de teclado/Login/Home/Vehículos pendiente por esta
incidencia ambiental**. No se declara un smoke exitoso ni validación iOS en Windows.

## Validación final

- flutter pub get: correcto; sin nuevas dependencias.
- dart format --set-exit-if-changed .: 45 archivos, 0 cambios.
- flutter analyze: No issues found.
- flutter test: 58 aprobados, 0 fallidos, 0 errors/skipped; baseline 57.
- Nuevo test de accesibilidad; dos tests responsive/teclado reforzados y render
  existente ampliado. Ningún test previo eliminado. El fallo inicial de limpieza
  del handle semántico de la prueba nueva se corrigió antes de la suite final.
- Regresión auth: login, Bearer, secure storage, restore, refresh, logout/guards.
- Regresión vehículos: GET /api/v1/vehicles/user/{userId}, POST /api/v1/vehicles.
- APK debug final: compilación correcta, assembleDebug 389,4 s.
- Backend intacto: no fue necesario ejecutar nuevamente Maven.
- git diff --check: correcto. Estado final: 3 archivos modificados y 2 nuevos,
  exclusivamente Flutter; código/docs sin staging. Build y capturas temporales se limpian.

Deuda legítima: cuota Figma, assets Inter/branding autorizados, inspección nativa
cuando el AVD esté estable e iOS desde macOS. CP20 permanece fuera de alcance;
conviene completar la revisión nativa pendiente antes del cierre visual definitivo.
