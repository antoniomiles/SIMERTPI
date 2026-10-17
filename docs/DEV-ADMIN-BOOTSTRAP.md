# Bootstrap del primer SIMERTPI_ADMIN — propuesta no ejecutada en DEV

Estado: herramienta implementada y persistencia validada con PostgreSQL 16 aislado.
Se requiere autorización expresa futura para despliegue, migración y creación de cuenta.
No hay comando que se ejecute automáticamente al arrancar el backend.

## Requisitos previos

1. Confirmar que el servicio y la Shell son DEV, nunca producción.
2. Contrastar de manera independiente host, base y usuario de conexión con metadatos
   autorizados. Los valores reales deben aportarse/revisarse antes de ejecutar.
3. El recurso DEV permitido es dpg-db1hq55g1s2s73a8e340-a; base fija simertpi,
   puerto 5432, PostgreSQL 16. Si Render cambió esos metadatos, detenerse y revisar
   código y autorización; no relajar la verificación ni sustituirlo por producción.
4. El host esperado debe coincidir exactamente con DB_URL y con metadata JDBC; admite
   el nombre interno exacto del recurso o su hostname PostgreSQL bajo render.com.
   No se usa coincidencia parcial de cadenas. El usuario esperado debe coincidir
   exactamente con DB_USERNAME y current_user, y provenir de una aprobación externa
   a las variables del datasource.
5. DB_URL debe tener jdbc:postgresql://HOST:5432/simertpi?sslmode=verify-full y, si es
   necesario, &sslrootcert=/ruta/ca-publica-aprobada.pem. No incluir usuario/password
   en URL. Solo se admiten sslmode=verify-full y sslrootcert; el driver verificará
   certificado y hostname y se comprueba TLS en pg_stat_ssl. La CA pública debe estar
   disponible en la Shell/contenedor; no se presupone que ya esté instalada. Un host
   interno cuyo certificado no coincida no es válido: usar el host DEV aprobado con
   TLS verificable o detenerse. Nunca cambiar a require/disable para pasar el control.
6. Credenciales DB solo mediante secretos ya gestionados en el entorno. No copiar su
   contenido a archivos, comandos, tickets o logs. El comando no necesita conocerlas
   como argumentos. No se proveen credenciales ni usuarios administrativos por defecto.
7. Schema identity y audit previamente preparados con Flyway por el despliegue aprobado.
   Cuenta DB con USAGE de schemas, SELECT de users/roles/user_roles, INSERT en users,
   user_roles y functional_audit_log, y ejecución del advisory lock. No requiere DDL,
   UPDATE, DELETE o superusuario. Reutilizar la conexión del entorno solo tras revisión
   de sus permisos y destino.
8. Aprobación nominal del primer administrador (username, nombres, apellidos y correo),
   operador identificado y referencia de acta/ticket no sensible. No usar cuentas
   compartidas o una identidad ciudadana existente.
9. Terminal interactiva real que permita System.console() y readPassword sin eco.
   Si la Shell no proporciona TTY, la herramienta se niega a continuar. No sustituir
   por contraseña en stdin redirigido, variables, argumentos, archivos o pipelines.
10. Artefacto revisado disponible. El Dockerfile existente usa BOOT-INF y classpath.txt;
    no existe /app/app.jar en ese layout. Confirmar presencia del nuevo entry point.

## Comando propuesto para la Shell de Render DEV

Los campos en MAYÚSCULAS son metadatos no sensibles pendientes de aprobación. No copiar
este bloque sin sustituirlos con valores verificados. No contiene contraseña:

```sh
java -cp "$(cat /app/classpath.txt)" ec.gob.simertpiadmin.bootstrap.AdminBootstrapCli \
  --bootstrap-dev-admin \
  --expected-host=HOST_DEV_VERIFICADO \
  --expected-db-user=USUARIO_DB_DEV_VERIFICADO \
  --operator=OPERADOR_AUTORIZADO \
  --approval-id=ACTA_APROBADA
```

La Shell debe conservar DB_URL, DB_USERNAME y DB_PASSWORD gestionados, sin imprimirlos.
El propietario introduce los datos nominales aprobados y dos veces su contraseña sin
eco: al menos 16 caracteres Unicode y hasta 72 bytes UTF-8 por BCrypt. Se limpian los
buffers char[] al terminar. Se reutiliza PasswordEncodingConfiguration y los repositorios
existentes. El logging CLI usa configuración sin appenders, no cambia logs del servidor.

El proceso solo arranca un contexto no web, fuera del scan normal, con Flyway excluido,
DDL none, SQL init never y scheduling ausente. Fuerza la configuración de seguridad
sobre valores de entorno que pudieran activar inicialización o logging SQL.
Dentro de una transacción: advisory lock, ausencia de cualquier SIMERTPI_ADMIN, rechazo
de username/email existentes, creación habilitada con único rol SIMERTPI_ADMIN,
verificación y auditoría de éxito atómica con operador y aprobación. Dos procesos no
pueden crear dos primeros administradores. Repetir no cambia contraseña, roles ni otra
cuenta. Incluso un administrador deshabilitado obliga a detener el bootstrap; la
recuperación administrativa necesita otro procedimiento aprobado.

Los rechazos de persistencia tras validar destino se auditan sin detalles sensibles.
Errores previos a verificar destino no escriben auditoría en una base no confiable.
No se registra password, hash o cookie. Si hay fallo ambiguo, consultar primero los
resultados por mecanismos autorizados de solo lectura; no promover ni resetear cuentas.

## Después de una ejecución futura autorizada

Verificar identidad, único rol y estado mediante el portal/me y la auditoría aprobada,
sin consultar ni imprimir hashes. Cambiar o recuperar acceso solo mediante procedimiento
administrativo específico; el bootstrap no lo hace. El administrador web no obtiene
INSPECTOR ni acceso a la APK inspectora por este alta.
No crear inspector.pruebas.dev todavía: la gestión municipal sigue pendiente.

## Límites de la validación actual

Pruebas aisladas cubren BCrypt, alta única, invariantes, cuentas ajenas intactas,
repetición, ausencia de promoción, auditoría y concurrencia, además de contexto sin
HTTP/Flyway y validación exacta de URL/TLS. No se ha usado TTY humano con credenciales
operativas ni se ha abierto una conexión a PostgreSQL Render DEV. Faltan confirmar
host/base/usuario reales, CA y disponibilidad de System.console en la Shell destino.
