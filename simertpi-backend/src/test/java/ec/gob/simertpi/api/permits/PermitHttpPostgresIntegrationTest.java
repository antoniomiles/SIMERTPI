package ec.gob.simertpi.api.permits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.domain.permits.entity.Permit;
import ec.gob.simertpi.infrastructure.permits.JpaPermitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermitHttpPostgresIntegrationTest {
    private static final String PASSWORD = "permit-checkpoint-test";
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired JpaPermitRepository permits;

    private final Fixture fixture = new Fixture();

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID adminRole = role("SIMERTPI_ADMIN");
        UUID citizenRole = role("CITIZEN");
        UUID inspectorRole = role("INSPECTOR");
        insertUser(fixture.adminId, fixture.adminUsername, now);
        insertUser(fixture.ownerId, fixture.ownerUsername, now);
        insertUser(fixture.otherId, fixture.otherUsername, now);
        insertUser(fixture.inspectorId, fixture.inspectorUsername, now);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.adminId, adminRole);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.ownerId, citizenRole);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.otherId, citizenRole);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.inspectorId, inspectorRole);
        jdbc.update("INSERT INTO identity.vehicles(id, user_id, plate, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                fixture.vehicleId, fixture.ownerId, fixture.plate, now, now);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_id = ?", fixture.permitId);
        jdbc.update("DELETE FROM permits.permits WHERE id = ?", fixture.permitId);
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id = ?", fixture.adminId);
        jdbc.update("DELETE FROM identity.vehicles WHERE id = ?", fixture.vehicleId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?, ?, ?, ?)", fixture.adminId, fixture.ownerId, fixture.otherId, fixture.inspectorId);
        jdbc.update("DELETE FROM identity.users WHERE id IN (?, ?, ?, ?)", fixture.adminId, fixture.ownerId, fixture.otherId, fixture.inspectorId);
    }

    @Test
    void authenticatesAdminCreatesAndReplaysPermitAndEnforcesCitizenOwnership() throws Exception {
        Map<String, Object> body = Map.of(
                "userId", fixture.ownerId,
                "vehicleId", fixture.vehicleId,
                "permitType", "RESIDENT",
                "validFrom", OffsetDateTime.now().minusMinutes(1).toString(),
                "validTo", OffsetDateTime.now().plusDays(2).toString(),
                "authorizationCode", fixture.authorizationCode,
                "notes", "Integration fixture");

        ResponseEntity<String> anonymous = http.postForEntity(url("/api/v1/permits"), body, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> citizenCreate = exchange("/api/v1/permits", HttpMethod.POST,
                fixture.ownerUsername, "KEY-CITIZEN", body);
        assertThat(citizenCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> invalidBody = Map.of(
                "userId", fixture.ownerId,
                "vehicleId", fixture.vehicleId,
                "permitType", "RESIDENT",
                "validFrom", OffsetDateTime.now().plusDays(2).toString(),
                "validTo", OffsetDateTime.now().plusDays(1).toString(),
                "authorizationCode", fixture.authorizationCode,
                "notes", "Invalid audit fixture");
        ResponseEntity<String> rejected = exchange("/api/v1/permits", HttpMethod.POST,
                fixture.adminUsername, fixture.idempotencyKey, invalidBody);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'PERMIT_CREATED' AND result = 'FAILURE'",
                Integer.class, fixture.adminId)).isEqualTo(1);

        ResponseEntity<String> created = exchange("/api/v1/permits", HttpMethod.POST,
                fixture.adminUsername, fixture.idempotencyKey, body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode response = mapper.readTree(created.getBody());
        fixture.permitId = UUID.fromString(response.get("id").asText());
        assertThat(response.get("status").asText()).isEqualTo("ACTIVE");
        Permit persisted = permits.findById(fixture.permitId).orElseThrow();
        assertThat(persisted.getUserId()).isEqualTo(fixture.ownerId);
        assertThat(persisted.getVehicleId()).isEqualTo(fixture.vehicleId);
        assertThat(persisted.getAuthorizationCode()).isEqualTo(fixture.authorizationCode);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.outbox_events WHERE aggregate_id = ? AND event_type = 'PERMIT_CREATED'",
                Integer.class, fixture.permitId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'PERMIT_CREATED' AND resource_id = ? AND result = 'SUCCESS' AND correlation_id = ?",
                Integer.class, fixture.adminId, fixture.permitId, fixture.correlationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'PERMIT_CREATED' AND resource_id = ?",
                String.class, fixture.adminId, fixture.permitId)).isEqualTo("{}");
        ResponseEntity<String> citizenAudit = exchange("/api/v1/audit", HttpMethod.GET,
                fixture.ownerUsername, null, null);
        assertThat(citizenAudit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> inspectorAudit = exchange("/api/v1/audit", HttpMethod.GET,
                fixture.inspectorUsername, null, null);
        assertThat(inspectorAudit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> adminAudit = exchange("/api/v1/audit?resourceType=PERMIT&resourceId=" + fixture.permitId,
                HttpMethod.GET, fixture.adminUsername, null, null);
        assertThat(adminAudit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminAudit.getBody()).contains(fixture.permitId.toString(), "PERMIT_CREATED");
        UUID auditId = jdbc.queryForObject("SELECT id FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'PERMIT_CREATED' AND resource_id = ? AND result = 'SUCCESS'",
                UUID.class, fixture.adminId, fixture.permitId);
        assertThat(exchange("/api/v1/audit/" + auditId, HttpMethod.DELETE,
                fixture.adminUsername, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit.functional_audit_log SET action = 'TAMPERED' WHERE id = ?", auditId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT action FROM audit.functional_audit_log WHERE id = ?",
                String.class, auditId)).isEqualTo("PERMIT_CREATED");

        ResponseEntity<String> replay = exchange("/api/v1/permits", HttpMethod.POST,
                fixture.adminUsername, fixture.idempotencyKey, body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(replay.getBody()).get("id").asText()).isEqualTo(fixture.permitId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permits.permits WHERE authorization_code = ?",
                Integer.class, fixture.authorizationCode)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id = ? AND action = 'PERMIT_CREATED' AND resource_id = ?",
                Integer.class, fixture.adminId, fixture.permitId)).isEqualTo(1);

        ResponseEntity<String> ownerRead = exchange("/api/v1/permits/" + fixture.permitId,
                HttpMethod.GET, fixture.ownerUsername, null, null);
        assertThat(ownerRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> otherRead = exchange("/api/v1/permits/" + fixture.permitId,
                HttpMethod.GET, fixture.otherUsername, null, null);
        assertThat(otherRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> ownList = exchange("/api/v1/permits/mine", HttpMethod.GET,
                fixture.ownerUsername, null, null);
        assertThat(ownList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(ownList.getBody())).hasSize(1);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String username, String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, PASSWORD);
        if (key != null) headers.set("Idempotency-Key", key);
        headers.set("X-Correlation-ID", fixture.correlationId);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }
    private String url(String path) { return "http://localhost:" + port + path; }
    private UUID role(String code) {
        return jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = ?", UUID.class, code);
    }
    private void insertUser(UUID id, String username, OffsetDateTime now) {
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                id, username, username + "@test.invalid", passwordEncoder.encode(PASSWORD), "Permit", username, now, now);
    }

    private static final class Fixture {
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final UUID adminId = UUID.randomUUID();
        final UUID ownerId = UUID.randomUUID();
        final UUID otherId = UUID.randomUUID();
        final UUID inspectorId = UUID.randomUUID();
        final UUID vehicleId = UUID.randomUUID();
        final String adminUsername = "permit-admin-" + suffix;
        final String ownerUsername = "permit-owner-" + suffix;
        final String otherUsername = "permit-other-" + suffix;
        final String inspectorUsername = "permit-inspector-" + suffix;
        final String plate = "P" + suffix.substring(0, 9).toUpperCase();
        final String authorizationCode = "PERMIT-" + suffix;
        final String idempotencyKey = "PERMIT-KEY-" + suffix;
        final String correlationId = "permit-test-" + suffix;
        UUID permitId;
    }
}
