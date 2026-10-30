# V51: roles, invitaciones y correo administrativo

## Estado y alcance

V51 es un único checkpoint funcional. El esquema incorpora V51 (roles y permisos)
y V52 (configuración dinámica de SMTP), por lo que la validación debe actualizar
una base PostgreSQL 16 descartable desde V50 hasta V52. Las migraciones son
aditivas; V52 guarda credenciales cifradas con una clave externa. No se ejecutaron
en DEV ni en producción.

Este runbook prepara la operación y no autoriza cambios en Render, PostgreSQL
compartido, cuentas reales ni buzones. El arranque normal aplica Flyway; no crea
cuentas. El primer Superadministrador se aprovisiona por el CLI local protegido,
no mediante un endpoint HTTP.

## Configuración externa del servicio DEV

La configuración SMTP activa (proveedor, host, puerto, TLS, usuario y credencial
cifrada) se gestiona en la base por el portal de Superadministrador, después de
la inicialización. El secreto de cifrado nunca se persiste junto con el ciphertext.

| Variable | Requisito | Uso |
|---|---|---|
| `SIMERTPI_SMTP_ENCRYPTION_KEY` | Obligatoria para usar SMTP dinámico | Clave Base64 de 32 bytes. Usar exactamente el mismo valor en el CLI inicial y en el backend DEV; conservarlo en un gestor externo de secretos con respaldo protegido. Nunca versionar ni imprimir. |
| `SIMERTPI_SMTP_ALLOWED_HOSTS` | Restringida | Lista de hosts SMTP permitidos; para el perfil inicial de DEV, limitar a `smtp.gmail.com`. Se valida host y resolución para reducir SSRF. |
| `SIMERTPI_SMTP_TEST_RECIPIENT` | Valor aprobado | Destinatario único para correos de prueba: `antoxavi1992@outlook.com`. |
| `SIMERTPI_SMTP_TEST_EMAIL_ENABLED` | `false` por defecto | Mantener deshabilitado hasta autorización expresa de prueba de correo real. |
| `SIMERTPI_PUBLIC_BASE_URL` | Obligatoria para invitaciones | Origen HTTPS exacto del portal del mismo ambiente, sin query ni fragmento. En DEV nunca usar la URL de producción. |

Las variables `SIMERTPI_SMTP_HOST`, `SIMERTPI_SMTP_PORT`,
`SIMERTPI_SMTP_USERNAME`, `SIMERTPI_SMTP_PASSWORD`, `SIMERTPI_SMTP_AUTH`,
`SIMERTPI_SMTP_STARTTLS`, `SIMERTPI_SMTP_STARTTLS_REQUIRED`,
`SIMERTPI_INVITATION_EMAIL_PROVIDER` y `SIMERTPI_INVITATION_EMAIL_FROM` son
configuración heredada/obsoleta para el flujo dinámico y no se deben solicitar
ni configurar. La configuración activa se persiste cifrada en PostgreSQL. No
almacenar la clave maestra en PostgreSQL, argumentos, repositorio, archivos
`.env` o logs.

La misma clave maestra debe seguir disponible externamente durante todo el
tiempo que existan credenciales SMTP cifradas con ella. Si se pierde o cambia,
el ciphertext existente no podrá descifrarse y el correo dinámico dejará de
funcionar. V51 no implementa rotación ni recifrado de credenciales; no cambiar
la clave ni reemplazarla hasta contar con un procedimiento de migración de clave
probado y autorizado.

## Inicialización de Gmail y primer Superadministrador

La configuración inicial prevista (no ejecutada) es:

- Proveedor: Gmail SMTP.
- Servidor: `smtp.gmail.com`, puerto 587, STARTTLS obligatorio.
- Usuario y remitente: `antoxavie1992@gmail.com`.
- Nombre remitente: `SIMERTPI — GAD Piñas`.
- Destinatario de prueba: el destinatario fijo descrito arriba; los envíos de
  prueba siguen deshabilitados inicialmente.
