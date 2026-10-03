# Checkpoint 9: evidencias y almacenamiento local

La implementación existente se completó sin reconstruir módulos ni cambiar V1–V25. No se agregó V26: enforcement.evidence ya contiene todos los campos requeridos y V18 protege la combinación de infracción, key y SHA-256. PostgreSQL guarda solamente metadata.

## Flujo y decisiones

- POST /api/v1/evidence recibe multipart, violationId, file y capturedAt/latitude/longitude opcionales. Idempotency-Key es obligatorio. Campos adicionales storageKey, sha256Hash, inspectorId y fileSize no tienen efecto; las pruebas HTTP los envían para verificarlo.
- Solamente un INSPECTOR activo propietario de la infracción puede cargar. Lecturas requieren propietario INSPECTOR, SUPERVISOR o SIMERTPI_ADMIN. SecurityConfig e InspectorAuthorizationService mantienen las reglas existentes; ciudadano recibe 403 y anónimo 401.
- Se permiten JPEG (.jpg/.jpeg), PNG y PDF. Se comprueban archivo no vacío, máximo configurado (10 MiB por defecto), extensión, MIME declarado, magic bytes y coherencia. Esto detecta spoofing básico, sin ejecutar, interpretar, descomprimir ni certificar completamente la estructura del archivo. No se incorporó MP4.
- El nombre se reduce a basename, se eliminan controles y se limita a 255 caracteres. La key se genera exclusivamente como violations/{violationId}/{sha256}.{extension}, con jpeg normalizado a jpg. SHA-256 proviene de los bytes reales y se guarda en minúsculas.
- ValidatedFile copia defensivamente los bytes; upload vuelve a validar y calcular metadata para impedir que un llamador interno construya metadata falsa.
- El fingerprint idempotente incluye hash real, tamaño, infracción, nombre y MIME, fecha y coordenadas, además del nombre/MIME declarados originales. Una repetición devuelve el mismo ID; un cambio bajo la misma key produce 409. La key está acotada y se separa por usuario/operación.
- EvidenceRepository.lockViolation adquiere PESSIMISTIC_WRITE antes de consultar duplicados y escribir el objeto. Esto serializa cargas simultáneas de la misma infracción, incluidas keys diferentes. V18 conserva la protección SQL.
- LocalObjectStorage conserva la abstracción ObjectStorage: putIfAbsent, read con InputStream, exists y delete para compensación. Base-path configurable, normalización, whitelist de keys, rechazo de enlaces simbólicos, CREATE_NEW y limpieza de escrituras incompletas. El directorio pertenece al proceso backend y debe tener permisos restringidos a nivel del sistema operativo.
- Después de crear un objeto nuevo se registra TransactionSynchronization. ROLLED_BACK elimina solamente ese objeto; COMMITTED lo conserva. UNKNOWN lo conserva y registra una advertencia sin rutas, porque borrarlo podría romper una transacción confirmada. Objetos preexistentes nunca reciben callback de eliminación. Fallos inmediatos sin sincronización también compensan.
- EVIDENCE_CREATED se registra explícitamente después de persistir una evidencia nueva, dentro de la misma transacción. Los retornos por replay o deduplicación no registran otra creación. EVIDENCE_UPLOAD_FAILED registra failures con REQUIRES_NEW y metadata limitada al tipo de excepción. EVIDENCE_DOWNLOADED usa @Audited después de autorización e integridad.
- AuditService existente obtiene actor, roles, IP, User-Agent y correlationId; no se registran bytes, keys ni rutas. CorrelationIdFilter se conserva: reutiliza IDs válidos, genera otros y los devuelve en response.
- GET /api/v1/evidence/{id}/content recupera mediante ObjectStorage. Siempre valida tamaño y SHA-256 antes de devolver cualquier byte. Se lee un snapshot acotado por max-file-size-bytes y se responde desde un stream de memoria: evita que una modificación posterior del archivo invalide una comprobación previa. El controller no accede al filesystem.
- Descarga con Content-Type de metadata validada, Content-Disposition attachment con nombre UTF-8 seguro, Content-Length y X-Content-Type-Options: nosniff. DTOs no exponen storageKey, base-path ni ruta física.
- Se eliminaron EvidenceService.create, EnforcementIdempotencyService.createEvidence, EvidencePayload y CreateEvidenceRequest. Sus únicos usos eran el flujo legacy y pruebas; no había una necesidad productiva que justificara conservarlos. Las pruebas existentes ahora cargan bytes reales.

