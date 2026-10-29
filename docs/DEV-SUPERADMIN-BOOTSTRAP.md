# Aprovisionamiento inicial del Superadministrador SIMERTPI

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

## Ejecución futura autorizada

Solo después de aprobar el preflight y revisar los datos de destino, repetir el
comando sin `-PreflightOnly`, desde una consola interactiva no redirigida. El
ejecutor conserva las credenciales PostgreSQL ya gestionadas fuera de los
argumentos. La contraseña de la nueva cuenta se solicita dos veces mediante
`Console.readPassword`, no se hace eco, se valida con el límite actual del
encoder y se limpia de memoria al terminar. No proporcionar contraseñas por
argumentos, variables de cuenta, archivos, pipes, tickets o chat.

El bootstrap utiliza el operador explícito y el identificador de aprobación
para dejar auditoría. En una transacción serializada, crea una identidad nueva
con el único rol `IT_ADMIN`, la verifica y registra el resultado. Rechaza un
nombre o correo ya existente: **no promueve ni modifica cuentas previas**. No
ejecuta Flyway, seeders ni cambios de infraestructura.

Si el proceso termina con resultado ambiguo, no repetirlo automáticamente.
Primero comprobar el evento de auditoría y la existencia/estado/rol de la
cuenta mediante los mecanismos autorizados de solo lectura. No consultar ni
imprimir hashes de contraseñas.

## Verificación posterior

Una futura ejecución debe confirmar, mediante el portal y auditoría, que:

- La cuenta nueva corresponde al titular autorizado y está habilitada.
- Tiene exactamente el rol `IT_ADMIN`.
- Puede iniciar sesión en el portal administrativo.
- La auditoría contiene el operador, objetivo, resultado y referencia aprobada,
  sin contraseña, hash, cookie, token ni secreto.
- La cuenta existente no fue promovida ni alterada.

Este documento no autoriza ejecutar el bootstrap en DEV ni en producción. La
ejecución real requiere autorización expresa del propietario para ese acto
concreto y una conexión verificada al destino DEV.
