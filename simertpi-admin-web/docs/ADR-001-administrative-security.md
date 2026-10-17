# ADR-001 — Seguridad del Administrador Web Municipal

Estado: sesión administrativa y CSRF implementados en CP25.3-C; permisos granulares, MFA y recuperación pendientes.
Fecha: 2026-10-16. Base: develop 07b6ead1c7c0f72aef182b018b57b23a9a5adb3d.

## Contexto

SIMERTPI conserva Java 21, Spring Boot 3.5.6, PostgreSQL, Flyway y el monolito modular.
Las aplicaciones ciudadana, municipal y Angular son productos independientes.
V3 ya contiene CITIZEN, INSPECTOR, SUPERVISOR, SIMERTPI_ADMIN, COLLECTIONS,
FINANCE, IT_ADMIN y AUDITOR. No crear un rol genérico ADMIN.
El registro público actual crea solo CITIZEN. El login municipal exige INSPECTOR.
V41 limita las audiencias móviles a CITIZEN y MUNICIPAL. No añadir sesiones web allí
sin una decisión y migración explícitas. HTTP Basic y CSRF globalmente desactivado
no son una solución de sesión administrativa web.

## Decisión

Autenticación administrativa independiente en /api/v1/admin/auth. Sesión opaca de
servidor persistida, con rotación al login, expiración absoluta e inactiva y revocación
por logout, deshabilitación, cambio de contraseña o de permisos. Revalidar estado y
privilegios en el servidor; una copia de autoridades en sesión no puede sobrevivir
indefinidamente a su retirada. Redis no es requisito inicial ni se presume instalado.

Preferir frontend y API bajo el mismo origen mediante routing de infraestructura,
sin convertir Angular en parte del backend ni añadir un microservicio. La topología
está pendiente de aprobación. En producción, cookie de sesión Secure, HttpOnly,
Path=/ y SameSite=Strict bajo el mismo origen (evaluar Strict según flujos aprobados).
Puede usarse nombre __Host-SIMERTPI_ADMIN_SESSION con esas condiciones y sin Domain.
Si se eligen sitios distintos, SameSite=None exige Secure, CORS exacto con credenciales,
protección CSRF y aceptación explícita del riesgo de bloqueo de cookies de terceros.
No usar comodines CORS con credenciales. No guardar tokens en almacenamiento web.

CSRF debe proteger también login, logout y operaciones mutables basadas en cookies.
El backend emitirá el token __Host-SIMERTPI_ADMIN_XSRF legible por JavaScript, distinto de la cookie
HttpOnly de sesión; Angular enviará X-XSRF-TOKEN a su origen. La configuración XSRF
incluida no resuelve solicitudes cross-origin: no habilitar la API DEV absoluta hasta
aprobar same-origin/proxy o un mecanismo explícito y probado. Comprobar Origin y
cabeceras de Fetch Metadata como defensa adicional, no sustituto del token.

Cadena de seguridad específica: rechazar Basic, tokens ciudadanos y municipales en
rutas administrativas; denegar por defecto rutas/métodos sin política. Errores JSON
genéricos 401/403 y Cache-Control: no-store para autenticación y datos sensibles.
CSP, HSTS y frame-ancestors se configurarán con el hosting futuro; no se modifican ahora.
No registrar cuerpos de login, cookies, contraseñas, tokens ni hashes.

## Roles y permisos

SIMERTPI_ADMIN es administrador funcional; INSPECTOR no hereda administración y
IT_ADMIN no recibe facultades funcionales por su nombre. Proponer permisos
municipal-users:read, inspectors:create, municipal-users:status:update,
municipal-users:access:reset, roles:read, roles:assign y audit:read.
Falta el modelo granular persistido. Backend valida permisos y alcance en cada acción;
los guards Angular solo ayudan a navegación. Controlar autoelevación, IDOR y la baja
concurrente del último administrador. Asignación de privilegios administrativos
separada del alta ordinaria de inspectores, con reautenticación/MFA según sensibilidad.

## MFA y recuperación

Diseñar estados de inscripción, verificación y recuperación. Exigir MFA antes de uso
productivo administrativo. No equiparar biometría ciudadana con MFA web. El canal de
recuperación debe verificarse y sus credenciales ser de un uso, con TTL y almacenamiento
irreversible. Respuesta no enumerativa, límites de intentos y auditoría. No implementar
recuperación hasta confirmar canal institucional y política de custodia.

## Auditoría

Reutilizar el módulo audit existente, agregando eventos de login, rechazo, logout,
bootstrap, alta, habilitación/deshabilitación, permisos y recuperación. Identificar
operador, recurso, UTC, resultado y correlación; almacenar metadatos permitidos,
nunca secretos. Cambio sensible y auditoría de éxito deben ser atómicos. Los fallos
deben registrarse sin revertirse con la operación. Definir acceso, retención y protección
contra alteración; la existencia del log no garantiza inmutabilidad por sí sola.

## Bootstrap del primer SIMERTPI_ADMIN

No es un endpoint público. Crear herramienta explícita de una ejecución, sin HTTP,
seeds, scheduling ni Flyway. Identidad nominal aprobada, operador identificado y
permiso puntual. Validar host por igualdad exacta, base e identidad de conexión
contrastados con valores autorizados independientemente; TLS verificable.
Entrada interactiva protegida con doble confirmación, sin contraseña en argumentos,
archivos, variables de entorno ni logs. Aplicar límites reales del codificador BCrypt.
Reutilizar repositorios y PasswordEncoder; fijar únicamente SIMERTPI_ADMIN. No
promover silenciosamente cuentas existentes ni añadir INSPECTOR/CITIZEN.
Bloqueo transaccional y comprobación atómica para impedir dos bootstraps concurrentes;
repetición no modifica contraseña ni roles. Auditar identidad del operador externo
mediante contexto controlado, no como SYSTEM anónimo. Probar en PostgreSQL aislado
antes de solicitar autorización separada para DEV.

El trabajo InspectorProvisioningService/InspectorProvisioningCli es referencia local,
no está integrado en esta entrega. Su política fija INSPECTOR y no puede ampliarse
solo cambiando una constante. Revisar host por contains, auditoría ausente, verificación
posterior al commit, alcance del component scan y comando incompatible con Docker.
No se da aquí un comando de bootstrap pretendidamente ejecutable.

## Altas posteriores

El administrador autorizado creará inspectores desde un contrato específico que fija
INSPECTOR en servidor. Ni autorregistro ni selección libre de roles privilegiados.
Alta y auditoría atómicas, validaciones y reintentos idempotentes. La cuenta habilitada
usará el login municipal existente en la APK, sin compartir la sesión del navegador.
Preferir activación mediante canal verificado; definir entrega de credencial antes del
piloto y no usar correos reservados ficticios para recuperar acceso.

## Estado de CP25.3-B

No se implementan endpoints ni se modifica Spring Security. No hay usuario autenticado,
transportes de login, cookies ni escrituras remotas. AdminSession siempre rechaza acceso;
/admin y descendientes redirigen a /login. Cliente HTTP bloquea toda solicitud.
La previsualización requiere configuración preview, modo Angular de desarrollo y host
loopback exacto. Es otra ruta, no sesión ni bypass del guard administrativo. DEV y
producción no habilitan preview. Las cuentas y formularios no se persisten.

Referencias: https://angular.dev/reference/versions y
https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html.

La sección CP25.3-B es histórica. El estado vigente, las cookies definitivas, las pruebas y los límites se describen en ../../docs/CP25.3-C_ADMIN_AUTH.md.
