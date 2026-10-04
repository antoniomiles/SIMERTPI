package ec.gob.simertpi.api.enforcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import ec.gob.simertpi.domain.enforcement.entity.Violation;
import ec.gob.simertpi.domain.enforcement.repository.EvidenceRepository;
import ec.gob.simertpi.domain.enforcement.repository.InspectionRepository;
import ec.gob.simertpi.domain.enforcement.repository.ViolationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnforcementHttpPostgresIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
    static final java.nio.file.Path STORAGE_DIRECTORY = temporaryStorage();
    static java.nio.file.Path temporaryStorage() {
        try { return java.nio.file.Files.createTempDirectory("simertpi-cp9-http-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void storageProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("simertpi.evidence.storage.base-path", () -> STORAGE_DIRECTORY.toString());
        registry.add("simertpi.control.scheduler.enabled", () -> false);
    }
    @org.junit.jupiter.api.AfterAll
    static void removeTemporaryStorage() throws Exception {
        try (var paths = java.nio.file.Files.walk(STORAGE_DIRECTORY)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
        }
        assertThat(java.nio.file.Files.exists(STORAGE_DIRECTORY)).isFalse();
    }
    @Autowired ec.gob.simertpi.application.enforcement.storage.ObjectStorage storage;
    @Autowired org.flywaydb.core.Flyway flyway;

    private static final String PASSWORD = "enforcement-test-password";
    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired InspectionRepository inspections;
    @Autowired ViolationRepository violations;
    @Autowired EvidenceRepository evidence;

    private final Fixture fixture = new Fixture();

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID inspectorRole = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = 'INSPECTOR'", UUID.class);
        UUID citizenRole = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = 'CITIZEN'", UUID.class);
        insertUser(fixture.inspectorId, fixture.inspectorName, "inspector", now);
        insertUser(fixture.citizenId, fixture.citizenName, "citizen", now);
        insertMunicipalUser(fixture.otherInspectorId, fixture.otherInspectorName, "INSPECTOR", now);
        insertMunicipalUser(fixture.supervisorId, fixture.supervisorName, "SUPERVISOR", now);
        insertMunicipalUser(fixture.adminId, fixture.adminName, "SIMERTPI_ADMIN", now);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.inspectorId, inspectorRole);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.citizenId, citizenRole);
        jdbc.update("INSERT INTO identity.vehicles(id, user_id, plate, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                fixture.vehicleId, fixture.citizenId, fixture.plate, now, now);
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)", fixture.zoneId, fixture.tag, "Enforcement test zone");
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, ?)",
                fixture.streetId, fixture.zoneId, fixture.tag, "Enforcement test street");
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number) VALUES (?, ?, ?, ?, ?)",
                fixture.spaceId, fixture.streetId, fixture.tag, fixture.qrCode, "01");
        jdbc.update("INSERT INTO parking.parking_sessions(id, user_id, vehicle_id, parking_space_id, started_at, expected_end_at, status, total_amount, extension_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 0, 0, ?, ?)",
                fixture.sessionId, fixture.citizenId, fixture.vehicleId, fixture.spaceId,
                now.minusMinutes(5), now.plusMinutes(55), now, now);
        jdbc.update("INSERT INTO parking.parking_control_events(id, parking_session_id, user_id, vehicle_id, parking_space_id, event_type, occurred_at, source, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'GRACE_PERIOD', ?, 'SYSTEM', 'RECORDED', ?, ?)",
                UUID.randomUUID(), fixture.sessionId, fixture.citizenId, fixture.vehicleId, fixture.spaceId,
                now, now, now);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Violation violation : violations.findByInspectorId(fixture.inspectorId)) {
            for (Evidence item : evidence.findByViolationId(violation.getId())) storage.delete(item.getStorageKey());
        }
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?, ?, ?)", fixture.otherInspectorId, fixture.supervisorId, fixture.adminId);
        jdbc.update("DELETE FROM identity.users WHERE id IN (?, ?, ?)", fixture.otherInspectorId, fixture.supervisorId, fixture.adminId);
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id IN (?, ?)", fixture.inspectorId, fixture.citizenId);
        jdbc.update("DELETE FROM enforcement.evidence WHERE violation_id IN (SELECT id FROM enforcement.violations WHERE inspector_id = ?)", fixture.inspectorId);
        jdbc.update("DELETE FROM enforcement.violations WHERE inspector_id = ?", fixture.inspectorId);
        jdbc.update("DELETE FROM enforcement.inspections WHERE inspector_id = ?", fixture.inspectorId);
        jdbc.update("DELETE FROM parking.parking_control_events WHERE parking_session_id = ?", fixture.sessionId);
        jdbc.update("DELETE FROM parking.parking_sessions WHERE id = ?", fixture.sessionId);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE id = ?", fixture.spaceId);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", fixture.streetId);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", fixture.zoneId);
        jdbc.update("DELETE FROM identity.vehicles WHERE id = ?", fixture.vehicleId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?, ?)", fixture.inspectorId, fixture.citizenId);
        jdbc.update("DELETE FROM identity.users WHERE id IN (?, ?)", fixture.inspectorId, fixture.citizenId);
    }

    @Test
    void securesAndPersistsInspectionViolationEvidenceAndLookup() throws Exception {
        Map<String, Object> inspectionRequest = Map.of(
                "parkingSessionId", fixture.sessionId, "parkingSpaceId", fixture.spaceId,
                "vehicleId", fixture.vehicleId, "observedPlate", fixture.plate,
                "observedAt", OffsetDateTime.now().toString(), "result", "NO_PAYMENT",
                "notes", "Observed during inspection", "inspectorId", fixture.citizenId);

        ResponseEntity<String> anonymous = restTemplate.postForEntity(url("/api/v1/inspections"), inspectionRequest, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> citizen = exchange("/api/v1/inspections", HttpMethod.POST, fixture.citizenName, inspectionRequest, String.class);
        assertThat(citizen.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String inspectionKey = "INSPECT-" + fixture.tag;
        ResponseEntity<String> inspectionResponse = exchange("/api/v1/inspections", HttpMethod.POST,
                fixture.inspectorName, inspectionKey, inspectionRequest, String.class);
        assertThat(inspectionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode inspectionJson = objectMapper.readTree(inspectionResponse.getBody());
        assertThat(inspectionJson.has("inspectorId")).isFalse();
        UUID inspectionId = UUID.fromString(inspectionJson.get("id").asText());
        Inspection persistedInspection = inspections.findById(inspectionId).orElseThrow();
        assertThat(persistedInspection.getInspectorId()).isEqualTo(fixture.inspectorId);
        assertThat(persistedInspection.getParkingSessionId()).isEqualTo(fixture.sessionId);
        assertThat(persistedInspection.getVehicleId()).isEqualTo(fixture.vehicleId);
        assertThat(persistedInspection.getParkingSpaceId()).isEqualTo(fixture.spaceId);
        ResponseEntity<String> inspectionReplay = exchange("/api/v1/inspections", HttpMethod.POST,
                fixture.inspectorName, inspectionKey, inspectionRequest, String.class);
        assertThat(inspectionReplay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(inspectionReplay.getBody()).get("id").asText()).isEqualTo(inspectionId.toString());
        assertThat(inspections.findByInspectorId(fixture.inspectorId)).hasSize(1);
        Map<String, Object> changedInspectionRequest = new java.util.HashMap<>(inspectionRequest);
        changedInspectionRequest.put("notes", "Different request under same key");
        ResponseEntity<String> changedInspection = exchange("/api/v1/inspections", HttpMethod.POST,
                fixture.inspectorName, inspectionKey, changedInspectionRequest, String.class);
        assertThat(changedInspection.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inspections.findByInspectorId(fixture.inspectorId)).hasSize(1);

        Map<String, Object> violationRequest = Map.of("inspectionId", inspectionId,
                "violationType", "NO_PAYMENT", "description", "No valid payment observed",
                "occurredAt", OffsetDateTime.now().toString());
        ResponseEntity<String> citizenViolation = exchange("/api/v1/violations", HttpMethod.POST,
                fixture.citizenName, violationRequest, String.class);
        assertThat(citizenViolation.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String violationKey = "VIOLATION-" + fixture.tag;
        ResponseEntity<String> violationResponse = exchange("/api/v1/violations", HttpMethod.POST,
                fixture.inspectorName, violationKey, violationRequest, String.class);
        assertThat(violationResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode violationJson = objectMapper.readTree(violationResponse.getBody());
        assertThat(violationJson.has("inspectorId")).isFalse();
        UUID violationId = UUID.fromString(violationJson.get("id").asText());
        Violation persistedViolation = violations.findById(violationId).orElseThrow();
        assertThat(persistedViolation.getInspectionId()).isEqualTo(inspectionId);
        assertThat(persistedViolation.getInspectorId()).isEqualTo(fixture.inspectorId);
        assertThat(persistedViolation.getParkingSpaceId()).isEqualTo(fixture.spaceId);
        assertThat(persistedViolation.getVehicleId()).isEqualTo(fixture.vehicleId);
        assertThat(persistedViolation.getFineAmount()).isEqualByComparingTo("0.00");
        ResponseEntity<String> violationReplay = exchange("/api/v1/violations", HttpMethod.POST,
                fixture.inspectorName, violationKey, violationRequest, String.class);
        assertThat(violationReplay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(violationReplay.getBody()).get("id").asText()).isEqualTo(violationId.toString());
        Map<String, Object> changedViolationRequest = new java.util.HashMap<>(violationRequest);
        changedViolationRequest.put("description", "Different request under same key");
        ResponseEntity<String> changedViolation = exchange("/api/v1/violations", HttpMethod.POST,
                fixture.inspectorName, violationKey, changedViolationRequest, String.class);
        assertThat(changedViolation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> secondViolation = exchange("/api/v1/violations", HttpMethod.POST,
                fixture.inspectorName, "VIOLATION-SECOND-" + fixture.tag, violationRequest, String.class);
        assertThat(secondViolation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(violations.findByInspectionId(inspectionId)).hasSize(1);

        byte[] image = java.util.Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCwAA//2Q==");
        String expectedHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(image));
        ResponseEntity<String> citizenEvidence = uploadEvidence(fixture.citizenName, "EVIDENCE-CITIZEN-" + fixture.tag,
                violationId, image, "test-photo.jpg");
        assertThat(citizenEvidence.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String evidenceKey = "EVIDENCE-" + fixture.tag;
        ResponseEntity<String> evidenceResponse = uploadEvidence(fixture.inspectorName, evidenceKey,
                violationId, image, "test-photo.jpg");
        assertThat(evidenceResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID evidenceId = UUID.fromString(objectMapper.readTree(evidenceResponse.getBody()).get("id").asText());
        JsonNode evidenceJson = objectMapper.readTree(evidenceResponse.getBody());
        assertThat(evidenceJson.has("violation")).isFalse();
        assertThat(evidenceJson.has("storageKey")).isFalse();
        Evidence persistedEvidence = evidence.findById(evidenceId).orElseThrow();
        assertThat(persistedEvidence.getSha256Hash()).isEqualTo(expectedHash);
        assertThat(persistedEvidence.getStorageKey()).doesNotContain("client-controlled");
        assertThat(persistedEvidence.getViolationId()).isEqualTo(violationId);
        assertThat(persistedEvidence.getStorageKey()).startsWith("violations/" + violationId + "/");
        assertThat(persistedEvidence.getFileSize()).isEqualTo(image.length);
        assertThat(persistedEvidence.getStorageKey()).doesNotContain("test-photo");
        assertThat(evidence.findByViolationId(violationId)).hasSize(1);
        ResponseEntity<String> evidenceReplay = uploadEvidence(fixture.inspectorName, evidenceKey,
                violationId, image, "test-photo.jpg");
        assertThat(evidenceReplay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(evidenceReplay.getBody()).get("id").asText()).isEqualTo(evidenceId.toString());
        ResponseEntity<String> changedEvidence = uploadEvidence(fixture.inspectorName, evidenceKey,
                violationId, image, "different-name.jpg");
        assertThat(changedEvidence.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<byte[]> downloaded = restTemplate.exchange(url("/api/v1/evidence/" + evidenceId + "/content"),
                HttpMethod.GET, basicAuth(fixture.inspectorName), byte[].class);
        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getBody()).containsExactly(image);
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(downloaded.getBody()))).isEqualTo(persistedEvidence.getSha256Hash());
        assertThat(downloaded.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        ResponseEntity<String> citizenDownload = restTemplate.exchange(url("/api/v1/evidence/" + evidenceId + "/content"),
                HttpMethod.GET, basicAuth(fixture.citizenName), String.class);
        assertThat(citizenDownload.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders statusHeaders = new HttpHeaders();
        statusHeaders.setBasicAuth(fixture.inspectorName, PASSWORD);
        statusHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Violation> notified = restTemplate.exchange(url("/api/v1/violations/" + violationId + "/status"),
                HttpMethod.PATCH, new HttpEntity<>(Map.of("status", "NOTIFIED"), statusHeaders), Violation.class);
        assertThat(notified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notified.getBody().getNotifiedAt()).isNotNull();
        ResponseEntity<Violation> paid = restTemplate.exchange(url("/api/v1/violations/" + violationId + "/status"),
                HttpMethod.PATCH, new HttpEntity<>(Map.of("status", "PAID"), statusHeaders), Violation.class);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().getPaidAt()).isNotNull();
        ResponseEntity<String> invalidTransition = restTemplate.exchange(url("/api/v1/violations/" + violationId + "/status"),
                HttpMethod.PATCH, new HttpEntity<>(Map.of("status", "OPEN"), statusHeaders), String.class);
        assertThat(invalidTransition.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> duplicateEvidence = uploadEvidence(fixture.inspectorName,
                "EVIDENCE-SECOND-" + fixture.tag, violationId, image, "test-photo.jpg");
        assertThat(duplicateEvidence.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(duplicateEvidence.getBody()).get("id").asText()).isEqualTo(evidenceId.toString());
        assertThat(evidence.findByViolationId(violationId)).hasSize(1);

        ResponseEntity<String> lookup = exchange("/api/v1/inspections/lookup?qrCode=" + fixture.qrCode,
                HttpMethod.GET, fixture.inspectorName, null, String.class);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lookupBody = objectMapper.readTree(lookup.getBody());
        assertThat(lookupBody.get("sessionStatus").asText()).isEqualTo("ACTIVE");
        assertThat(lookupBody.get("controlEvents").get(0).get("eventType").asText()).isEqualTo("GRACE_PERIOD");
    }

    @Test
    void rejectsEvidenceWithInvalidHashAndCannotUseClientSuppliedInspectorId() {
        Map<String, Object> forgedRequest = Map.of("inspectorId", fixture.inspectorId,
                "parkingSpaceId", fixture.spaceId, "vehicleId", fixture.vehicleId,
                "observedPlate", fixture.plate, "observedAt", OffsetDateTime.now().toString(), "result", "NO_PAYMENT");
        ResponseEntity<String> citizen = exchange("/api/v1/inspections", HttpMethod.POST,
                fixture.citizenName, forgedRequest, String.class);
        assertThat(citizen.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inspections.findByInspectorId(fixture.inspectorId)).isEmpty();
    }

    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired ec.gob.simertpi.application.enforcement.EnforcementIdempotencyService idempotency;

    @Test
    void realDatabaseRollbackRemovesNewObjectAndMetadata() throws Exception {
        UUID violationId = createViolation();
        byte[] bytes = {(byte)255, (byte)216, (byte)255, 2};
        java.util.concurrent.atomic.AtomicReference<String> key = new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        fixture.inspectorName, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("INSPECTOR"))));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status -> {
                Evidence item = idempotency.createEvidenceUpload(fixture.inspectorName, "ROLLBACK-" + fixture.tag,
                        violationId, "file.jpg", "image/jpeg", bytes, null, null, null);
                key.set(item.getStorageKey());
                jdbc.execute("SELECT 1 / 0");
                return null;
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(key.get()).isNotNull();
        assertThat(storage.exists(key.get())).isFalse();
        assertThat(evidence.findByViolationId(violationId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.idempotency_keys WHERE user_id = ? AND operation_type = 'CREATE_EVIDENCE'", Long.class, fixture.inspectorId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'EVIDENCE_CREATED'", Long.class, fixture.inspectorId)).isZero();
    }

    @Test
    void concurrentUploadsWithDifferentKeysCreateOneObjectAndOneAudit() throws Exception {
        UUID violationId = createViolation();
        byte[] bytes = {(byte)255, (byte)216, (byte)255, 3};
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> uploadEvidence(fixture.inspectorName,
                "CONCURRENT-A-" + fixture.tag, violationId, bytes, "file.jpg"));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> uploadEvidence(fixture.inspectorName,
                "CONCURRENT-B-" + fixture.tag, violationId, bytes, "file.jpg"));
        var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
        var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(a.getBody()).get("id")).isEqualTo(objectMapper.readTree(b.getBody()).get("id"));
        assertThat(evidence.findByViolationId(violationId)).hasSize(1);
        try (var files = java.nio.file.Files.walk(STORAGE_DIRECTORY.resolve("violations/" + violationId))) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'EVIDENCE_CREATED' AND result = 'SUCCESS'", Long.class, fixture.inspectorId)).isEqualTo(1);
    }

    private UUID createViolation() throws Exception {
        var inspectionResponse = exchange("/api/v1/inspections", HttpMethod.POST, fixture.inspectorName,
                Map.of("parkingSpaceId", fixture.spaceId, "observedPlate", fixture.plate,
                        "observedAt", OffsetDateTime.now().toString(), "result", "NO_PAYMENT"), String.class);
        assertThat(inspectionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID inspectionId = UUID.fromString(objectMapper.readTree(inspectionResponse.getBody()).get("id").asText());
        var response = exchange("/api/v1/violations", HttpMethod.POST, fixture.inspectorName,
                Map.of("inspectionId", inspectionId, "violationType", "NO_PAYMENT", "occurredAt", OffsetDateTime.now().toString()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(response.getBody()).get("id").asText());
    }

    @Test
    void evidenceAuditCorrelationDeduplicationAndAccess() throws Exception {
        UUID violationId = createViolation();
        byte[] bytes = {(byte)255, (byte)216, (byte)255, 1};
        var response = uploadEvidence(fixture.inspectorName, "CP9-AUDIT-" + fixture.tag, violationId, bytes, "../../photo.jpg");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("cp9-" + fixture.tag);
        UUID id = UUID.fromString(objectMapper.readTree(response.getBody()).get("id").asText());
        assertThat(response.getBody()).doesNotContain("storageKey", STORAGE_DIRECTORY.toString(), "violations/");
        for (String key : java.util.List.of("CP9-AUDIT-" + fixture.tag, "CP9-OTHER-" + fixture.tag)) {
            var replay = uploadEvidence(fixture.inspectorName, key, violationId, bytes, "../../photo.jpg");
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(objectMapper.readTree(replay.getBody()).get("id").asText()).isEqualTo(id.toString());
        }
        assertThat(uploadEvidence(fixture.inspectorName, "CP9-AUDIT-" + fixture.tag, violationId,
                bytes, "photo.jpg").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action = 'EVIDENCE_CREATED' AND resource_id = ? AND result = 'SUCCESS' AND correlation_id = ?", Long.class, id, "cp9-" + fixture.tag)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit.functional_audit_log WHERE action = 'EVIDENCE_CREATED' AND resource_id = ?", UUID.class, id)).isEqualTo(fixture.inspectorId);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit.functional_audit_log WHERE action = 'EVIDENCE_CREATED' AND resource_id = ?", String.class, id)).doesNotContain("storageKey", "violations/", STORAGE_DIRECTORY.toString());
        try (var paths = java.nio.file.Files.walk(STORAGE_DIRECTORY.resolve("violations/" + violationId))) {
            assertThat(paths.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(1);
        }
        var otherUpload = uploadEvidence(fixture.otherInspectorName, "OTHER-" + fixture.tag, violationId, bytes, "photo.jpg");
        assertThat(otherUpload.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(uploadEvidence(null, "ANON-" + fixture.tag, violationId, bytes, "photo.jpg").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(url("/api/v1/evidence/" + id + "/content"), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange(url("/api/v1/evidence/" + id + "/content"), HttpMethod.GET, basicAuth(fixture.otherInspectorName), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (String name : java.util.List.of(fixture.inspectorName, fixture.supervisorName, fixture.adminName)) {
            var download = restTemplate.exchange(url("/api/v1/evidence/" + id + "/content"), HttpMethod.GET, basicAuth(name), byte[].class);
            assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(download.getBody()).containsExactly(bytes);
            assertThat(download.getHeaders().getFirst("X-Correlation-ID")).isNotBlank();
            assertThat(download.getHeaders().toString()).doesNotContain(STORAGE_DIRECTORY.toString(), "violations/");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action = 'EVIDENCE_DOWNLOADED' AND resource_id = ? AND result = 'SUCCESS' AND correlation_id = ?", Long.class, id, download.getHeaders().getFirst("X-Correlation-ID"))).isEqualTo(1);
        }
        assertThat(evidence.findByViolationId(violationId)).hasSize(1);
    }

    @Test
    void acceptsPngAndPdfAndRejectsInvalidMultipartFiles() throws Exception {
        UUID violationId = createViolation();
        byte[] png = {(byte)137, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(uploadEvidence(fixture.inspectorName, UUID.randomUUID().toString(), violationId, png, "f.png", MediaType.IMAGE_PNG).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadEvidence(fixture.inspectorName, UUID.randomUUID().toString(), violationId, pdf, "f.pdf", MediaType.APPLICATION_PDF).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (var response : java.util.List.of(
                uploadEvidence(fixture.inspectorName, UUID.randomUUID().toString(), violationId, new byte[0], "f.jpg"),
                uploadEvidence(fixture.inspectorName, UUID.randomUUID().toString(), violationId, pdf, "f.jpg"),
                uploadEvidence(fixture.inspectorName, UUID.randomUUID().toString(), violationId, pdf, "f.exe", MediaType.APPLICATION_PDF),
                uploadEvidence(fixture.inspectorName, UUID.randomUUID().toString(), violationId, pdf, "f.pdf", MediaType.TEXT_PLAIN))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).doesNotContain(STORAGE_DIRECTORY.toString(), "storageKey");
        }
        assertThat(evidence.findByViolationId(violationId)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action = 'EVIDENCE_UPLOAD_FAILED' AND actor_id = ? AND result = 'FAILURE'", Long.class, fixture.inspectorId)).isEqualTo(4);
        assertThat(flyway.info().current().getVersion()).isGreaterThanOrEqualTo(org.flywaydb.core.api.MigrationVersion.fromVersion("25"));
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, String username,
                                            Object body, Class<T> responseType) {
        return exchange(path, method, username, "REQUEST-" + UUID.randomUUID(), body, responseType);
    }

    private ResponseEntity<String> uploadEvidence(String username, String key, UUID violationId,
                                                   byte[] content, String filename) {
        return uploadEvidence(username, key, violationId, content, filename, MediaType.IMAGE_JPEG);
    }

    private ResponseEntity<String> uploadEvidence(String username, String key, UUID violationId,
                                                   byte[] content, String filename, MediaType mime) {
        HttpHeaders headers = new HttpHeaders();
        if (username != null) headers.setBasicAuth(username, PASSWORD);
        headers.set("Idempotency-Key", key);
        headers.set("X-Correlation-ID", "cp9-" + fixture.tag);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mime);
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("violationId", violationId.toString());
        body.add("storageKey", "../../client-controlled.jpg");
        body.add("sha256Hash", "a".repeat(64));
        body.add("inspectorId", fixture.citizenId.toString());
        body.add("fileSize", "999999");
        body.add("file", new HttpEntity<>(resource, fileHeaders));
        return restTemplate.exchange(url("/api/v1/evidence"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private HttpEntity<Void> basicAuth(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, PASSWORD);
        return new HttpEntity<>(headers);
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, String username,
                                            String key, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, PASSWORD);
        headers.set("Idempotency-Key", key);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), responseType);
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private void insertMunicipalUser(UUID id, String name, String role, OffsetDateTime now) {
        insertUser(id, name, role, now);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) SELECT ?, id FROM identity.roles WHERE code = ?", id, role);
    }

    private void insertUser(UUID id, String username, String role, OffsetDateTime now) {
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                id, username, username + "@example.test", passwordEncoder.encode(PASSWORD), "Test", role, now, now);
    }

    private static final class Fixture {
        final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final UUID inspectorId = UUID.randomUUID();
        final UUID citizenId = UUID.randomUUID();
        final UUID otherInspectorId = UUID.randomUUID();
        final UUID supervisorId = UUID.randomUUID();
        final UUID adminId = UUID.randomUUID();
        final String otherInspectorName = "other-inspector-" + tag;
        final String supervisorName = "supervisor-" + tag;
        final String adminName = "admin-" + tag;
        final UUID vehicleId = UUID.randomUUID();
        final UUID zoneId = UUID.randomUUID();
        final UUID streetId = UUID.randomUUID();
        final UUID spaceId = UUID.randomUUID();
        final UUID sessionId = UUID.randomUUID();
        final String inspectorName = "inspector-" + tag;
        final String citizenName = "citizen-" + tag;
        final String plate = "E" + tag.substring(0, 9).toUpperCase();
        final String qrCode = "QR-ENF-" + tag;
    }
}
