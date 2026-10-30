# V53 — Operación de Microsoft Outlook OAuth2/XOAUTH2

## Alcance y estado

Este documento describe la preparación operativa de Outlook.com como remitente SMTP delegado. La autorización OAuth real no está configurada ni probada hasta registrar la aplicación Microsoft, otorgar consentimiento y completar la conexión desde el backend DEV. No pegar secretos o tokens en tickets, chat, archivos del repositorio, argumentos de comandos ni logs.

La identidad prevista del remitente es `simertpi@outlook.com`. El primer Superadministrador autorizado se aprovisiona por el procedimiento protegido documentado en [DEV-SUPERADMIN-BOOTSTRAP.md](DEV-SUPERADMIN-BOOTSTRAP.md), después de verificar el correo y la ausencia de conflictos. El bootstrap no se ejecuta al desplegar la aplicación.

## Registro Microsoft requerido

El titular/operador de la cuenta institucional debe registrar una aplicación en Microsoft Entra antes de autorizar Outlook.com:

1. Elegir el tipo de cuentas compatible con **cuentas organizacionales y cuentas Microsoft personales**. Un registro exclusivo de tenant organizacional no satisface la cuenta Outlook.com personal.
2. Registrar como Redirect URI la URI HTTPS exacta del backend DEV terminada en `/api/v1/admin/system/email/oauth/callback`. La base URL pública backend debe confirmarse en Render; no usar la URL del portal ni producción. El código lee el valor de `SIMERTPI_MICROSOFT_REDIRECT_URI` y exige que sea HTTPS.
3. Crear una credencial confidencial solo si la arquitectura desplegada la requiere; guardarla únicamente en el gestor de secretos de Render. Anotar su fecha de expiración para rotarla antes del vencimiento.
4. Solicitar consentimiento delegado para `openid profile email`, `offline_access` y `https://outlook.office.com/SMTP.Send`. Los scopes OpenID se usan para validar que la cuenta devuelta sea exactamente el remitente autorizado. No solicitar permisos de lectura de correo ni permisos de aplicación.
5. El titular de `simertpi@outlook.com` debe iniciar y completar el consentimiento. No autorizar una cuenta distinta como remitente.

