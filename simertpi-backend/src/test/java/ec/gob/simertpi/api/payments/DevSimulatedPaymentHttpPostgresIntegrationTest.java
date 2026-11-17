package ec.gob.simertpi.api.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("dev")
class DevSimulatedPaymentHttpPostgresIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {


    private static final String PASSWORD = "payment-flow-test";

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ParkingSessionRepository sessionRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID streetId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID tariffId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private String username;
    private String otherUsername;

    @BeforeEach
    void setUp() {
        username = "payment-citizen-" + tag;
        otherUsername = "payment-other-" + tag;
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                userId, username, username + "@example.test", passwordEncoder.encode(PASSWORD),
                "Payment", "Test", now, now);
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                otherUserId, otherUsername, otherUsername + "@example.test", passwordEncoder.encode(PASSWORD),
                "Other", "Citizen", now, now);
        UUID roleId = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = 'CITIZEN'", UUID.class);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", userId, roleId);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", otherUserId, roleId);
        jdbc.update("INSERT INTO identity.vehicles(id, user_id, plate, active, created_at, updated_at) VALUES (?, ?, ?, true, ?, ?)",
                vehicleId, userId, "P" + tag.substring(0, 7), now, now);
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)", zoneId, "PZ-" + tag, "Payment zone");
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, ?)", streetId, zoneId, "PS-" + tag, "Payment street");
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number) VALUES (?, ?, ?, ?, ?)",
                spaceId, streetId, "PC-" + tag, "PQR-" + tag, tag.substring(0, 4));
        jdbc.update("INSERT INTO parking.tariffs(id, code, name, amount, duration_minutes, min_minutes, active, valid_from, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?, ?)",
                tariffId, "PT-" + tag, "Payment tariff", new BigDecimal("1.25"), 60, 30,
                now.minusDays(1), now, now);
        jdbc.update("INSERT INTO parking.parking_sessions(id, user_id, vehicle_id, parking_space_id, tariff_id, started_at, expected_end_at, status, total_amount, extension_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', 0, 0, ?, ?)",
                sessionId, userId, vehicleId, spaceId, tariffId, now.minusMinutes(1), now.plusMinutes(59), now, now);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payments.webhooks WHERE provider_event_id LIKE ?",tag+"%");
        jdbc.update("DELETE FROM audit.reconciliation_findings WHERE resource_id=? OR resource_id IN (SELECT id FROM payments.payments WHERE parking_session_id=?)",sessionId,sessionId);
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_type = 'PAYMENT' AND aggregate_id IN (SELECT id FROM payments.payments WHERE parking_session_id = ?)", sessionId);
        for(UUID id:List.of(sessionId)) {
            jdbc.update("DELETE FROM audit.reconciliation_findings WHERE resource_id=?",id);
            jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_type='PAYMENT' AND aggregate_id IN (SELECT id FROM payments.payments WHERE parking_session_id=?)",id);
            jdbc.update("DELETE FROM payments.payment_attempts WHERE payment_id IN (SELECT id FROM payments.payments WHERE parking_session_id=?)",id);
            jdbc.update("DELETE FROM payments.payments WHERE parking_session_id=?",id);
            jdbc.update("DELETE FROM parking.parking_sessions WHERE id=?",id);
        }
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id = ?", otherUserId);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE id = ?", spaceId);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", streetId);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", zoneId);
        jdbc.update("DELETE FROM parking.tariffs WHERE id = ?", tariffId);
        jdbc.update("DELETE FROM identity.vehicles WHERE id = ?", vehicleId);
        jdbc.update("DELETE FROM identity.mobile_refresh_tokens WHERE session_id IN (SELECT id FROM identity.mobile_sessions WHERE user_id IN (?, ?))", userId, otherUserId);
        jdbc.update("DELETE FROM identity.mobile_sessions WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", userId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", otherUserId);
    }


    /** Uses the real DEV simulator and HTTP bearer authentication; never approves from test code. */
    @Test
    void devCheckoutPersistsApprovalAndActiveSessionAndEnforcesOwnershipOnReplay() throws Exception {
        var login = restTemplate.postForEntity("http://localhost:" + port + "/api/v1/auth/login",
                java.util.Map.of("username", username, "password", PASSWORD), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        var authHeaders = new HttpHeaders();
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
        authHeaders.set("Idempotency-Key", "dev-approved-checkout");
        authHeaders.setBearerAuth(objectMapper.readTree(login.getBody()).get("accessToken").asText());
        authHeaders.set("X-Correlation-ID", "cp22-mobile-payment");
        String body = "{\"parkingSessionId\":\"" + sessionId + "\",\"paymentMethod\":\"TEST\"}";
        var created = restTemplate.postForEntity("http://localhost:" + port + "/api/v1/payments",
                new HttpEntity<>(body, authHeaders), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("cp22-mobile-payment");
        var response = objectMapper.readTree(created.getBody());
        String paymentId = response.get("id").asText();
        assertThat(response.get("amount").decimalValue()).isEqualByComparingTo("1.25");
        assertThat(response.get("status").asText()).isEqualTo("APPROVED");
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(paymentRepository.findById(UUID.fromString(paymentId)).orElseThrow().getStatus()).isEqualTo("APPROVED");
        var replay = restTemplate.postForEntity("http://localhost:" + port + "/api/v1/payments",
                new HttpEntity<>(body, authHeaders), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(replay.getBody()).get("id").asText()).isEqualTo(paymentId);
        assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payment_attempts WHERE payment_id=?", Integer.class, UUID.fromString(paymentId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_sessions WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
        var read = restTemplate.exchange("http://localhost:" + port + "/api/v1/payments/" + paymentId,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        var otherLogin = restTemplate.postForEntity("http://localhost:" + port + "/api/v1/auth/login",
                java.util.Map.of("username", otherUsername, "password", PASSWORD), String.class);
        assertThat(otherLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        var otherHeaders = new HttpHeaders();
        otherHeaders.setBearerAuth(objectMapper.readTree(otherLogin.getBody()).get("accessToken").asText());
        assertThat(restTemplate.exchange("http://localhost:" + port + "/api/v1/payments/" + paymentId,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(otherHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
