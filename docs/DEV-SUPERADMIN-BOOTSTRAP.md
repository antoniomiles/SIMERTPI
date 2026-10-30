# Aprovisionamiento inicial del Superadministrador SIMERTPI

Este es el único procedimiento previsto para el primer Superadministrador. El
modo heredado `--bootstrap-dev-admin` crea un `SIMERTPI_ADMIN` distinto y no
debe utilizarse como sustituto del bootstrap de `IT_ADMIN`.

**Procedimiento preparado; no ejecutado.** No asigna `IT_ADMIN` a una cuenta
existente y no se ejecuta al iniciar la aplicación.

## Aprobación y alcance

1. El propietario emite aprobación registrada para crear una cuenta nueva de
   Superadministrador y designa nominalmente a su titular y al operador que
   realizará el procedimiento.
2. El operador verifica de manera independiente que el destino es PostgreSQL
   DEV de SIMERTPI, la base `simertpi`, PostgreSQL 16 y el host oficial actual
   del servicio. El host, usuario DB y CA deben verificarse con la consola y
   documentación autorizadas de Render; no se deducen de este archivo.
3. El procedimiento solo admite el hostname DEV aprobado por el ejecutor,
   TLS `verify-full`, certificado CA válido, puerto 5432 y base `simertpi`.
   No usar `sslmode=require`, `disable`, alias de producción ni una conexión
   cuyo certificado no coincida.
4. No continuar si el recurso no coincide con DEV, si la identidad del host no
   puede verificarse, si ya existe un `IT_ADMIN` o si no hay aprobación nominal.

## Preflight local

Preparar clases y dependencias siguiendo el runbook de bootstrap existente. En
PowerShell, ejecutar primero el modo de validación sin conectar a PostgreSQL:

```powershell
.\scripts\Invoke-DevAdminBootstrap.ps1 `
  -ExpectedHost HOST_POSTGRES_DEV_VERIFICADO `
  -ExpectedDbUser USUARIO_DB_DEV_VERIFICADO `
  -CaCertificate RUTA_CA_PUBLICA_VERIFICADA `
  -Operator OPERADOR_AUTORIZADO `
  -ApprovalId REFERENCIA_APROBACION `
  -JavaHome RUTA_JAVA_21 `
  -Superadministrator `
  -PreflightOnly
```

El preflight valida el runtime, certificado local y artefactos sin abrir una
conexión a DEV. No sustituye la verificación independiente del recurso Render.

## Inicialización de SMTP y creación por invitación

Solo después de aprobar el preflight y revisar los datos de destino, repetir el
comando sin `-PreflightOnly`, desde una consola interactiva no redirigida. El
ejecutor conserva las credenciales PostgreSQL ya gestionadas fuera de los
argumentos. El bootstrap de Superadministrador no solicita ni establece una
contraseña inicial. Solicita la contraseña de aplicación de Gmail con entrada
oculta (`Console.readPassword`); no se acepta por argumentos, archivos, pipes,
historial de PowerShell, ticket o chat. El proceso necesita además
`SIMERTPI_SMTP_ENCRYPTION_KEY`, una clave Base64 de 256 bits suministrada por el
gestor externo de secretos únicamente al proceso local. El backend DEV debe
recibir exactamente el mismo valor; conservarlo también en el respaldo protegido
del gestor. Nunca se almacena esa clave en PostgreSQL ni en Git. Perderla o
cambiarla vuelve irrecuperables las credenciales SMTP ya cifradas. V51 no dispone
de rotación ni recifrado; no reemplazarla sin un procedimiento probado y
autorizado. El CLI limpia los buffers de contraseña al finalizar; no debe
habilitarse debug SMTP/JPA ni logging de valores sensibles.