- Cuenta Superadministradora candidata: `superadmin.simertpi.dev`,
  `antoxavi1992@outlook.com`, rol único `IT_ADMIN`. El nombre y apellido deben
  confirmarse en la aprobación nominal; no se infieren.

Antes de cualquier operación futura, ejecutar el preflight documentado en
[`DEV-SUPERADMIN-BOOTSTRAP.md`](../../docs/DEV-SUPERADMIN-BOOTSTRAP.md) y verificar
independientemente que el destino sea PostgreSQL DEV, TLS sea
`sslmode=verify-full`, y el hostname corresponda al recurso actual. El modo
`-Superadministrator` del wrapper es explícito; no usar el modo heredado
`-MunicipalAdministrator` para crear la primera autoridad del sistema.

El operador debe suministrar `SIMERTPI_SMTP_ENCRYPTION_KEY` desde el gestor de
secretos externo y escribir la contraseña de aplicación Gmail solo en el prompt
oculto del CLI. Nunca se solicita una contraseña inicial para la cuenta
Superadministradora. El CLI prueba la conexión TLS, cifra y persiste la
configuración activa y crea, en la misma transacción, una cuenta nueva
deshabilitada, sin contraseña, con token aleatorio de un uso y vigencia de 24
horas (solo se almacena su SHA-256). La invitación se envía después del commit;
el token no se imprime ni se devuelve al portal.

Si la prueba SMTP falla, se revierten tanto la configuración inicial como el
alta de la cuenta. Si el proveedor no acepta el correo después del commit, la
cuenta queda pendiente, sin exponer el token; comprobar auditoría y usar el modo
protegido de reenvío tras el límite existente. `ACCEPTED_BY_PROVIDER` no prueba
que el mensaje llegó al buzón. No repetir una operación ambigua sin consultar el
estado por mecanismos autorizados de solo lectura.

## Invitaciones y activación

- El enlace usa HTTPS y lleva el secreto únicamente en el fragmento URL.
- La activación vence a las 24 horas, es atómica y de un solo uso.
- Reenviar invalida el token anterior; se aplica un mínimo de 60 segundos y un
  máximo de cinco reenvíos por usuario en 24 horas.
- La cuenta se habilita solo cuando el titular establece su propia contraseña.
- Errores de proveedor se reducen a estados/códigos seguros. Nunca habilitar
  debug SMTP ni registrar credenciales, cuerpo del mensaje o URL con token.
- Los correos de prueba permanecen deshabilitados hasta autorización explícita.

## Validación y despliegue futuro

1. En PostgreSQL 16 descartable, aplicar V50 → V51 → V52 y probar permisos,
   correo cifrado, activación, caducidad, concurrencia e idempotencia.
2. Revisar migraciones y artefactos antes de la integración.
3. Tras autorización futura, desplegar backend por el procedimiento normal y
   confirmar Flyway V52 y readiness antes del portal compatible.
4. Inicialmente probar SMTP con transporte simulado. Para un correo real,
   habilitar temporalmente el envío de prueba solo después de autorización
   específica; usar únicamente el destinatario fijo autorizado.
5. Verificar resultado de proveedor y auditoría sin inspeccionar ni imprimir
   tokens o secretos. No ejecutar este procedimiento como parte del checkpoint.

## Reversión y limitaciones

Flyway no revierte V51/V52 automáticamente. Detener altas/invitaciones ante una
incidencia, deshabilitar SMTP conservando tablas, ciphertext, roles, auditoría y
asignaciones; revisar compatibilidad del artefacto anterior con el esquema
aditivo. Preferir corrección hacia delante. No ejecutar SQL de limpieza como
rollback.

Este checkpoint no modificó Render, PostgreSQL DEV ni producción; no creó
usuarios ni envió correo real. La clave maestra y la contraseña de aplicación
Gmail requieren configuración externa futura. Una validación con proveedor
simulado no equivale a confirmar entrega real.
