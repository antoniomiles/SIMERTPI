# Checkpoint 20 — Mapa, zonas, espacios y QR

Baseline `develop / 08c7d17`, working tree inicial limpio. Backend inspeccionado,
sin modificaciones ni nuevas migraciones. No git add/commit/push ni CP21.

## Matriz de contratos reales

| Capacidad | Contrato GET existente | Autorización | Uso CP20 |
|---|---|---|---|
| Zonas | /api/v1/zones; /zones/code/{code} | Catálogo público | Listado y filtro |
| Calles | /api/v1/streets; /streets/zone/{zoneId}; /streets/zone/{zoneId}/code/{code} | Público | Relación espacio→calle→zona |
| Espacios | /api/v1/parking-spaces; /parking-spaces/street/{streetId}; /parking-spaces/code/{code} | Público | Listado y resolución por código |
| QR | /api/v1/parking-spaces/qr/{qrCode} | Público | Resolución exacta del identificador |
| Reglas | /api/v1/parking/rules, selector spaceId o qrCode | CITIZEN y roles municipales definidos | Inspeccionado, no cálculo CP21 |
| Disponibilidad/ocupación | No hay contrato ciudadano agregado | — | No se inventa estado |

Se revisaron controllers/DTOs/services/entities/repositories de Zone, Street y
ParkingSpace, V5/V16, búsquedas sobre migraciones posteriores, SecurityConfig,
pruebas de catálogo público y reglas QR, documentación CP10 y rutas de sesiones.
No se consultan sesiones ajenas para inferir ocupación.

ZoneResponse: id/code/name/description/active/createdAt/updatedAt.
StreetResponse: id/zoneId/code/name/description/active/createdAt/updatedAt.
ParkingSpaceResponse: id/streetId/code/qrCode/spaceNumber/latitude/longitude/
spaceType/active/createdAt/updatedAt. Móvil modela solo campos necesarios.
`active` es boolean de habilitación, no AVAILABLE/OCCUPIED/RESERVED. Tipos de
espacio son strings sin enum; no se presentan como estados inventados.

Zonas y calles no tienen coordenadas/geometría. Espacios tienen coordenadas
NUMERIC(10,7), opcionales, sin constraints de rango geográfico. Solo pares finitos
dentro del dominio del mapa Mercator se representan; ausentes/invalidas no generan
coordenadas ficticias. Centro/encuadre provienen únicamente de espacios reales.

## Implementación y límites

Feature-first discovery: DTOs/servicio, ChangeNotifier y pantallas. ApiClient único
para backend, Bearer/correlation ID/refresh existentes intactos, sin nuevo cliente
API. Tres catálogos se cargan en paralelo una vez por consulta explícita; sin polling.
Zonas filtran espacios mediante calles reales. Skeleton inicial, empty honesto,
error/retry y actualización conservan contenido con advertencia de datos antiguos.

Seleccionar consulta nuevamente GET por código y catálogos frescos: usa metadata
actual y verifica espacio/calle/zona habilitados. Si cambió entre ambas lecturas,
prevalece el catálogo más reciente. Detalle y confirmación son locales, sin reserva,
inicio de parking, duración, tarifa o pago. La información puede cambiar después;
**CP21 debe volver a validar reglas y concurrencia antes de iniciar**.

Mapa: flutter_map 8.3.2 (BSD-3-Clause), latlong2 0.9.1. Cliente Flutter sin vendor
fijo; proveedor cartográfico aún no definido. No Google/Mapbox/OSM URL inventada.
`--dart-define=MAP_TILE_URL=<template HTTPS autorizado con {z}/{x}/{y}>`
y `--dart-define=MAP_ATTRIBUTION=<atribución/licencia oficial>` configuran tiles.
No valor por defecto ni API key/secret. No userInfo/query/fragment en URL; TLS no
se omite. Attribution obligatoria. Sin configuración, mensaje honesto y listado
funcional; no mapa vacío presentado como cartografía real. Error de tiles ofrece
lista. Prueba del mapa usa TileProvider de memoria exclusivamente en tests.
Licencia/costos/cobertura/privacidad del proveedor deben aprobarse antes de uso real;
el proveedor de tiles recibe las solicitudes de áreas cartográficas mostradas.
No GPS, tracking, permiso ni envío de ubicación del ciudadano.

Marcadores 48×48 con icono/color/semántica de habilitación; detalle distingue
selección explícita. Reutiliza AppColors/AppTypography/theme/spacing/buttons/states.
Nuevo token mapViewport=320 representa tamaño de preview, no regla de negocio.
AppQrMarker existente es decoración de Home, no scanner ni QR real.

## QR y cámara