Microsoft documenta SMTP de Outlook.com en `smtp-mail.outlook.com:587`, STARTTLS y Modern Auth; OAuth SMTP usa el scope delegado `https://outlook.office.com/SMTP.Send` y `offline_access` para solicitar renovación. Fuentes oficiales: [configuración POP/IMAP/SMTP de Outlook.com](https://support.microsoft.com/es-es/outlook/pop-imap-y-smtp-settings-for-outlook-com) y [OAuth para IMAP, POP y SMTP](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth).

## Configuración DEV

La configuración de proveedor debe permanecer inactiva hasta que se completen todas las comprobaciones. Los nombres siguientes son variables conceptuales y se deben ajustar a los nombres que exponga el backend V53; no añadir valores al repositorio:

| Dato | Tratamiento |
|---|---|
| `SIMERTPI_MICROSOFT_CLIENT_ID` | ID no secreto del registro de aplicación que admite cuentas personales. |
| `SIMERTPI_MICROSOFT_CLIENT_SECRET` | Secreto de entorno Render. El código lo lee desde el entorno; **no lo guarda en PostgreSQL** ni lo devuelve en API/UI. |
| `SIMERTPI_MICROSOFT_REDIRECT_URI` | URI HTTPS exacta del callback backend DEV: `<backend-origin>/api/v1/admin/system/email/oauth/callback`. La URI exacta debe coincidir en Microsoft Entra y Render. |
| `SIMERTPI_MICROSOFT_AUTHORIZED_SENDER` | Cuenta remitente que el callback puede vincular. Para esta configuración: `simertpi@outlook.com`. |
| `SIMERTPI_SMTP_ENCRYPTION_KEY` | Conservar exactamente la clave estable ya usada por V52. Antes de cambiarla, verificar directamente en Render si existe y qué ciphertext cifra; no reemplazarla durante este procedimiento. Una clave ausente o distinta impide descifrar configuraciones existentes. |
| `SIMERTPI_ADMIN_WEB_ORIGIN` | Origen HTTPS del portal DEV. Se usa para redirigir el callback al portal, sin parámetros OAuth sensibles. |
| SMTP | Host fijo `smtp-mail.outlook.com`, puerto `587`, STARTTLS requerido y validación del certificado habilitada. No permitir host arbitrario para OAuth. |

La configuración OAuth está incompleta si falta cualquiera de los cuatro valores `SIMERTPI_MICROSOFT_*` anteriores; no se debe iniciar ni activar en ese estado. La autorización administrativa usa `GET /api/v1/admin/system/email/oauth/status`, `POST .../start`, `GET .../callback`, `POST .../test-connection`, `POST .../test-email`, `POST .../activate` y `POST .../disconnect`. El callback devuelve al portal un resultado sanitizado (`connected`/`failed`); el navegador no recibe access ni refresh tokens. La autorización ordinaria se vincula a state de un solo uso, PKCE S256, expiración y sesión administrativa. El primer flujo se inicia solo desde el CLI, antes del primer `IT_ADMIN`, también con state de un solo uso y aprobación del operador.

Flyway V53 crea las tablas de conexión OAuth, intentos PKCE y pruebas SMTP; tokens y verifier se cifran con `SIMERTPI_SMTP_ENCRYPTION_KEY`. La migración es aditiva y conserva las tablas V52. Confirmar la migración sobre PostgreSQL 16 descartable antes de aplicar en DEV.

## Conexión, prueba y operación

1. Confirmar backend y portal DEV en el mismo SHA, readiness saludable, V53 aplicada y callback HTTPS exacto.
2. Para una conexión ordinaria, ingresar al portal como IT_ADMIN y comprobar que la cuenta Microsoft solicitada es `simertpi@outlook.com`.
3. Para el primer Superadministrador, no hay endpoint HTTP de bootstrap: el operador ejecuta el CLI local protegido contra el destino DEV verificado con el modo `--authorize-dev-microsoft-smtp` y flags obligatorios `--expected-host`, `--expected-db-user`, `--operator` y `--approval-id`. Introducir exactamente `AUTHORIZE DEV SIMERTPI OUTLOOK SMTP` cuando el CLI lo solicite. El CLI muestra la URL Microsoft para abrirla de forma interactiva; el intento caduca a los diez minutos y se consume una vez.
4. En Microsoft, revisar el registro, remitente y scopes; el titular autoriza la cuenta. El callback procesa el código y vuelve al origen `SIMERTPI_ADMIN_WEB_ORIGIN` hacia `/admin/system/email?oauth=connected|failed`.
5. Ejecutar el CLI con `--activate-dev-microsoft-smtp` y los mismos flags de destino/aprobación. Confirmar exactamente `ACTIVATE DEV OUTLOOK SMTP`; el CLI realiza la prueba de conexión y activa la conexión. Esta operación no crea ninguna cuenta.
6. Solo después, ejecutar `--bootstrap-dev-superadmin` con flags de destino aprobados. El CLI exige `SIMERTPI_BOOTSTRAP_SUPERADMIN_EMAIL`, que debe ser `simertpi@outlook.com`, nombre de usuario `superadmin.simertpi.dev` y la confirmación exacta `CONFIRM DEV SUPERADMIN`. Crea la cuenta pendiente e intenta una invitación; no define la contraseña. El modo de bootstrap Gmail no requiere Microsoft OAuth: verifica smtp.gmail.com con STARTTLS, cifra y activa la credencial de aplicación introducida en consola oculta antes de crear la invitación.
7. No ejecutar los modos anteriores sin autorización operativa independiente. Este checkpoint no ejecutó autorización Microsoft, activación, bootstrap ni invitación real.
8. Supervisar expiración/renovación. El backend renueva de forma controlada y cifra los tokens; ante `REAUTH_REQUIRED`, no enviar y solicitar nueva autorización. Comprobar estado/auditoría sin recuperar tokens.

No usar la contraseña normal de Outlook ni SMTP AUTH con LOGIN/PLAIN como fallback. Si OAuth o STARTTLS falla, mantener el proveedor inactivo y registrar un código de error sanitizado.

## Respaldo, publicación y recuperación

Antes de desplegar cualquier migración V53 en DEV, crear un respaldo actual de PostgreSQL DEV mediante el mecanismo autorizado de Render y probar la restauración en una instancia PostgreSQL 16 descartable. No restaurar sobre DEV durante la validación. Registrar fecha, formato, tamaño, SHA-256, versión, historial Flyway y resultado de restauración en un registro protegido, sin credenciales ni datos personales.

La publicación se hace por el flujo normal de la rama `develop` y AutoDeploy, una vez que las pruebas de backend, Angular, migración y OAuth simulado pasan. Confirmar SHA, health/readiness, versión Flyway y logs sanitizados. Evitar un segundo deploy manual si AutoDeploy ya inició.

Flyway no revierte por sí solo una migración aplicada. Ante un problema, detener operaciones de correo y mantener tokens/ciphertext y auditoría; preferir corrección hacia delante. No ejecutar `flyway clean`, SQL destructivo ni restaurar el respaldo en DEV sin autorización. El rollback del código no deshace el esquema.

## Rotación y revocación

- Para reemplazar el client secret, cargar el nuevo valor por el gestor de secretos, probar el intercambio y retirar la credencial anterior solo tras confirmar éxito.
- Para pérdida o compromiso de refresh token, desactivar envíos, revocar la autorización desde Microsoft y volver a conectar desde la cuenta institucional. Marcar la conexión `REAUTH_REQUIRED`/desconectada; no borrar historial.
- No cambiar `SIMERTPI_SMTP_ENCRYPTION_KEY` como parte de la rotación de la aplicación. La rotación de clave de cifrado requiere descifrar y volver a cifrar todos los secretos en un procedimiento aparte, con respaldo y pruebas.
- Mantener callback, state, PKCE verifier, tokens y errores OAuth fuera de logs, navegador persistente y telemetría.

## Pendientes externos y criterios de validación real

La conexión real permanece pendiente hasta tener: registro Microsoft compatible con cuentas personales, Redirect URI exacta en Entra y Render, consentimiento de la cuenta remitente, Client ID y Client Secret ingresados como variables secretas de Render, `SIMERTPI_SMTP_ENCRYPTION_KEY` confirmada, respaldo DEV restaurado en PostgreSQL 16 descartable y autorización expresa de operación. En este checkpoint no se realizó OAuth real ni se envió invitación. Sin estos requisitos, las pruebas simuladas no deben describirse como envío Outlook real.
