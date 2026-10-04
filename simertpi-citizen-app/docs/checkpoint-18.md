# Checkpoint 18 — Autenticación ciudadana y Figma

## Baseline y alcance

Git develop, HEAD 04b98ce (`feat: bootstrap SIMERTPI citizen Flutter app`),
working tree limpio antes de cambios. Flutter 3.47.6 stable / Dart 3.13.5,
SDK existente C:\src\flutter. Baseline Flutter: 17 tests CP17.
No cambios al backend, Flyway, providers ni funcionalidades CP19–CP24.

## Contratos reales inspeccionados

SecurityConfig, SimertpiUserDetailsService, UserController, CreateUserRequest,
UserResponse, UserService, GlobalExceptionHandler y HttpBasicSecurityTest.
Búsqueda acotada adicional de JWT/Bearer/login/logout/refresh/recovery/reset:
no emisión de tokens ni contrato de sesión móvil encontrados.
NotificationSettingsController y exclusivamente su lectura de preferencias
se revisaron como dependencia estricta para verificar credenciales/rol sin
mutaciones, IDs de usuarios ni consultas administrativas.

| Operación | Método / endpoint | Request / respuesta | Acceso | Estados |
|---|---|---|---|---|
| Verificar acceso ciudadano HTTP Basic | GET /api/v1/notifications/preferences | Sin body; Authorization Basic; lista de {channel, enabled}, descartada tras validar estructura | CITIZEN | 200, 401, 403, 5xx |
| Crear ciudadano | POST /api/v1/users | CreateUserRequest → UserResponse | Público, rol CITIZEN asignado server-side | 201, 400 validación, 409 duplicado, 5xx |
| Consultar usuario por username | GET /api/v1/users/username/{username} | UserResponse | SIMERTPI_ADMIN | No utilizado por móvil |
| Login/token/refresh/logout backend/recuperación | No existen endpoints | No hay DTO ni token/TTL contractual | No implementados | BACKEND GAP |

CreateUserRequest: username requerido máximo100; email requerido @Email máximo255;
password @NotBlank (sin mínimo empresarial inventado); firstName/lastName
requeridos máximo100; phone opcional máximo30. El móvil envía esos seis campos,
no rol ni userId. La respuesta de registro es un usuario creado, nunca una
sesión autenticada. Los errores 400/409 se traducen sin mostrar payloads internos.
La verificación no es un endpoint de login: reutiliza una lectura CITIZEN ya
existente y no implementa preferencias/notificaciones funcionales.

## Arquitectura, sesión y seguridad

CP17 se extiende: UI → AuthController (ChangeNotifier) → AuthGateway/AuthService
→ mismo ApiClient. AppScope comparte configuración y controlador. No se añade
otro gestor de estado ni cliente HTTP ni dependencia de almacenamiento.

**BACKEND GAP: sesión persistente entre reinicios no disponible sin persistir
credenciales Basic.** No se guarda contraseña, ni Authorization codificado,
ni cookies; no se asume JSESSIONID reutilizable. Basic codifica, no cifra, y su
header en memoria equivale a credenciales: únicamente lo recibe el cliente HTTP.
El bootstrap restaura solo el estado técnicamente posible: inicio sin credencial
va a Login; una instancia autenticada en memoria conserva su estado. Reiniciar
el proceso exige ingreso nuevo. No se inventan expiración local ni refresh.
Para persistencia segura real se necesita un contrato backend de sesión móvil
revocable; recién entonces corresponderá secure storage de un token emitido.

Logout es local, limpia el header y resetea navegación; no revoca remotamente una
credencial Basic (no contrato para ello). Un 401 de la sesión vigente invalida
estado y vuelve a Login; una respuesta de una sesión anterior no invalida una
nueva. Generation guard evita autenticación tardía después de logout/dispose.
Login y AsyncButton previenen envíos concurrentes. No hay retries automáticos.
Los guards centrales protegen Home/showcases y rutas desconocidas; login/logout
resetean stack, sin Back hacia pantallas incompatibles con la sesión.

API_BASE_URL conserva la semántica CP17 (base que termina en /api/v1), configurada
por --dart-define. ENVIRONMENT=dev/qa/uat/prod: HTTPS obligatorio fuera de DEV,
TLS normal, sin bypass. X-Correlation-ID conserva formato [A-Za-z0-9._:-]{1,128};
respuesta validada. No se expone al ciudadano. Redirects deshabilitados para
no reenviar Authorization. Logs solo event/result/correlationId, sin cuerpos,
URLs, headers, contraseñas, tokens o destinos. No se persiste información personal.

## Figma y observaciones UX

Skill utilizado: figma-design-to-code. Archivo oficial SIMERTPI DIGITAL,
https://www.figma.com/design/7uCDe3oYaZ4m1gBejNUKl8, única página utilizada:
**01 — App Ciudadano (0:1)**. Contexto de alta fidelidad y screenshots reales
consultados para **01 — Login (5:591)** y **02 — Registro (5:607)**.
No se implementaron las otras pantallas. Ambos frames usan wordmark textual
SIMERTPI; no incluyen assets raster/SVG ni logo descargable.

