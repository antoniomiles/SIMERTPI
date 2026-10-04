# Checkpoint 19 — Inicio y vehículos

Baseline heredado: develop / 4c2f894. Se inició únicamente después del gate
CP18.1: Maven 403/403, Flutter 46/46, analyze correcto y APK debug compilado.
Sin commit intermedio ni cambios backend durante CP19.

## Figma y UX

Archivo SIMERTPI DIGITAL, página 01 — App Ciudadano: frame 03 — Inicio (5:627),
contexto completo y screenshot inspeccionados. Colores/token #EBF5FF agregado;
se mantienen azul #05306B, cyan #05A1D1, márgenes 24, cards/botones existentes.
No hay assets estáticos descargables en ese frame: branding y símbolo QR son texto.
El marcador textual ▣ se representa con geometría Flutter equivalente, sin asset
nuevo ni QR escaneable, para evitar glifos ausentes. La variante filled gris de
SecondaryButton reutiliza el componente existente sin alterar su default.
Render Home revisado frente al screenshot Figma a 390×844 con Roboto del SDK;
no se redistribuyen esos fonts ni se afirma uso de Inter. Estado vacío sustituye
el vehículo ficticio del frame. CTA centrados conservan convención CP17/18 y
contraste accesible; ajuste registrado, no rediseño de identidad.
Vehículo (5:673) no pudo consultarse: límite MCP Starter alcanzado, no fallo de API.
PENDIENTE VALIDACIÓN VISUAL FIGMA para listado/formulario y componentes no accesibles.
Inter sin archivo tipográfico disponible conserva fallback existente; CTA con
texto oscuro sobre cyan mantiene contraste; annotation 03 no se presenta como dato.
OBSERVACIÓN UX/FIGMA: logout y manejo de vehículos reales/estados reemplazan
contenido de ejemplo; texto escalado/layout accesible prevalece sobre posiciones fijas.
Figma Home no define bottom navigation; no se inventan tabs.

Home protegido reemplaza showcase como raíz ciudadana. Showcase permanece en
ruta separada para pruebas foundation, sin acceso en la experiencia principal.
QR/ingreso manual/historial conservan CTA visual con aviso explícito de función
no disponible; no simulan operaciones. No hay vehículos/parking/saldos ficticios.
Home carga solo sección vehículos: navegación y contenido estático permanecen útiles.

## Contratos reales

| Operación | Método/ruta | Request/response | Seguridad/resultados |
|---|---|---|---|
| Listar propios | GET /api/v1/vehicles/user/{userId} | lista VehicleResponse | Bearer; 200,401,403 |
| Crear | POST /api/v1/vehicles | userId,plate,brand?,model?,color? → VehicleResponse | Bearer; 201,400,401,403,409 |

VehicleResponse: id,userId,plate,brand,model,color,active,createdAt,updatedAt.
El móvil modela únicamente campos usados; no copia entidades JPA.
userId proviene exclusivamente del DTO de sesión autenticada, no de formulario.
Backend confirma ownership del usuario autenticado: no basta el UUID enviado.
GET /vehicles/plate/{plate} es staff; no se usa para ciudadano.
GAP BACKEND: no editar/eliminar/desactivar/vehículo principal; no se inventan endpoints.
Placa requerida y máximo 10; marca/modelo máximo 100 y color máximo 50 opcionales.
Backend no normaliza placa: móvil preserva valor exacto, sin regex normativa ni
conversión arbitraria a mayúsculas. Duplicado: 409 con mensaje ciudadano.

## Implementación

Feature-first: VehicleService (ApiClient único), VehiclesController ChangeNotifier,
VehicleList, VehiclesPage y VehicleFormPage. Estados INITIAL/LOADING/SUCCESS/EMPTY/
ERROR; flags REFRESHING/PROCESSING. Skeleton inicial, vacío honesto, error/retry;
refresh conserva datos existentes. Pull-to-refresh solo en listado. Crear espera
confirmación backend, AsyncButton evita doble submit; no optimistic UI monetaria
ni destructiva. Campos conservados ante errores; resultado incierto informa límite.
No edición/eliminación sin contrato. Logout y guards CP18.1 se conservan.
Semantics de placa, targets táctiles, text scaling 200%, teclado y scroll adaptable.
No tokens, placas, emails, passwords ni payloads sensibles en logging técnico.

## Validaciones

Gate global: `mvn test` BUILD SUCCESS, 403 tests, 0 failures/errors/skipped;
PostgreSQL 16.15 Testcontainers, JDBC dinámico, Flyway valida 30 migraciones/V30.
Flutter pub get correcto, `dart format --set-exit-if-changed .` 44 archivos sin
cambios, `flutter analyze` sin observaciones, `flutter test` 57 tests aprobados
(37 baseline → 46 gate CP18.1 → 57 global; ninguno eliminado). Un smoke Android
adicional aprobado (`flutter test integration_test/mobile_smoke_test.dart -d
emulator-5554`): login, storage/restauración nativa, Home, Vehículos vacío,
formulario/foco, Back nativo y logout remoto/local sobre HTTP fixture.
No prueba creación contra datos reales. APK debug global compilado correctamente (assembleDebug 198,1 s).
Los errores intermedios de compilación/tests/lint se corrigieron; las primeras
expectativas Flyway V29 se ajustaron a V30 sin eliminar cobertura. La prueba
HTTP se separó de widgets porque Flutter Test sustituye HttpClient; smoke usó
aserciones síncronas en callbacks y Back nativo. Formato sobre build temporal
falló inicialmente; se resolvió con flutter clean antes del gate, sin omitirlo.
Pruebas CP19 cubren contratos exactos/headers,
controller, estados, doble creación, widgets y responsive; CP17/18 se conservan.
Smoke Android único usa HTTP local controlado y secure storage nativo: no es una
integración municipal productiva ni utiliza credenciales reales. integration_test
proviene del SDK y se añade únicamente para esa verificación nativa.

## Deuda y siguiente fase

Validación visual Figma pendiente para Vehículos/formulario y font Inter;
backend carece de edición/eliminación/normalización uniforme de placa; política
de rate limiting/retención de sesiones pendiente CP18.1. iOS requiere macOS.
CP20 (mapa/zonas/espacios/QR) no implementado en esta ejecución.

## Archivos y revisión

Nuevos CP19: HomePage, VehicleService/CitizenVehicle/VehicleInput,
VehiclesController, VehicleList, VehiclesPage, VehicleFormPage, AppQrMarker,
vehicle_http_test.dart, vehicles_test.dart, mobile_smoke_test.dart y este documento.
Ajustados: router, tokens, botones secundarios, tests foundation/auth widgets,
pubspec/lock (integration_test SDK), README y gitignore (caché Kotlin).
Backend permanece exactamente con los cambios CP18.1: V30/auth/config y tests
compatibles. No contratos de vehículos modificados, no CP20, no add/commit/push.

Validación final Android: APK normal instalado con adb install -r y arrancado.
Smoke nativo automatizado 1/1 aprobado con HTTP local controlado; no dataset real.
La comprobación visual manual de teclado quedó inconclusa: UiAutomator devolvió
null root node; no se declara verificada visualmente. Teclado/layout/foco se
cubren por widgets (200% texto, portrait/landscape) y foco del smoke nativo.
Ajuste show_ime_with_hard_keyboard restaurado; no SDK/emulador reinstalado.
Formato/analyze/tests/build finales pasan. iOS no ejecutado desde Windows.
Capturas temporales y build APK se limpian al cerrar; código/docs quedan sin staging.
