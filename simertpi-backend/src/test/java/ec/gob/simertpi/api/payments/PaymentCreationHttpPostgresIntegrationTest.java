package ec.gob.simertpi.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.payments.PaymentService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCreationHttpPostgresIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "payment-flow-test";

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ParkingSessionRepository sessionRepository;
    @Autowired PaymentService paymentService;

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
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_type = 'PAYMENT' AND aggregate_id IN (SELECT id FROM payments.payments WHERE parking_session_id = ?)", sessionId);
        jdbc.update("DELETE FROM payments.payment_attempts WHERE payment_id IN (SELECT id FROM payments.payments WHERE parking_session_id = ?)", sessionId);
        jdbc.update("DELETE FROM payments.payments WHERE parking_session_id = ?", sessionId);
        jdbc.update("DELETE FROM parking.parking_sessions WHERE id = ?", sessionId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id = ?", otherUserId);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE id = ?", spaceId);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", streetId);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", zoneId);
        jdbc.update("DELETE FROM parking.tariffs WHERE id = ?", tariffId);
        jdbc.update("DELETE FROM identity.vehicles WHERE id = ?", vehicleId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", userId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", otherUserId);
    }

    @Test
    void createsOnePaymentForOwnerAndReplaysTheSameIdempotentOperation() throws Exception {
        ResponseEntity<String> first = post("PAY-KEY-001", sessionId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode firstBody = objectMapper.readTree(first.getBody());
        UUID paymentId = UUID.fromString(firstBody.get("id").asText());
        assertThat(firstBody.get("amount").decimalValue()).isEqualByComparingTo("1.25");
        assertThat(firstBody.get("status").asText()).isEqualTo("PENDING");

        ResponseEntity<String> replay = post("PAY-KEY-001", sessionId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(replay.getBody()).get("id").asText()).isEqualTo(paymentId.toString());
        assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payment_attempts WHERE payment_id = ?", Integer.class, paymentId)).isEqualTo(1);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");

        paymentService.fail(paymentId, "test provider failure");
        ResponseEntity<String> retry = post("PAY-KEY-002", sessionId);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(retry.getBody()).get("id").asText()).isEqualTo(paymentId.toString());
        assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payment_attempts WHERE payment_id = ?", Integer.class, paymentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.outbox_events WHERE aggregate_id = ? AND aggregate_type = 'PAYMENT'", Integer.class, paymentId)).isEqualTo(3);

        paymentService.approve(paymentId, "TEST-ONLY-TRANSACTION");
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.outbox_events WHERE aggregate_id = ? AND aggregate_type = 'PAYMENT'", Integer.class, paymentId)).isEqualTo(4);

        ResponseEntity<String> differentRequest = post("PAY-KEY-001", UUID.randomUUID());
        assertThat(differentRequest.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);
    }

    @Test
    void rejectsUnauthenticatedOrNonCitizenPaymentRequests() {
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unauthenticated = restTemplate.postForEntity(url(),
                new HttpEntity<>(body(sessionId), anonymous), String.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders inspector = new HttpHeaders();
        inspector.setBasicAuth(username, "wrong-password");
        inspector.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> badCredentials = restTemplate.postForEntity(url(),
                new HttpEntity<>(body(sessionId), inspector), String.class);
        assertThat(badCredentials.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlySessionOwnerCanCreatePaymentAndWebhookEndpointRemainsClosed() {
        ResponseEntity<String> createdByOwner = post("PAY-SHARED-USER-SCOPE", sessionId);
        assertThat(createdByOwner.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> otherUser = post(otherUsername, "PAY-SHARED-USER-SCOPE", sessionId);
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> webhook = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/payments/webhooks/unconfigured",
                new HttpEntity<>("{}", headers), String.class);
        assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void concurrentDifferentKeysCannotCreateTwoPaymentsForOneSession() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = executor.submit(() -> {
                gate.await();
                return post("PAY-RACE-A", sessionId);
            });
            Future<ResponseEntity<String>> second = executor.submit(() -> {
                gate.await();
                return post("PAY-RACE-B", sessionId);
            });
            gate.countDown();
            ResponseEntity<String> resultA = first.get();
            ResponseEntity<String> resultB = second.get();
            assertThat(List.of(resultA.getStatusCode(), resultB.getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyReturnOnePayment() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = executor.submit(() -> {
                gate.await();
                return post("PAY-SAME-KEY-RACE", sessionId);
            });
            Future<ResponseEntity<String>> second = executor.submit(() -> {
                gate.await();
                return post("PAY-SAME-KEY-RACE", sessionId);
            });
            gate.countDown();
            ResponseEntity<String> resultA = first.get();
            ResponseEntity<String> resultB = second.get();
            assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(objectMapper.readTree(resultA.getBody()).get("id").asText())
                    .isEqualTo(objectMapper.readTree(resultB.getBody()).get("id").asText());
            assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<String> post(String key, UUID requestedSessionId) {
        return post(username, key, requestedSessionId);
    }

    private ResponseEntity<String> post(String requestedUsername, String key, UUID requestedSessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(requestedUsername, PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return restTemplate.postForEntity(url(), new HttpEntity<>(body(requestedSessionId), headers), String.class);
    }

    private String body(UUID requestedSessionId) {
        return "{\"parkingSessionId\":\"" + requestedSessionId + "\",\"paymentMethod\":\"CARD\"}";
    }

    private String url() { return "http://localhost:" + port + "/api/v1/payments"; }
}
