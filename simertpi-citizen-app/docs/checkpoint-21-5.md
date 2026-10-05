# CP21.5 — DEV remoto y APK independiente de PC

Baseline: develop / e60c73d, working tree inicialmente limpio. No CP22, no commit,
no push, no secretos ni cambios en Render. Backend productivo sin modificaciones;
solo se agregan dos pruebas HTTP en MobileAuthPostgresIntegrationTest.

## Diagnóstico demostrado antes de cambios

RegisterPage -> AuthService.register -> ApiClient POST users, authenticated:false.
La base original se usaba literalmente. Con API_BASE_URL=https://simertpi-backend-dev.onrender.com
la ruta resultante era /users, no /api/v1/users. SecurityConfig permite POST
/api/v1/users; cualquier otra ruta termina en denyAll y HTTP 401 anónimo.
ApiClient clasifica 401 como unauthorized y authErrorMessage usaba el mensaje
«No pudimos verificar tu usuario y contraseña» también al registrar.
No era evidencia de una contraseña incorrecta ni de fallo BCrypt.

Contraste DEV realizado únicamente con POST JSON {} (sin usuario, contraseña ni
credencial, sin crear usuarios/sesiones): /users=401, /auth/login=401,
/api/v1/users=400, /api/v1/auth/login=400. Los 400 corresponden a validación del
contrato correcto; no se ingresó a los servicios de creación. No se consultaron
secretos, usuarios existentes ni BD remota. No se inspeccionó el binario instalado:
la conclusión aplica a la configuración de compilación proporcionada y fue
reproducida contractual y remotamente, sin afirmar una captura del teléfono.

## Contratos y flujo real

Registro POST /api/v1/users, Content-Type y Accept application/json,
X-Correlation-ID, sin Authorization. JSON: username, email, password, firstName,
lastName obligatorios; phone opcional/null. Username/nombres hasta 100 caracteres,
email válido hasta 255, phone hasta 30; password @NotBlank. No identification/role/
enabled aceptados. HTTP 201 UserResponse incluye id, username, email, nombres,
phone, enabled y timestamps; nunca password/hash/tokens. Flutter exige 201,
id String, username coincidente y enabled bool.

Registro no hace login automático: muestra cuenta creada, limpia el campo password
y ofrece ir a iniciar sesión. Login manual usa username exacto (no email) y password.
POST /api/v1/auth/login con {username,password}, sin Authorization. HTTP 200:
userId, tokenType=Bearer, accessToken, refreshToken, accessExpiresAt, refreshExpiresAt.
Tokens opacos de 43 caracteres, hashes server-side, access TTL 15m y refresh TTL 30d
por defaults configurables actuales. Refresh rota ambos tokens; reutilización revoca
la sesión. Logout remoto revoca sesión. Flutter conserva single refresh, retry único,
secure storage por ambiente/API y guards; nunca persiste password.

UserService valida duplicados, asigna únicamente CITIZEN, enabled=true y BCrypt.
MobileAuthService usa DaoAuthenticationProvider con UserDetailsService real; exige
cuenta habilitada y CITIZEN. V2 crea identidad, V3 siembra roles, V30 sesiones/tokens.
Flyway desde BD vacía prepara el flujo sin usuario/admin sembrado manualmente.
POST /users incorrecto no alcanza UserController ni persiste usuario. No se afirma
si hubo otros intentos previos exitosos con otra APK/configuración.

## Corrección mínima

AppConfig normaliza solo la URL sin ruta o con / hacia /api/v1; base API explícita
y prefijos reverse-proxy conservados. No URL Render en código ni segundo cliente.
TLS y validación de configuración intactos. Registro 401 ahora utiliza mensaje
propio de registro, sin atribuirlo a contraseña incorrecta; login conserva su mensaje.
Variables existentes reales: ENVIRONMENT y MAP_SOURCE. APP_ENV y MAP_PROVIDER
indicados en el comando anterior son ignorados por Dart; no causaban el 401, pero
MAP_PROVIDER no habilitaba OSM. No se agregan alias ni se cambia su contrato.

## Cobertura y resultados

Antes de cambios: backend 21 PASS (UserService, MobileAuthPostgres, SecurityHardening,
HttpBasic); Flutter auth/session/secure storage 29 PASS. Primer comando Flutter
incluyó por error session_test.dart inexistente: 22 tests cargados PASS y error
solo de carga; se corrigió a session_persistence_test.dart/secure_session_store_test.dart.
No existía prueba HTTP de registro seguido de login móvil: los tests de login
insertaban usuarios por JDBC; unit UserService omitía proxy/auditoría real.

Pruebas nuevas backend: ruta origin incorrecta devuelve 401 sin persistir; registro
público en PostgreSQL Testcontainers -> BCrypt/CITIZEN/enabled -> login misma
credencial -> access/refresh -> Bearer autorizado -> rotación -> ADMIN denegado.
Flutter: origen/root/base explícita/reverse proxy/TLS; reproduce antiguo /users;
HTTP local controlado registro -> login manual -> adaptador secure storage -> restore
-> Bearer, sin password persistido. Widget existente ahora continúa hasta Home tras
login posterior al registro. Fixtures exclusivamente en tests, nunca en app/Render.

