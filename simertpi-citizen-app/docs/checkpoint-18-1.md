# CP18.1 — Autenticación móvil persistente

Baseline develop 4c2f894, árbol limpio. Fase A obligatoria antes de CP19.

## Decisión y contratos

Se reutilizan Spring Security, SimertpiUserDetailsService, BCrypt y roles reales.
Se eligen tokens opacos aleatorios de256bits, con SHA-256 únicamente en PostgreSQL:
no JWT, claims, PII dentro del token, claves de firma ni nueva dependencia backend.
El monolito ya utiliza PostgreSQL; este diseño permite revocación inmediata.
HTTP Basic permanece compatible para clientes existentes. El móvil deja de usarlo.

- POST /api/v1/auth/login: {username,password};200 SessionResponse;400 validación,
  401 credencial/usuario deshabilitado/no elegible. Solo CITIZEN puede abrir sesión
  móvil. No se divulga existencia de usuario.
- POST /api/v1/auth/refresh: {refreshToken};200 nuevo SessionResponse;400 formato,
  401 inválido/vencido/reutilizado/revocado.
- POST /api/v1/auth/logout: {refreshToken};204 sin body; prueba de posesión de
  refresh, no userId arbitrario. Repetición de token conocido es idempotente.
- SessionResponse: userId, tokenType=Bearer, accessToken, refreshToken,
  accessExpiresAt, refreshExpiresAt. Fechas UTC explícitas. Cache-Control:no-store.
- Registro POST /api/v1/users preservado sin cambios funcionales.

V30 aditiva crea identity.mobile_sessions y mobile_refresh_tokens con FK e índices
únicos de hashes. Ninguna migración histórica cambia. Access TTL configurable
SIMERTPI_AUTH_ACCESS_TTL (default de seguridad15m), refresh TTL absoluto
SIMERTPI_AUTH_REFRESH_TTL (30d); valores positivos y refresh mayor a access.
Los defaults son política técnica, no normativa municipal; revisar para PROD.

## Rotación y seguridad

Refresh bloquea la fila de sesión (FOR UPDATE), consume el token y rota access y
refresh. El vencimiento absoluto no se alarga. Historial detecta reutilización:
revoca toda esa sesión y mantiene la revocación incluso cuando devuelve401.
Refresh repetido concurrente no genera dos rotaciones; reutilización revoca el
resultado previo. Por ello cliente debe serializar refresh. Logout revoca access
inmediatamente junto con toda la familia refresh, sin afectar otras sesiones.
Access y refresh no son intercambiables. Roles/enabled se consultan en cada
request, sin autoridad inventada en claims. Contexto Bearer es request-scoped,
incluido despacho de errores; no se convierte en cookie/sesión HTTP persistente.

Token DTOs tienen toString redactado. No se registra password/token/Authorization.
Correlation ID existente se conserva en éxito y errores. Endpoints auth públicos
solo aceptan métodos necesarios; todas las demás reglas de autorización siguen.

## Flutter

Arquitectura CP17/18 preservada. AuthService consume contrato real; ApiClient único
agrega Bearer, conserva timeouts/correlation y bloquea redirects. SecureSessionStore
usa flutter_secure_storage11.2.0, con una escritura del DTO de tokens, namespace por
ambiente/backend, Keychain this-device en iOS y cifrado nativo Android; backup
Android deshabilitado. Password nunca se persiste y el campo se limpia tras login.

Documentación del paquete consultada:
https://pub.dev/packages/flutter_secure_storage (Keychain y cifrado de plataforma).
No se usa SharedPreferences directamente ni fallback plano. Tests de canal de
plataforma comprueban escritura/lectura/borrado sin credenciales reales.

Splash lee secure storage sin delay: access utilizable → Home; access vencido →
refresh; refresh ausente/vencido/inválido → Login y limpieza. Refresh único en vuelo;
401 antiguo no rota otra sesión. Un401 autenticado permite renovar y repetir una
vez; no se repite ante timeout ni resultados inciertos de creación/refresh.
Resultado refresh incierto cierra localmente; no reenvía el token consumido.
Logout borra localmente y solicita revocación backend, reseteando navegación.
Si no puede confirmar revocación remota, informa ese límite; expiración absoluta
sigue siendo aplicable. Guards y Back se conservan, sin datos de CP19 aún.

## Validaciones

Gate Fase A completado antes de iniciar CP19: `mvn test` BUILD SUCCESS, 403 tests,
0 failures/errors/skipped, PostgreSQL 16.15 Testcontainers, Flyway validate V30.
Flutter pub get y formato correctos; analyze sin observaciones; 46 tests pasan;
APK debug compilado. git diff --check correcto. Android SDK agregó automáticamente
Platform 35 y CMake 3.22.1 requeridos por dependencias durante Gradle; no se cambió
Flutter ni Dart. Smoke nativo completado en emulator-5554: login con contrato HTTP controlado,
secure storage real, controlador nuevo/restauración, navegación y logout.
La verificación backend real se ejecuta en PostgreSQL Testcontainers; el smoke
móvil no utiliza cuentas productivas ni afirma una prueba E2E contra ese backend.
Gate global posterior: analyze sin observaciones y 57 tests Flutter aprobados;
Maven nuevamente BUILD SUCCESS con 403 tests, 0 failures/errors/skipped.
APK debug global compilado correctamente (assembleDebug 198,1 s).
Estado técnico PASS CON OBSERVACIONES: rate limiting e iOS pendientes.
No git add/commit/push. CP19 inicia únicamente después de este gate.

## Deuda técnica

Brute force/rate limiting no estaba implementado y queda explícitamente pendiente
antes de exposición productiva: requiere política y gateway/solución acordada,
no Redis ni infraestructura inventada. También limpieza programada/retención de
sesiones expiradas e historial refresh, revisión de TTL operativo y observabilidad
auth dedicada. iOS/Keychain y release requieren validación macOS/dispositivo;
no se declaran validados desde Windows. Recuperación de password sigue sin contrato.

Validación final Android: APK normal instalado con adb install -r y arrancado.
Smoke nativo automatizado 1/1 aprobado con HTTP local controlado; no dataset real.
La comprobación visual manual de teclado quedó inconclusa: UiAutomator devolvió
null root node; no se declara verificada visualmente. Teclado/layout/foco se
cubren por widgets (200% texto, portrait/landscape) y foco del smoke nativo.
Ajuste show_ime_with_hard_keyboard restaurado; no SDK/emulador reinstalado.
Formato/analyze/tests/build finales pasan. iOS no ejecutado desde Windows.
Capturas temporales y build APK se limpian al cerrar; código/docs quedan sin staging.