V5: qr_code string único máximo 255; CreateParkingSpaceRequest @NotBlank/@Size.
El payload utilizable es el valor opaco exacto, sin parsear URL/JSON ni transformar
mayúsculas o agregar prefijos. No contrato de firma/expiración/tamper-proof.
Validación local de transporte rechaza blancos/control, traversal, separadores,
URL/JSON y exceso de tamaño; es una restricción segura del cliente, no un nuevo
formato normativo. Identificadores legacy con esos caracteres requieren formalizar
un contrato compatible (por ejemplo resolver por query) antes de habilitarlos.
Backend resuelve la identidad; nunca se construye metadata confiable desde QR.
QR reemplazado por otro válido no puede detectarse criptográficamente: ciudadano
debe contrastar código/ubicación del detalle con el espacio físico.

mobile_scanner 7.4.2: QR exclusivamente, lectura local, MLKit bundled Android;
sin URLs externas, galería, imágenes devueltas o payloads logueados. Android CAMERA
contextual y hardware cámara opcional; iOS NSCameraUsageDescription preparado.
No GPS/micrófono/fotos. La cámara se detiene al retirar preview tras captura y al
salir; SDK maneja lifecycle. Doble lectura bloqueada hasta reintento explícito.
Permiso denegado/bloqueado o cámara ausente ofrece instrucciones y búsqueda por
código. SDK no distingue denegación permanente: ambas usan explicación segura de
ajustes, sin loop de solicitudes. No se afirma disponibilidad desde QR.

## Figma y UX

Reintento controlado contexto+screenshot node 5:646 de `01 — App Ciudadano`:
"You've reached the Figma MCP tool call limit on the Starter plan." Sin recorrer
archivo ni reintentos masivos. **PENDIENTE VALIDACIÓN VISUAL FIGMA** para nuevas
pantallas. DS CP17–CP19.1 conservado; sin identidad/branding/font nuevos.
Home conecta Buscar estacionamiento y Escanear QR; navegación protegida central
Discovery/QR/Space, Back al origen. Vehículos y autenticación no se rediseñan.

## Validación

Resultado: PASS CON OBSERVACIONES (Figma limitado y proveedor cartográfico
pendiente). Flutter 3.47.6 / Dart 3.13.5, sin actualizar SDK.

- flutter pub get: correcto.
- dart format --set-exit-if-changed .: 55 archivos, 0 cambios, exit 0.
- flutter analyze: No issues found.
- flutter test: 75 tests, 0 failures/errors/skipped; baseline 58, +17 pruebas
  en discovery_test.dart y discovery_http_test.dart. Regresión auth/vehicles intacta.
- flutter build apk --debug: correcto, assembleDebug 231,4 s; APK no versionado.
- Smoke Android nativo ampliado: 1 test adicional, PASS. Login, secure storage,
  restore, Home, zonas, detalle/selección local, entrada al scanner, Back, Vehicles,
  formulario/teclado y logout; HTTP fixture controlado dentro del test, sin backend
  productivo ni cuentas reales. Cámara y QR físico no validados extremo a extremo:
  lectura/errores/permisos y resolución QR se cubren mediante tests controlados.
- AVD existente emulator-5554: arranque inicialmente lento; am start -W reportó
  timeout, luego el runner ejecutó el smoke completo (01:12, All tests passed).
  No AVD nuevo/reinstalación. La aplicación de test ya no estaba instalada al
  retirar el permiso temporal (pm revoke: package not found); captura diagnóstica
  propia eliminada. No se conservan permisos/datos del smoke en otra aplicación.
- Responsive 320×640 y 640×320 a 200%: sin overflow, semántica y markers testeados.
- Mapa testeado con tiles de memoria, sin proveedor externo; no se declara
  validada cartografía de producción. iOS no compilado desde Windows.
- Backend sin cambios: suite Maven no repetida, Flyway sin modificaciones.
- git diff --check: correcto; cambios Flutter sin stage, sin temporales/secretos.

Se cerró un caso de datos antiguos: resolución exitosa reemplaza el catálogo local
con la respuesta fresca antes de quitar advertencias. Errores del lector permiten
scroll para texto escalado. No se modificaron contratos ni estados de autenticación.

Fuentes de dependencias: https://pub.dev/packages/flutter_map y
https://pub.dev/packages/mobile_scanner. Sin llamadas a proveedores productivos.
Deuda legítima: cartografía oficial/configuración, geometría de zonas, disponibilidad
agregada/paginación, contrato QR formal para legacy, Figma/assets e iOS en macOS.
No blocker QR para identificadores opacos compatibles con el contrato existente.
CP20.1 es opcional para esos gaps; no se implementa automáticamente. CP21 queda
pendiente de revisión humana y debe tratar la selección como snapshot sin reserva.

## Continuación CP20.1

Opción cartográfica DEV explícita, guard ambiental y atribución dentro del mapa
implementados sin descartar CP20. Véase checkpoint-20-1.md para términos,
configuración, validaciones y límites de producción. Backend/QR intactos.

CP20.1 validado: 78 tests verdes (+3), analyze sin incidencias, APK debug DEV
con cartografía explícita y smoke Android de tiles reales aprobado. Ver
checkpoint-20-1.md; sin cambio backend/disponibilidad/QR ni CP21.
