# Checkpoint 15 - Testcontainers, QA y CI

## Baseline

Develop limpio antes de modificar: HEAD 7b20e78. Migraciones actuales V1-V29. Reportes baseline existentes: 391 tests, 0 failures, 0 errors, 0 skipped. Java Microsoft 21.0.12.1; Maven 3.9.3; Spring Boot 3.5.6. No migracion nueva ni cambios funcionales.

## Infraestructura de integracion

AbstractPostgresIntegrationTest usa @DynamicPropertySource y un holder lazy: un PostgreSQLContainer por JVM Surefire, imagen oficial postgres:16.15, base efimera simertpi_test y credenciales aleatorias de test. No @Container por clase ni reutilizacion entre ejecuciones Maven. Ryuk elimina el container al terminar el proceso. Surefire usa un fork reutilizado; JUnit ejecuta clases secuencialmente por defecto. No se agrega paralelizacion de clases sobre fixtures compartidos.

Se alinean todos los modulos mediante testcontainers.version=1.21.4 en el BOM existente. Se eliminan los overrides 1.20.6 que mezclaban modulos antiguos con core administrado 1.21.3. La primera ejecucion con 1.21.3 fallo antes de iniciar PostgreSQL (Docker 29.8.1 rechazo el cliente con HTTP 400); el parche minimo 1.21.4 corrige esa incompatibilidad. Fuente oficial: https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4. No se actualiza Spring Boot ni dependencias generales.

Datasource URL/username/password provienen exclusivamente del container. Los valores vacios de test impiden volver accidentalmente al datasource DEV. localhost en URLs HTTP apunta al servidor embebido de puerto aleatorio, no a PostgreSQL local. El host del container puede ser localhost, pero su puerto es asignado por Docker y nunca 5432 configurado manualmente.

Flyway ejecuta V1-V29 sobre una base nueva y JPA valida el esquema. PostgresInfrastructureIntegrationTest comprueba URL/cat?logo/major version, validate/applied/pending, persistencia/consulta y constraint UNIQUE real. No modifica migraciones historicas, no usa H2 ni un PostgreSQL CI paralelo.

IntegrationTestConfiguration elimina exclusivamente el postprocesador automatico @Scheduled en contextos de test. Los beans/metodos permanecen para tests explicitos. Se fuerzan apagados timeout/reconciliation/outbox/dispatcher de fondo. Los providers son sandbox/fakes ya existentes, sin llamadas externas. Storage comun usa un directorio temporal externo al repo y cleanup al cerrar JVM; los tests de storage con overrides conservan sus temporales/cleanup propios.

## Tests migrados y aislamiento

12 clases existentes heredan la infraestructura:

- EnforcementHttpPostgresIntegrationTest
- NotificationHttpPostgresIntegrationTest
- NotificationProviderHttpIntegrationTest
- OperationalHttpIntegrationTest
- ParkingSessionCreationHttpIntegrationTest
- OperationalRecoveryPostgresIntegrationTest
- PaymentCreationHttpPostgresIntegrationTest
- PaymentProviderHttpIntegrationTest
- PermitHttpPostgresIntegrationTest
- SecurityHardeningHttpIntegrationTest
- VehicleControllerIntegrationTest
- ParkingCalendarServiceIntegrationTest

Se conservan assertions y escenarios. Fixtures UUID y cleanup propios aislan datos. Calendar ahora crea/elimina horarios de prueba explicitos en lugar de depender de una zona instalada manualmente. Vehicle limpia usuarios/roles/vehiculos al terminar. La primera suite sobre container detecto otro defecto de fixture: una extension de 90+30 minutos cruzaba el cierre cuando se ejecutaba de noche. ParkingSessionCreationHttpIntegrationTest configura un offset operativo de test que situa el instante actual a mediodia y usa ese mismo offset para fechas/horarios/feriados del fixture. No se cambian instantes, assertions ni el motor productivo; timezone de produccion permanece intacta. Un Clock inyectable futuro permitiria casos temporales aun mas explicitos sin depender del wall clock. Audit append-only permanece durante esta ejecucion efimera y no contamina otra ejecucion Maven. No se trunca ni altera la DB del usuario.