Tokens verificados y centralizados: azul#05306B, cian#05A1D1, blanco,
texto#141F2E, secundario#616E7D, input#F5F7FA, margen24,
radio input10 y botón12; título25, branding23. Header adapta la altura total82
con safe area real. Responsive fluido/scroll, sin posiciones absolutas rígidas.

- GAP FIGMA/BACKEND: Login muestra correo en Figma, pero backend autentica
  username. Campo cambiado a Nombre de usuario, sin asumir login por email.
- Registro separa nombres/apellidos y añade username porque son obligatorios
  reales; teléfono opcional. No se divide arbitrariamente un nombre completo.
- Recuperar contraseña se omite del flujo: no existe contrato. No se simula envío.
- OBSERVACIÓN UX/FIGMA: blanco sobre cian no cumple contraste4.5:1. CTA conserva
  cian y usa texto oscuro accesible; targets≥48px, feedback textual y semántico.
- **PENDIENTE VALIDACIÓN VISUAL FIGMA: tipografía Inter.** El contexto identifica
  Inter pero no proporciona archivo/licencia de fuente; se conserva fuente
  de plataforma, sin descargar branding arbitrario ni usar fuentes por red.
- Numeración01/02 de los frames se omite como referencia de diseño, no contenido
  funcional. Sin rediseño de las demás pantallas ni implementación Home CP19.

Password oculto/toggle accesible, labels visibles y semantics de campo,
validaciones cercanas, conservación de inputs ante error recuperable. Login
usa loading contextual, no skeleton. Sin delays. Text scaling200%, móviles
320×640/640×320 y teclado probados. Registro confirma solo respuesta real201.
Timeout incierto de registro indica verificar acceso antes de repetir creación.

## Pruebas y validación

Tests de controlador: inicial/signedOut, validación, procesamiento/deduplicación,
Basic en memoria, logout, invalidación, sesión anterior, dispose y errores.
HTTP local con puertos efímeros: rutas/DTO reales, Authorization, correlation,
201 sin auto-login, 400/401/403/409, respuesta malformada y fail-closed sin URL.
Widgets: campos/ocultación, validación, loading/disabled, navegación/guards,
registro, layouts con teclado/text scaling. Tests CP17 se conservan/adaptan a
fixtures autenticadas para sus showcases; no se elimina cobertura.

Validación final ejecutada después de los últimos cambios de código:

- flutter pub get: exit0; lock conservado, ninguna dependencia agregada.
- dart format --set-exit-if-changed .: 31 archivos, 0 cambios, exit0.
- flutter analyze: No issues found, exit0.
- flutter test: **37 tests, 0 failures/errors, 0 skipped**, exit0
  (17 CP17 conservados/adaptados +20 auth nuevos).
- flutter build apk --debug: assembleDebug exitoso49.0s, exit0;
  APK168828561bytes, instalado con éxito en emulator-5554.
- git diff --check: exit0. Solo app ciudadana modificada; sin add/commit/push.

En pruebas dirigidas anteriores se detectaron/corrigieron cuatro avisos de estilo,
etiqueta duplicada y un tap antes de estabilizar teclado. La validación final
no tuvo esos fallos. Gradle mostró aviso de acceso nativo del JBR instalado,
sin fallo de build; no se actualizaron SDK/toolchains por CP18.

Smoke emulador existente Android17/API37.0, resolución1080×2400:
instalación/arranque/foreground confirmados, login visual, foco/teclado virtual,
validaciones vacías y navegación a registro comprobados con screenshots.
El teclado estaba oculto por configuración de hardware: show_ime_with_hard_keyboard
se cambió temporalmente0→1 para verificarlo y se restauró a0. El arranque en frío
mostró primero el splash nativo Flutter heredado de CP17; después renderizó Login.
No hubo errores flutter:E del proceso observado. Native splash/launcher assets
siguen pendientes de branding oficial. No se creó ni detuvo otro emulador.
Loading y navegación autenticada/logout se validaron con tests controlados,
no con una sesión real en el emulador: APK DEV sin API_BASE_URL intencional.
No se ejecutó smoke contra backend real por falta de dataset/credencial autorizada.
Capturas/XML de diagnóstico y artefactos de build se retiraron tras verificarlos.

Estado: **PASS CON OBSERVACIONES**, para las capacidades reales disponibles.
La persistencia entre reinicios NO está implementada: gap explícito del contrato
backend Basic y prohibición de persistir contraseña. No equivale a sesión móvil
productiva completa ni a validación de iOS.

## Deuda y siguiente checkpoint

Sesión móvil persistente/refresh/recuperación requieren contratos backend;
no se modificó backend para resolverlos. Inter/branding final requieren asset
oficial. iOS requiere macOS/Xcode y no se valida desde Windows. Smoke contra
backend real depende de dataset autorizado; tests no usan credenciales reales
ni crean ciudadanos en producción. CP19 (Inicio + vehículos) queda pendiente,
no iniciado.