Validación final: backend específico BUILD SUCCESS, 23 tests (12 MobileAuth,
7 HttpBasic, 3 SecurityHardening, 1 UserService), 0 failures/errors/skipped;
PostgreSQL 16.15 Testcontainers y Flyway V1–V30 desde esquema vacío.
Flutter pub get OK; dart format --set-exit-if-changed .: 62 archivos, 0 cambios;
flutter analyze: No issues found; flutter test completo: 103 PASS (99 baseline + 4),
0 failures/errors/skipped. Cuatro pruebas nuevas dirigidas también PASS.
APK DEV real con ENVIRONMENT=dev, API_BASE_URL explícita /api/v1 y MAP_SOURCE=osm-dev:
assembleDebug OK (204,6 s); build/app/outputs/flutter-apk/app-debug.apk conservada
para instalación física, excluida de Git. Sin actualización de SDK/dependencias.
Primer formato global encontró ruta generada Gradle inaccesible: flutter clean
resolvió y validación final completa pasó. Primeros tests HTTP nuevos encontraron
el override 400 del binding de widgets: cliente HTTP real solo en arnés local,
sin cambio ni bypass TLS en app; luego pruebas completas verdes.
git diff --check OK; 5 archivos modificados y 2 nuevos, todos sin stage.
Veredicto PASS CON OBSERVACIONES: instalación/prueba física pendiente del propietario;
no registro válido ni login contra Render, ningún secreto ni cambio remoto.

## APK y teléfono

Desde simertpi-citizen-app:

```sh
flutter pub get
flutter build apk --debug --dart-define=ENVIRONMENT=dev --dart-define=API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1 --dart-define=MAP_SOURCE=osm-dev
```

1. Instalar/actualizar build/app/outputs/flutter-apk/app-debug.apk en el teléfono.
2. Usar Internet móvil/Wi-Fi; apagar backend local y desconectar USB si se desea
   demostrar independencia de PC. No detener servicios ni bases remotos.
3. Registrar una cuenta DEV autorizada con username/email nuevos. No publicar
   contraseña ni tokens. Debe aparecer cuenta creada, sin sesión automática.
4. Ir a iniciar sesión; usar el mismo username (no email) y password. Debe llegar Home.
5. Cerrar/reabrir la app: restaurar sesión; logout debe regresar al login.
6. Si duplicado, no repetir registros indiscriminadamente: intentar login con el
   usuario que realmente se creó; si persiste error, registrar momento/pantalla,
   sin enviar secretos. Render cold start y conectividad pueden producir timeout
   distinto al bug corregido; no declarar creación fallida si resultado incierto.

No se efectuó registro válido contra DEV remoto ni prueba física desde Codex.
La prueba de usuario persistido usa PostgreSQL efímero y los tests Flutter usan
HTTP local controlado. Prueba final física requiere revisión del propietario.

## Deuda separada

Rate limiting/brute force pendiente según CP18.1; Render cold start/conectividad;
recuperación de contraseña sin contrato; Figma/QR físico/iOS previos. No relacionados
con el 401 por ruta. Ningún cambio de Flyway, autorización, Basic/Bearer, pagos ni CP22.
## Cierre DEV remoto y datos operativos (esta ejecución)

Confirmación del propietario: APK física instalada, registro, login y Home contra
Render por Internet PASS con PC/backend local apagados. Esta confirmación sustituye
el pendiente de autenticación física anterior; Codex no efectuó esa prueba física.
El fix API_BASE_URL y los dart-define ENVIRONMENT/API_BASE_URL/MAP_SOURCE se conservan.

Dataset reproducible: [guía DEV backend](../../simertpi-backend/docs/dev-pinas.md),
[QR imprimibles](../../simertpi-backend/docs/dev-pinas-qrs.html). Sembrado explícito
SIMERTPI_DEV_SEED_ENABLED=true exclusivamente dev; por defecto desactivado y fail-closed
si se combina con perfiles ajenos a dev/test. Sin nueva migración, V1–V30 intactas.
Tres calles verificadas en la fuente pública EMGIRZAPP; zonas, nueve espacios, QR,
tarifas y calendario son simulados DEV/NO OFICIALES. Coordenadas omitidas.

Validación local CP21.5 previa a CP22: 6 pruebas seed/backend PostgreSQL verdes,
Flutter analyze limpio y 103 tests PASS. La suite global backend obtuvo BUILD SUCCESS:
411 tests, 0 failures/errors/skipped, PostgreSQL 16.15 Testcontainers y Flyway validate V30.
El flujo HTTP llegó a PENDING_PAYMENT sin pagos. Después del redeploy quedan pendientes:
vehículo físico, catálogo Render, código/QR, cotización y PENDING_PAYMENT. Payment físico
se probará después de ese orden. Esta ejecución no modifica Render ni realiza deployment.
Resultado final global después de CP22: backend 414 tests PASS, Flyway validate V30;
Flutter 132 tests PASS, analyze limpio, format sin cambios y APK DEV compilada contra
Render /api/v1 con ENVIRONMENT=dev y MAP_SOURCE=osm-dev. CP21.5: PASS CON OBSERVACIONES,
implementación local validada; dataset/vehículo/QR/CP21 físico pendientes de redeploy.
No hubo cambios Render, commit, push ni prueba física de payment.