Tag heredado integration distingue contextos DB. Unit tests y el slice MVC existente no heredan la base y no arrancan Docker:

```sh
mvn test "-Dgroups=!integration"
```

Suite completa requerida:

```sh
mvn test
```

Requisitos: Java 21, Maven y Docker/Testcontainers compatible con containers Linux. Docker no disponible hace fallar los integration tests: no se omiten y no existe fallback local.

Los integration tests no requieren una instalación local
manual de PostgreSQL.

## CI Azure DevOps

Archivo simertpi-backend/azure-pipelines.yml (seleccionar esta ruta al crear el pipeline Azure). Triggers develop/main y PR. Agente ubuntu-24.04, JavaToolInstaller selecciona Java 21 preinstalado, valida Maven/Docker y ejecuta mvn --batch-mode --no-transfer-progress test desde backend. Docker solo es runtime Testcontainers; no se instala PostgreSQL ni se crea service container.

PublishTestResults publica XML JUnit y falla con tests fallidos/reportes ausentes; PublishPipelineArtifact conserva Surefire como evidencia, ambos always(). No continueOnError ni skipTests. Flyway/container/compilacion/test fallidos mantienen el job fallido. No se agrega cache inicialmente.

## Resultados finales

Validaciones intermedias: 200 tests sin integration, 0 failures/errors/skipped, sin inicializar Testcontainers; prueba focalizada de infraestructura/calendario: 8 tests verdes. Primer mvn test con 1.21.3: 393 tests, 0 failures, 193 errors, 0 skipped por Docker incompatible. Segundo mvn test con 1.21.4: 393 tests, 0 failures, 1 error, 0 skipped por fixture de extension dependiente de la hora. Ambos problemas se corrigieron sin modificar reglas funcionales. Resultado final: mvn test BUILD SUCCESS, 393 tests, 0 failures, 0 errors, 0 skipped; 391 baseline + 2 pruebas nuevas, sin eliminaciones. Un solo PostgreSQL container 16.15; JDBC observado jdbc:postgresql://localhost:59228/simertpi_test?loggerLevel=OFF. Flyway aplica/valida 29 migraciones desde schema vacio; version final V29. git diff --check sin errores. No se uso la DB local ni se alteraron servicios del usuario. CI queda preparado; no se configura ni ejecuta un pipeline remoto desde CP15.

## Deuda tecnica

Registrar el pipeline en Azure DevOps y validar su primera ejecucion alojada. QA E2E/performance/seguridad (Karate, Serenity, K6) queda para un trabajo posterior; no se duplica el backend. Container Linux requiere Docker accesible y acceso al registry/cache de imagen oficial. Paralelizacion futura exige revisar aislamiento de fixtures/locks y no debe habilitarse ciegamente.

## Inventario

Archivos creados (5):

- `azure-pipelines.yml`
- `docs/checkpoint-15.md`
- `src/test/java/ec/gob/simertpi/testsupport/AbstractPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/testsupport/IntegrationTestConfiguration.java`
- `src/test/java/ec/gob/simertpi/testsupport/PostgresInfrastructureIntegrationTest.java`

Archivos modificados (14):

- `pom.xml`
- `src/test/java/ec/gob/simertpi/api/enforcement/EnforcementHttpPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/notifications/NotificationHttpPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/notifications/NotificationProviderHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/operations/OperationalHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/payments/OperationalRecoveryPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/payments/PaymentCreationHttpPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/payments/PaymentProviderHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/permits/PermitHttpPostgresIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/security/SecurityHardeningHttpIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/api/vehicles/VehicleControllerIntegrationTest.java`
- `src/test/java/ec/gob/simertpi/application/parking/calendar/ParkingCalendarServiceIntegrationTest.java`
- `src/test/resources/application.properties`
