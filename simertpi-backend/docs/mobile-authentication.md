# Autenticación móvil — CP18.1

HTTP Basic continúa disponible para compatibilidad. El cliente ciudadano usa
sesiones Bearer opacas: valores aleatorios de 256 bits, persistidos solamente
como SHA-256 en PostgreSQL. Se reutilizan BCrypt, usuarios y autorización actuales.
No se necesita clave de firma ni dependencia JWT.

## Contrato

- `POST /api/v1/auth/login`: `{username,password}`; 200 con
  `{userId,tokenType,accessToken,refreshToken,accessExpiresAt,refreshExpiresAt}`.
  Solo usuarios habilitados con rol CITIZEN; rechazo genérico 401.
- `POST /api/v1/auth/refresh`: `{refreshToken}`; mismo DTO, tokens rotados.
- `POST /api/v1/auth/logout`: `{refreshToken}`; 204 y revocación de esa sesión.
- Registro ciudadano existente `POST /api/v1/users` se conserva.

Respuestas de sesión no-cache/no-store. Header `X-Correlation-ID` preservado.
Access y refresh no son intercambiables. No se incluyen roles ni PII en tokens;
la autorización lee los roles actuales. Logout revoca también el access vigente.

## Rotación y configuración

Refresh se serializa con bloqueo de fila de sesión. Su expiración absoluta no
se extiende. Reutilizar un refresh consumido revoca toda esa sesión; un resultado
incierto no debe reintentarse ciegamente. Otras sesiones del usuario no cambian.

`SIMERTPI_AUTH_ACCESS_TTL` (15m) y `SIMERTPI_AUTH_REFRESH_TTL` (30d) configuran
la política de seguridad, no normativa municipal. Deben ser positivos y refresh
mayor que access. HTTPS requerido en entornos reales; no se guardan secretos.

V30 agrega tablas de sesiones e historial refresh con hashes únicos y FK.
No se modifican migraciones históricas. Tests usan PostgreSQL 16 Testcontainers.

## Pendiente antes de producción

Política e implementación de rate limiting/brute force; retención y limpieza de
sesiones expiradas/historial; evaluación de TTL operativos; validación iOS nativa.
No existe recuperación de contraseña. No se registran DTOs de credenciales,
tokens ni Authorization. Ver documentación Flutter CP18.1 para lifecycle móvil.
