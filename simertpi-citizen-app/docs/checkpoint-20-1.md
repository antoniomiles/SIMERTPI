# Checkpoint 20.1 — Cierre cartográfico y QR

Baseline develop / 08c7d17. Cambios CP20 sin stage preservados. Backend intacto.

## Fuente y configuración

flutter_map 8.3.2 y latlong2 0.9.1 conservados; sin nuevas dependencias.
Opción explícita MAP_SOURCE=osm-dev exclusivamente ENVIRONMENT=dev. Archivo
config/map-dev.json sin credenciales. No es default: sin configuración permanece
el catálogo/listado real. Fuente oficial raster:
https://tile.openstreetmap.org/{z}/{x}/{y}.png

Uso DEV interactivo limitado bajo https://operations.osmfoundation.org/policies/tiles/.
Datos OSM bajo ODbL, atribución © OpenStreetMap contributors, ODbL explícita y referencia
https://www.openstreetmap.org/copyright. Servicio best-effort, sin SLA, sujeto a
bloqueo/cambios. No scraping, descargas offline, prefetch masivo ni polling.
User-Agent estable identifica ec.gob.simertpi.simertpi_citizen_app. Cache nativo
incorporado flutter_map: respeta headers HTTP (fallback 7 días), no se deshabilita ni envía no-cache.
Tiles únicamente para viewport interactivo; no headers/token backend enviados.

Atribución visible dentro del viewport, fondo sólido/semántica y texto escalable.
MAP_SOURCE=custom conserva MAP_TILE_URL HTTPS y MAP_ATTRIBUTION obligatoria.
URLs con userInfo/query/fragment rechazadas. OSM público bloqueado en QA/UAT/PROD;
DEV exige URL canónica y crédito requerido, sin variantes de subdominio.
Config inválida produce fallback ciudadano/listado, no error técnico.

## Ambientes y privacidad

DEV: flutter run --dart-define-from-file=config/map-dev.json
Puede añadir API_BASE_URL real local mediante --dart-define según CP17.
QA/UAT: proveedor autorizado para pruebas, límites/licencia/atribución definidos,
configuración HTTPS externa; no heredan osm-dev. PROD: contratación/selección
oficial con cobertura, SLA, cuotas/costo, privacidad y atribución aprobados, o
hosting propio autorizado. No se registra cuenta ni se inventan endpoints/keys.
Ausencia de proveedor en cualquiera mantiene catálogo funcional.

Una app distribuida no puede guardar secretos de servidor. Si futuro proveedor
requiere clave cliente, deberá ser publicable/restringida según contrato oficial
(app/cuotas), inyectada por build externo y fuera de Git. Credenciales privadas
no caben en dart-define, deben permanecer en infraestructura servidor autorizada.
El contrato actual no admite query tokens: un adaptador futuro requiere revisión
explícita, no bypass de TLS. Tiles revelan IP/área consultada al proveedor; no
GPS/tracking del ciudadano ni envío de su ubicación. Cache plataforma puede ser
purgada por OS; no garantía offline ni disponibilidad productiva.

## Coordenadas y selección

Markers exclusivamente de latitude/longitude backend finitos en dominio Mercator.
Viewport centro/bounds derivados de esos markers; sin centro Piñas hardcodeado.
Espacios sin coordenadas siguen en listado y detalle, no crean marker. active !=
available. Icono/texto/semántica indican habilitación, nunca libre/ocupado.
Selección consulta metadata fresca; no reserva, parking, duración, tarifa o pago.
CP21 debe revalidar invariantes/reglas y concurrencia con contratos reales.

## QR, Figma y validación

QR opaco -> GET /api/v1/parking-spaces/qr/{qrCode} -> metadata backend.
Sin nuevo formato, JSON confiable, apertura URL ni cambios backend. Doble lectura,
input inválido, errores y retry se conservan. PENDIENTE VALIDACIÓN FÍSICA:
no QR físico disponible para cámara del AVD; callbacks controlados no sustituyen
esa validación. Smoke incluye entrada al scanner y navegación, no lectura física.
Figma no reintentado: límite Starter confirmado CP20; PENDIENTE VALIDACIÓN VISUAL
FIGMA permanece. No nueva identidad visual. iOS pendiente de macOS.

Smoke opcional real DEV:
flutter test integration_test/mobile_smoke_test.dart -d emulator-5554 --dart-define-from-file=config/map-dev.json --dart-define=MAP_SMOKE_REAL_TILES=true
Usa HTTP fixture exclusivo de test, coordenadas exclusivamente fixture y vista
interactiva OSM; verifica imagen tile decodificada, atribución y selección marker.
No se usa proveedor externo en suite normal/CI. Pruebas de widgets usan tiles de
memoria; no espacios/QR/coordenadas demo en app.

Validación Android cartográfica: PASS, 1 smoke (01:13). Tiles reales decodificados
(RawImage con imagen no nula dentro de FlutterMap), atribución visible, tap marker,
detalle/selección local, auth/restore/vehicles/formulario/logout. APK de integración
compiló en 184,5 s, instalación 10,8 s. No se usa la base local/productiva.
AVD existente emulator-5554 estable en esta ejecución. Intento de permiso temporal
pm grant devolvió package not found porque runner ya había retirado la aplicación;
no se cambió otro paquete ni se reinstaló SDK. QR físico continúa pendiente.

Resultado final: PASS CON OBSERVACIONES (QR físico, Figma, iOS y proveedor PROD).
flutter pub get: correcto. dart format --set-exit-if-changed .: 55 archivos,
0 cambios, exit 0. flutter analyze: No issues found (61,1 s). flutter test:
78 passed, 0 failures/errors/skipped; CP20 tenía 75, +3 pruebas necesarias.
flutter build apk --debug --dart-define-from-file=config/map-dev.json: correcto,
assembleDebug 274,9 s. No nuevas dependencias. Smoke Android adicional: 1 PASS.
No cambios backend, auth o payloads vehicles. No suite Maven repetida.
git diff --check correcto; cambios CP20 y CP20.1 sin stage. build/.dart_tool
retirados al cerrar, sin APK versionado ni capturas temporales en repositorio.
No git add/commit/push. CP21 no iniciado.

Fuentes oficiales: https://www.openstreetmap.org/copyright,
https://operations.osmfoundation.org/policies/tiles/,
https://docs.fleaflet.dev/layers/tile-layer/caching.