El bootstrap primero valida la configuración Gmail de DEV (smtp.gmail.com,
587, STARTTLS), verifica la conectividad y cifra la credencial con AES-GCM bajo
la clave externa. En la misma operación protegida crea una cuenta nueva
deshabilitada, sin hash de contraseña y con el único rol `IT_ADMIN`; crea un
token de activación aleatorio de un solo uso, conserva solo su SHA-256 y
vigencia de 24 horas. Tras confirmar el commit, intenta enviar la invitación a
la dirección ingresada para la cuenta. El correo informa usuario/rol y enlaza a
la URL HTTPS del portal DEV; el token solo va en el fragmento del enlace y
nunca se imprime. `ACCEPTED_BY_PROVIDER` no equivale a entrega en el buzón.
Si SMTP no está activo o la prueba de conexión falla, la creación no debe
continuar. Si el proveedor falla después de guardar la cuenta, el resultado se
reporta como pendiente/fallido sin mostrar token; comprobar el estado mediante
auditoría y usar el procedimiento de reenvío autorizado antes de reintentar.

La cuenta DEV prevista para la operación posterior es `superadmin.simertpi.dev`
con correo `antoxavi1992@outlook.com` y rol `IT_ADMIN`. El remitente/usuario
SMTP previstos son `antoxavie1992@gmail.com`, con nombre `SIMERTPI - GAD Pinas`;
el destinatario de prueba permitido será `antoxavi1992@outlook.com`. El nombre y
apellido del titular deben confirmarse en la autorización nominal; no se
inventan aquí. El modo inicial solicita esos datos y la contraseña de aplicación
de Gmail con entrada oculta. No solicita una contraseña del Superadministrador.
En los modos de creación o reenvío de esta cuenta, el CLI compara usuario y
correo con esos valores previstos y exige escribir exactamente `CONFIRM DEV
SUPERADMIN` antes de aceptar la credencial SMTP o ejecutar cambios. Una
identidad diferente o una confirmación incorrecta detiene la operación sin
crear la cuenta ni enviar correo. Esta verificación local complementa, pero no
sustituye, la aprobación nominal externa que debe revisar el operador.

Antes del comando real, el operador inyecta `SIMERTPI_SMTP_ENCRYPTION_KEY` como
secreto de entorno del proceso (Base64 de 32 bytes), el mismo que quedará en el
backend DEV, y confirma
`SIMERTPI_PUBLIC_BASE_URL` como el origen HTTPS exacto del portal DEV. No usar
archivos `.env`, historial, argumentos ni valores de producción. El CLI los
lee solo en memoria y apaga logging detallado.

Si el proveedor falla después del commit de la cuenta, el comando retorna un
estado diferenciado. Tras comprobar el resultado por auditoría y esperar el
límite de reenvío, repetir el wrapper con `-Superadministrator` no es correcto.
El reenvío controlado se ejecuta con `-ResendSuperadminInvitation`; solo acepta
el mismo usuario/correo si sigue deshabilitado, sin contraseña, con `IT_ADMIN`
único y consta en la auditoría del bootstrap. El token anterior se invalida por
el mecanismo existente; se aplica el límite de reenvío de invitaciones.

El bootstrap utiliza el operador explícito y el identificador de aprobación
para dejar auditoría. Serializa la operación, rechaza cualquier cuenta previa
con rol `IT_ADMIN` y rechaza cualquier nombre/correo existente: **no promueve ni
modifica cuentas previas**. El token no se expone en Angular ni en logs. No
ejecuta Flyway, seeders ni cambios de infraestructura.

Si el proceso termina con resultado ambiguo, no repetirlo automáticamente.
Primero comprobar el evento de auditoría y la existencia/estado/rol de la
cuenta mediante los mecanismos autorizados de solo lectura. No consultar ni
imprimir hashes de contraseñas.

## Verificación posterior

Una futura ejecución debe confirmar, mediante el portal y auditoría, que:

- La cuenta nueva corresponde al titular autorizado y permanece pendiente hasta
  que el titular abra el enlace y establezca su contraseña personal.
- Tiene exactamente el rol `IT_ADMIN`.
- Puede iniciar sesión en el portal administrativo.
- La auditoría contiene el operador, objetivo, resultado y referencia aprobada,
  sin contraseña, hash, cookie, token ni secreto.
- La cuenta existente no fue promovida ni alterada.

Al completar el enlace, confirmar que el usuario define y confirma su
contraseña en la página de activación y que después puede iniciar sesión con
`IT_ADMIN`. Nunca pedir al operador que elija o comunique esa contraseña.

Este documento no autoriza ejecutar el bootstrap en DEV ni en producción. La
ejecución real requiere autorización expresa del propietario para ese acto
concreto y una conexión verificada al destino DEV.
