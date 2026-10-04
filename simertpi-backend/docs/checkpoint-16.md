# Checkpoint 16 - Docker, entornos y CI/CD

## Baseline y alcance

Develop limpio, CP15 commiteado: 5ee1188 (test: add Testcontainers QA and Azure CI pipeline). Reportes existentes: 393 tests, 0 failures/errors/skipped. Migracion actual V29. Java 21 / Spring Boot 3.5.6 se mantienen. Docker Desktop/Engine 29.8.1, Linux amd64. No cambios de negocio ni migraciones nuevas/historicas.

## Docker y configuracion

Dockerfile multi-stage: build maven:3.9.11-eclipse-temurin-21 y runtime eclipse-temurin:21-jre-alpine, ambos fijados por digest oficial en Dockerfile. La version Maven de la imagen no cambia Maven 3.9.3 instalado localmente. SOURCE_DATE_EPOCH fija timestamps del JAR; VCS_REF identifica revision. Actualizar bases/digests debe ser un cambio revisado, con reconstruccion y pruebas. El paquete curl resuelto durante build requiere reconstruccion y futura vigilancia de vulnerabilidades; no se afirma reproducibilidad bit a bit de paquetes APK del registry.

BuildKit usa cache Maven y copia solo pom.xml/src/main. No usa target local ni empaqueta test fixtures, .env, credenciales o IDE. Maven package dentro del build no encuentra fuentes de test; la validacion QA obligatoria ocurre antes en CI con mvn test/Testcontainers. No se utiliza skipTests para simular un quality gate.

Runtime solo contiene JRE, curl para probe y app.jar; UID/GID 10001:10001, exec ENTRYPOINT java como PID 1, SIGTERM y server.shutdown=graceful, timeout 30s configurable. Puerto SERVER_PORT configurable (8080 por defecto), EXPOSE es metadata. Readiness de CP14 usa DB y no providers; Docker HEALTHCHECK usa ese endpoint, no solo puerto abierto. Unhealthy no equivale a liveness ni implica restart automatico por Docker. Infraestructura futura debe distinguir liveness para proceso y readiness para trafico.

Application.yml elimina datasource/credenciales DEV hardcodeadas del backend: DB_URL/DB_USERNAME/DB_PASSWORD son externos y obligatorios. Ningun password productivo va a imagen/ARG/JVM flags. JVM permite configuracion externa Spring, environment y secretos montados/secret manager futuro. No se agregan credenciales. .env.example solo contiene placeholders; .gitignore ignora .env/.env.* (excepto ejemplo). .dockerignore excluye esos archivos, target, .git, IDE, logs, dumps, archivos de claves y tests/docs no requeridos por build.

Evidencia local: /var/lib/simertpi/evidence en imagen, propietario app. Compose monta un volumen persistente. PROD exige SIMERTPI_EVIDENCE_STORAGE_BASE_PATH externo (Docker lo configura a ese directorio); deployment debe montar almacenamiento durable y comprobar permisos/backup, no confiar en la capa efimera de la imagen. No se integra storage cloud.

## Variables consumidas

Aliases leidos explicitamente en application.yml:

- `DB_PASSWORD`
- `DB_URL`
- `DB_USERNAME`
- `SERVER_PORT`
- `SIMERTPI_CONTROL_SCHEDULER_ENABLED`
- `SIMERTPI_EVIDENCE_RECONCILIATION_DELAY_MS`
- `SIMERTPI_EVIDENCE_RECONCILIATION_ENABLED`
- `SIMERTPI_EVIDENCE_STORAGE_BASE_PATH`
- `SIMERTPI_NOTIFICATION_DISPATCHER_ENABLED`
- `SIMERTPI_NOTIFICATION_EMAIL_PROVIDER`
- `SIMERTPI_NOTIFICATION_PUSH_PROVIDER`
- `SIMERTPI_NOTIFICATION_SANDBOX_ENABLED`
- `SIMERTPI_NOTIFICATION_WHATSAPP_PROVIDER`
- `SIMERTPI_OUTBOX_BACKOFF_SECONDS`
- `SIMERTPI_OUTBOX_ENABLED`
- `SIMERTPI_OUTBOX_MAX_ATTEMPTS`
- `SIMERTPI_OUTBOX_PROCESSING_TIMEOUT_SECONDS`
- `SIMERTPI_OUTBOX_RECOVERY_DELAY_MS`
- `SIMERTPI_OUTBOX_RECOVERY_ENABLED`
- `SIMERTPI_PARKING_TIME_ZONE`
- `SIMERTPI_PAYMENT_PROVIDER`
- `SIMERTPI_PAYMENT_RECONCILIATION_DELAY_MS`
- `SIMERTPI_PAYMENT_RECONCILIATION_ENABLED`
- `SIMERTPI_PAYMENT_SANDBOX_ENABLED`
- `SIMERTPI_PENDING_TIMEOUT_ENABLED`
- `SIMERTPI_SHUTDOWN_TIMEOUT`