## Cobertura de los 35 escenarios solicitados

E = EnforcementHttpPostgresIntegrationTest; U = EvidenceStorageTest; S = EnforcementServicesTest.

| Escenarios CP9 | Cobertura |
|---|---|
| 1–3 JPEG, PNG, PDF | E.securesAndPersistsInspectionViolationEvidenceAndLookup; E.acceptsPngAndPdfAndRejectsInvalidMultipartFiles; U.supportsValidatedFormats (3 casos) |
| 4 archivo vacío | U.rejectsEmpty; E.acceptsPngAndPdfAndRejectsInvalidMultipartFiles |
| 5 máximo | U.rejectsOversized |
| 6 MIME inválido | U.rejectsDeclaredMime; E.acceptsPngAndPdfAndRejectsInvalidMultipartFiles |
| 7 spoofing | U.rejectsSpoofedMime; E.acceptsPngAndPdfAndRejectsInvalidMultipartFiles |
| 8 extensión | U.rejectsExtension; U.rejectsMismatchedExtension; E.acceptsPngAndPdfAndRejectsInvalidMultipartFiles |
| 9–10 filename/key | U.sanitizesFilenameAndGeneratesKey; U.doesNotTrustForgedValidatedFile; E.securesAndPersistsInspectionViolationEvidenceAndLookup |
| 11–13 hash y asociación | U.supportsValidatedFormats; U.defensiveCopyProtectsHash; S.persistsRealSha256AndReusesDuplicate; E.securesAndPersistsInspectionViolationEvidenceAndLookup |
| 14–17 autenticación/acceso horizontal | E.evidenceAuditCorrelationDeduplicationAndAccess; E.securesAndPersistsInspectionViolationEvidenceAndLookup; U.rejectsHorizontalUpload |
| 18–20 descarga/ciudadano/DTO | E.securesAndPersistsInspectionViolationEvidenceAndLookup; E.evidenceAuditCorrelationDeduplicationAndAccess |
| 21–22 replay/conflicto | E.securesAndPersistsInspectionViolationEvidenceAndLookup; E.evidenceAuditCorrelationDeduplicationAndAccess (incluye nombres diferentes que normalizan al mismo basename) |
| 23–24 metadata/archivo único | E.evidenceAuditCorrelationDeduplicationAndAccess; E.concurrentUploadsWithDifferentKeysCreateOneObjectAndOneAudit; U.duplicateDoesNotWriteOrAudit |
| 25 fallo storage | U.storageFailureDoesNotSaveMetadata |
| 26 fallo DB/compensación | U.databaseFailureCompensatesWithoutSynchronization; U.rollbackCompensatesNewObjectAfterDeferredDatabaseFailure; E.realDatabaseRollbackRemovesNewObjectAndMetadata (error SQL real después de flush) |
| 27 objeto preexistente | U.rollbackNeverDeletesPreexistingObject; U.rollbackRetainsPreviouslyCommittedDuplicate |
| 28–30 auditoría/correlation/replay | E.evidenceAuditCorrelationDeduplicationAndAccess; E.acceptsPngAndPdfAndRejectsInvalidMultipartFiles; U.duplicateDoesNotWriteOrAudit |
| 31 hash descargado | E.securesAndPersistsInspectionViolationEvidenceAndLookup; U.supportsValidatedFormats; U.rejectsCorruptContentBeforeReturningStream; U.rejectsOversizedRetrievedContent |
| 32 traversal local | U.rejectsTraversalOnAllStorageOperations |
| 33 overwrite | U.preventsOverwriteAndReturnsStreamWithoutPath |
| 34 disabled | U.disabledStorageFailsControlled |
| 35 rutas ocultas | U.preventsOverwriteAndReturnsStreamWithoutPath; E.evidenceAuditCorrelationDeduplicationAndAccess |

