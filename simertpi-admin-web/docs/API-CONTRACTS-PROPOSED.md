# Estado CP25.3-C

Implementados: GET /admin/auth/csrf, POST /admin/auth/login, GET /admin/auth/me y
POST /admin/auth/logout, con cookie de sesión y CSRF. El contrato definitivo está en
../../docs/CP25.3-C_ADMIN_AUTH.md. MFA, recuperación, usuarios, roles y permisos de
la propuesta histórica siguiente continúan pendientes y no se invocan desde Angular.

# Contratos administrativos previstos — NO IMPLEMENTADOS

Estos contratos son propuestas para la siguiente CP. No existen clientes operativos
ni llamadas a estas rutas en CP25.3-B. Prefijo /api/v1. OpenAPI formal, campos definitivos
y esquemas de error deben revisarse junto al backend antes de conectar el frontend.

| Método y ruta                       | Acceso / propósito                                                   |
| ----------------------------------- | -------------------------------------------------------------------- |
| GET /admin/auth/csrf                | Token CSRF inicial, sin conceder sesión                              |
| POST /admin/auth/login              | Credenciales + CSRF; cookie de sesión o desafío MFA, sin tokens JSON |
| POST /admin/auth/mfa/verify         | Completar desafío pendiente, con límites y CSRF                      |
| GET /admin/auth/me                  | Identidad, roles, permisos y estado de MFA de una sesión válida      |
| POST /admin/auth/logout             | Revocación idempotente y eliminación de cookie; CSRF                 |
| POST /admin/auth/access-recovery    | Respuesta genérica; límites y canal verificado                       |
| POST /admin/auth/access-reset       | Credencial efímera de un uso; revocación de sesiones                 |
| GET /admin/users                    | municipal-users:read, paginación y filtros autorizados               |
| GET /admin/users/{id}               | municipal-users:read, alcance y DTO seguro                           |
| POST /admin/inspectors              | inspectors:create + CSRF; rol fijado por servidor                    |
| PATCH /admin/users/{id}/status      | municipal-users:status:update + CSRF                                 |
| POST /admin/users/{id}/access-reset | municipal-users:access:reset + CSRF                                  |
| GET /admin/roles                    | roles:read; solo catálogo permitido                                  |
| GET /admin/permissions              | Catálogo de permisos visibles al operador                            |
| PUT /admin/users/{id}/roles         | roles:assign + CSRF + reautenticación/MFA según política             |

## Borradores de datos

Login: username y password; nunca logs, query strings ni almacenamiento de navegador.
/me: userId, username, roles, permissions, enabled, sessionExpiresAt y mfaVerified.
Alta de inspector: username, firstName, lastName, email y phone opcional. La política
de activación debe aprobarse antes de fijar si hay campos adicionales. Rechazar campos
roles, permissions o enabled enviados para eludir la política del servidor.

Listado: q, enabled, role, page y size con máximo documentado; orden estable y campos
de respuesta explícitos. No exponer hash, credenciales, cookies ni tokens de activación.
Estado: enabled y version para concurrencia optimista; no permitir deshabilitar al último
administrador. Evitar éxito aparente ante versión obsoleta.

Alta: 201 con DTO seguro; 409 para identidad duplicada o conflicto. Definir
Idempotency-Key con huella del payload autorizado, TTL y alcance por operador. Una
repetición nunca restablece contraseña ni cambia roles. 400/422 validación, 401 sin
sesión, 403 sin permiso/CSRF, 429 límites, 5xx genérico con correlationId.
Los contratos de sesión deben emitir Cache-Control: no-store. El cliente no muestra
cuerpos arbitrarios del servidor. Validar autorizaciones en backend y no solo en rutas.

## Contratos existentes preservados

POST /users crea únicamente CITIZEN. /municipal/auth/login, refresh, logout y me
continúan para INSPECTOR y su audiencia móvil. GET /audit ofrece filtros y límites;
requiere revisión de permisos para consumo web. Ninguno de estos contratos se invoca
para simular autenticación administrativa. No se modifica el backend en esta CP.