SPRING_PROFILES_ACTIVE es propiedad nativa Spring Boot; Docker default prod, Compose default dev. DB_NAME, DEV_HTTP_PORT y SIMERTPI_BACKEND_IMAGE son solo Compose. VCS_REF y SOURCE_DATE_EPOCH son argumentos de build, nunca secretos. Properties adicionales existentes se pueden suministrar mediante configuracion Spring externa; no se inventan URLs de provider ni secretos placeholders de banco.

## Profiles y ambientes

No se duplican application-dev/qa/uat completos. El profile prod agrega exclusivamente sandbox=false y requisito de storage externo. Contratos CP12/CP13 aceptan sandbox exclusivamente bajo perfiles dev/test y opt-in; no se alteran. Un profile qa combinado con dev seguiria siendo rechazado por esos contratos, por eso ambiente logico y profile Spring son decisiones separadas:

| Ambiente | Configuracion/profile | Proveedores |
|---|---|---|
| DEV | Local, dev | UNCONFIGURED por defecto; sandbox solo opt-in |
| QA | Externa; dev solo para sandbox aislado, o prod para ensayo equivalente a produccion | Seleccion explicita; sin servicios reales inexistentes |
| UAT | Externa, prod | Sandbox interno prohibido; UNCONFIGURED mientras no exista adaptador real |
| PROD | Externa, prod, secretos externos y storage durable | Sandbox interno prohibido; provider real solo tras integracion oficial |

No se inventan hosts cloud, subscriptions, domains, registry o service connections. Profiles nunca deben escogerse a partir de entrada del ciudadano; deployment debe imponer la politica del ambiente.

## Compose DEV y Testcontainers

Se inspecciono ../docker-compose.yml: stack DB heredado con nombre/puerto/credenciales DEV existentes. Se conserva intacto para no alterar la DB del usuario. compose.dev.yml es un stack opt-in aislado del backend dentro de esta carpeta, sin container_name fijo ni publicar puerto PostgreSQL. No requiere ejecutar ambos Compose: escoger el stack heredado para desarrollo nativo o este stack para backend containerizado. No reutiliza volumen del stack heredado.

Compose nuevo: PostgreSQL 16.15 + backend, credenciales externas obligatorias, DB healthcheck, backend depends_on healthy, puerto HTTP solo en loopback, volumen DB y volumen evidencia. Configuracion interna DB_URL usa el servicio postgres; no es un endpoint cloud. stop_grace_period=35s da margen al apagado Spring. Cambiar password en environment no cambia una DB ya inicializada: gestionar rotacion de forma controlada, sin borrar datos para resolverlo.

Tests y CI TEST mantienen PostgreSQL Testcontainers CP15, datasource dinamico, base vacia y cleanup Ryuk. No dependen de Compose ni del PostgreSQL local. No parar servicios del usuario durante validacion.

## CI real / CD preparado