También se verifica commit y resultado transaccional UNKNOWN, permisos de supervisor/admin, Flyway.validateWithResult y última migración V25.

## Entorno y limpieza

Pruebas HTTP contra PostgreSQL local 16.15 en localhost:5432/simertpi, sin Testcontainers. Las fixtures funcionales se eliminan al terminar; la auditoría append-only se conserva conforme a V25.

Las pruebas HTTP crean un directorio único bajo java.io.tmpdir con prefijo simertpi-cp9-http-. Eliminan objetos antes de borrar metadata y eliminan el directorio completo en AfterAll. Los unit tests usan JUnit @TempDir y su limpieza automática. No se guardan evidencias dentro del repositorio.

La primera ejecución de la prueba original dejó un JPEG en java.io.tmpdir/simertpi-evidence; se identificó por la hora de esta ejecución y se retiró únicamente ese archivo. Las pruebas actualizadas tienen limpieza explícita.

## Observaciones para checkpoints futuros

- Esta implementación local es para DEV/TEST. No se agregaron proveedores cloud ni credenciales.
- La compensación cubre fallos y rollback del proceso vivo. Una caída abrupta entre escritura y commit, un fallo al eliminar o un resultado UNKNOWN necesita una reconciliación operativa futura. Los objetos conservados por resultado incierto no se borran automáticamente.
- La validación por signatures detecta spoofing básico; no incluye antivirus ni análisis profundo de documentos. Los archivos se entregan como attachment.
- La descarga usa memoria acotada por el máximo configurado. Para archivos grandes o mayor concurrencia convendrá una estrategia de snapshot/stream verificado distinta.
- EVIDENCE_DOWNLOADED significa acceso autorizado a contenido íntegro preparado para entrega; no garantiza que la conexión del cliente complete la transferencia.
- Se conserva el estado Git previo con numerosos archivos sin seguimiento de checkpoints anteriores. No se ejecutaron git add, commit ni push.

## Verificación final

Resultado: PASS CON OBSERVACIONES (limitaciones operativas documentadas arriba).

mvn test: BUILD SUCCESS. Tests run: 135, Failures: 0, Errors: 0, Skipped: 0. Total time: 02:19 min. Finished at: 2026-10-03T15:01:25-05:00. Los mensajes ERROR del caso de scheduler corresponden a un fallo simulado esperado por una prueba que pasa.

Flyway: 25 migraciones validadas; current version 25; validateWithResult exitoso. Sin V26.

git diff --check: exit code 0. Solamente avisos LF/CRLF sobre archivos preexistentes. Whitespace de todos los archivos CP9 sin seguimiento revisado adicionalmente: OK.

Directorio HTTP de la ultima suite: C:/Users/antox/AppData/Local/Temp/simertpi-cp9-http-10319788735309942278 (eliminado al terminar). Unit tests: subdirectorios JUnit @TempDir bajo java.io.tmpdir, eliminados automaticamente. Sin archivos fisicos huerfanos ni archivos de evidencia dentro del repositorio.

Archivos creados en esta revision: EvidenceStorageTest.java y docs/checkpoint-9.md.
Archivos existentes modificados: EvidenceService.java, EnforcementIdempotencyService.java, EvidenceRepository.java, EvidencePersistenceAdapter.java, LocalObjectStorage.java, EvidenceController.java, EnforcementServicesTest.java, EnforcementHttpPostgresIntegrationTest.java.
Archivo legacy retirado: CreateEvidenceRequest.java.