Azure pipeline CP15 conservado: Java 21, mvn test, Testcontainers, publicacion JUnit/Surefire incluso ante fallo. Se agregan triggers release/* y hotfix/* a develop/main; feature/* llega por PR a develop. Despues del quality gate se empaquetan clases ya compiladas con jar:jar spring-boot:repackage para no repetir la suite, se construye Docker, se comprueba UID no-root y Compose config. Un error detiene empaquetado/publicacion de imagen.

Se publica backend-container como Pipeline Artifact: archivo Docker tar.gz, image.json (imageId/revision/user) y SHA256SUMS. Exportacion solo en Build.ArtifactStagingDirectory del agente, nunca repo; no se exporta tar local durante CP16. No hay registry configurado ni docker push. ImageId es el identificador Docker local, no se presenta como manifest digest de registry.

CD no ejecutado: faltan registry/service connections/destinos/Azure Environments. Integracion posterior, sin valores ficticios:

1. Registrar registry real y service connection de privilegio minimo; validar checksum del artefacto y publicar esa misma imagen, registrar manifest digest real.
2. Definir destino/configuracion externa DEV y un deployment job hacia el Environment real. Aceptar release solo tras readiness/Flyway/compatibilidad/storage verificados.
3. Promover exactamente ese digest a QA con criterios de aceptacion; no reconstruir por ambiente.
4. release/* candidata a UAT con approvals/checks del Environment real y configuracion prod.
5. main/release aprobada a PROD con approvals/checks externos, permisos minimos y rollback listo.

No existen jobs de deploy vacios que aparenten exito. Crear nombres/IDs reales de ambientes/conexiones y conectar gates queda para infraestructura definida. No se modifican politicas reales de Azure.

## Versionado, ramas, approvals y rollback

CI tag sha-<commit completo> y OCI revision de Build.SourceVersion. Una release puede agregar tag de version conservando el mismo digest; no latest. Promocion debe registrar digest, Git SHA, build/artifact y revision de configuracion no secreta. La imagen local cp16-local incorpora cambios aun no commiteados: su label refiere baseline, no representa una release de ese SHA.

Flujo conceptual: feature/* -> PR develop; develop -> DEV/QA; release/* -> UAT; main -> PROD; hotfix/* -> flujo controlado/PR con aprobacion y sincronizacion posterior. No se crean ramas. QA/UAT/PROD deben tener aprobaciones/checks reales en Azure Environments, administrados fuera del YAML; no se inventan personas/grupos/correos.

Rollback de aplicacion: seleccionar digest previamente aprobado y configuracion compatible, redeploy, verificar readiness y operaciones. NO rollback DB destructivo automatico, borrar history Flyway ni downgrades improvisados. Migraciones deben ser forward-compatible (expand/contract cuando aplique); si una version previa no admite schema vigente, rollback puede no ser seguro y requiere estrategia forward fix. No se afirma que el rollback haya sido desplegado/probado en cloud.

## Security gates / backup / DR

Gates actuales: compilacion/tests/Flyway, configuracion Compose y UID no-root. Puntos pendientes para herramientas oficialmente elegidas: SAST + secret scan antes de empaquetar; dependency scan antes de promover artefacto; image scan tras Docker build y antes de publicar/promover. No se instalan scanners arbitrarios ni se afirma que analisis CVE/SAST haya pasado. PR/branch approvals y Azure environment checks deben ser bloqueantes antes de CD real.

Backup/DR no operativo en CP16: planificar backups PostgreSQL y evidencia, retencion/cifrado/acceso, restauracion en destino aislado y verificaciones metadata/hash/DB. Snapshots/volumen Docker no equivalen a backup validado. No se encontraron objetivos RPO/RTO en docs CP14/CP15; deben confirmarse, no se inventan. Antes de PROD se requieren restore drills y responsables oficiales.

## Comandos

Desde backend, tras suministrar variables externas (o una copia local no versionada del ejemplo con valores exclusivamente DEV):

```sh
mvn test
docker version
docker build --build-arg VCS_REF=<git-sha> --build-arg SOURCE_DATE_EPOCH=<commit-epoch> -t simertpi-backend:sha-<git-sha> .
docker compose -f compose.dev.yml config --quiet
docker compose -f compose.dev.yml up -d --build
```

No imprimir docker compose config completo con secretos; --quiet valida sin mostrarlos. Para apagar el stack DEV sin borrar datos: docker compose -f compose.dev.yml down (sin --volumes). Nunca ejecutar down contra el proyecto equivocado ni borrar vol?menes del usuario. Flyway se ejecuta al arrancar backend; no hay scripts de migracion paralelos.

## Validacion y deuda

Docker build real exitoso: imagen simertpi-backend:cp16-local, aproximadamente 414 MB (395 MiB), UID/GID 10001:10001. Compose config --quiet correcto. Smoke local aislado con profile prod y puerto interno configurable 18080: health/liveness/readiness 200 y UP, correlationId presente, metrics anonimo 401, storage escribible por UID 10001. PostgreSQL 16.15 limpio recibio las 29 migraciones. SIGTERM completo graceful shutdown, exit 143; se retiraron contenedores, red y los dos volumenes exclusivos del smoke, sin tocar recursos del usuario.

Validacion completa unica mvn test: BUILD SUCCESS, 393 tests, 0 failures, 0 errors, 0 skipped (baseline 393, sin regresion ni eliminaciones). Testcontainers uso PostgreSQL 16.15, JDBC dinamico localhost:59810/simertpi_test; localhost representa el puerto aleatorio publicado por Docker, no PostgreSQL local en 5432. Flyway validate correcto para 29 migraciones, version final V29. Comando CI jar:jar spring-boot:repackage tambien BUILD SUCCESS sin repetir tests. git diff --check correcto; solo los nueve archivos previstos aparecen modificados/nuevos. Sin .env real, tar exportado, logs, dumps ni secretos nuevos versionables; target/Surefire quedan ignorados como artefactos normales Maven.

CI alojado/CD no ejecutados: registrar pipeline, registry/destinos/Environments, conectar scanners y aprobaciones, formalizar secretos, backup/restore y RPO/RTO. Integraciones reales de pagos/notificaciones y observabilidad externa siguen pendientes de documentacion/infraestructura oficial. No se despliega PROD ni se crea infraestructura cloud.

Archivos creados: Dockerfile, .dockerignore, .env.example, compose.dev.yml, src/main/resources/application-prod.yml y este documento. Modificados: .gitignore, azure-pipelines.yml y src/main/resources/application.yml. No hay nuevas dependencias, migraciones, cambios en tests ni reglas funcionales. No se ejecutan git add/commit/push.