Estado Git completo (incluye trabajo previo; nada preparado para commit):

```text
 M pom.xml
 M src/main/java/ec/gob/simertpi/SimertpiBackendApplication.java
 M src/main/java/ec/gob/simertpi/api/GlobalExceptionHandler.java
 M src/main/java/ec/gob/simertpi/api/identity/CreateUserRequest.java
 M src/main/java/ec/gob/simertpi/application/identity/UserService.java
 M src/main/java/ec/gob/simertpi/config/SecurityConfig.java
 M src/main/java/ec/gob/simertpi/domain/identity/repository/UserRepository.java
 M src/main/resources/application.yml
?? docs/
?? src/main/java/ec/gob/simertpi/api/AuthenticationRequiredException.java
?? src/main/java/ec/gob/simertpi/api/CorrelationIdFilter.java
?? src/main/java/ec/gob/simertpi/api/ForbiddenException.java
?? src/main/java/ec/gob/simertpi/api/IdempotencyConflictException.java
?? src/main/java/ec/gob/simertpi/api/InvalidIdempotencyKeyException.java
?? src/main/java/ec/gob/simertpi/api/InvalidRequestException.java
?? src/main/java/ec/gob/simertpi/api/ResourceNotFoundException.java
?? src/main/java/ec/gob/simertpi/api/audit/
?? src/main/java/ec/gob/simertpi/api/enforcement/
?? src/main/java/ec/gob/simertpi/api/notifications/
?? src/main/java/ec/gob/simertpi/api/parking/
?? src/main/java/ec/gob/simertpi/api/payments/
?? src/main/java/ec/gob/simertpi/api/permits/
?? src/main/java/ec/gob/simertpi/api/vehicles/
?? src/main/java/ec/gob/simertpi/application/audit/
?? src/main/java/ec/gob/simertpi/application/enforcement/
?? src/main/java/ec/gob/simertpi/application/idempotency/
?? src/main/java/ec/gob/simertpi/application/notifications/
?? src/main/java/ec/gob/simertpi/application/parking/
?? src/main/java/ec/gob/simertpi/application/payments/
?? src/main/java/ec/gob/simertpi/application/permits/
?? src/main/java/ec/gob/simertpi/application/vehicles/
?? src/main/java/ec/gob/simertpi/config/SimertpiUserDetailsService.java
?? src/main/java/ec/gob/simertpi/domain/audit/
?? src/main/java/ec/gob/simertpi/domain/configuration/
?? src/main/java/ec/gob/simertpi/domain/enforcement/
?? src/main/java/ec/gob/simertpi/domain/notification/
?? src/main/java/ec/gob/simertpi/domain/parking/
?? src/main/java/ec/gob/simertpi/domain/payments/
?? src/main/java/ec/gob/simertpi/domain/permits/
?? src/main/java/ec/gob/simertpi/domain/vehicles/
?? src/main/java/ec/gob/simertpi/infrastructure/
?? src/main/resources/db/migration/V16__add_street_description.sql
?? src/main/resources/db/migration/V17__create_session_extensions.sql
?? src/main/resources/db/migration/V18__add_evidence_idempotency_constraint.sql
?? src/main/resources/db/migration/V19__add_unique_violation_per_inspection.sql
?? src/main/resources/db/migration/V20__create_control_events_and_notification_rules.sql
?? src/main/resources/db/migration/V21__update_active_space_constraint.sql
?? src/main/resources/db/migration/V22__update_parking_session_status_constraint.sql
?? src/main/resources/db/migration/V23__parametrize_parking_calendar.sql
?? src/main/resources/db/migration/V24__enable_multichannel_notifications.sql
?? src/main/resources/db/migration/V25__create_functional_audit.sql
?? src/test/
```

No se ejecutaron git add, git commit ni git push. Checkpoint 10 no iniciado.

