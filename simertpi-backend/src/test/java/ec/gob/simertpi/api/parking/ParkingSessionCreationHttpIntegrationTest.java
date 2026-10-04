package ec.gob.simertpi.api.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.application.parking.ParkingSessionService;
import ec.gob.simertpi.application.parking.control.ParkingControlEvaluationService;
import ec.gob.simertpi.application.parking.control.ParkingControlScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParkingSessionCreationHttpIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {

    // Existing services use the wall clock. Keep valid-session scenarios away from
    // midnight using the configurable operational timezone, without changing instants
    // or mocking the rules engine. This offset stays within [-11, +12] hours.
    private static final java.time.ZoneOffset TEST_TIME_ZONE = java.time.ZoneOffset.ofHours(
            12 - java.time.Instant.now().atOffset(java.time.ZoneOffset.UTC).getHour());

    @org.springframework.test.context.DynamicPropertySource
    static void operationalTestTimeZone(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("simertpi.parking.rules.time-zone", TEST_TIME_ZONE::getId);
    }

    @LocalServerPort
    int port;

    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ParkingSessionRepository sessionRepository;
    @Autowired ParkingControlEvaluationService controlEvaluationService;
    @Autowired ParkingControlScheduler controlScheduler;
    @Autowired ParkingSessionService parkingSessionService;
    @Autowired ObjectMapper objectMapper;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        UUID citizenRoleId = jdbc.queryForObject(
                "SELECT id FROM identity.roles WHERE code = 'CITIZEN'", UUID.class);
        insertUser(fixture.userA, "citizen-a-" + fixture.tag, "citizen-a-" + fixture.tag + "@example.test", now);
        insertUser(fixture.userB, "citizen-b-" + fixture.tag, "citizen-b-" + fixture.tag + "@example.test", now);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.userA, citizenRoleId);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.userB, citizenRoleId);
        insertVehicle(fixture.vehicleA, fixture.userA, "A" + fixture.tag.substring(0, 7), now);
        insertVehicle(fixture.vehicleA2, fixture.userA, "B" + fixture.tag.substring(0, 7), now);
        insertVehicle(fixture.vehicleB, fixture.userB, "C" + fixture.tag.substring(0, 7), now);

        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)",
                fixture.zone, "ZT-" + fixture.tag, "Integration zone");
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, ?)",
                fixture.street, fixture.zone, "ST-" + fixture.tag, "Integration street");
        LocalDate today = LocalDate.now(TEST_TIME_ZONE);
        jdbc.update("INSERT INTO parking.schedules(id, zone_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), fixture.zone, (short) today.getDayOfWeek().getValue(),
                LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59));
        jdbc.update("INSERT INTO parking.tariffs(id, code, name, amount, duration_minutes, min_minutes, max_continuous_minutes, valid_from, zone_id, currency, rounding_mode, grace_period_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                fixture.tariff, "TR-" + fixture.tag, "Integration tariff", new java.math.BigDecimal("1.00"), 60, 30, 240, now.minusDays(1), fixture.zone, "USD", "HALF_UP", 10);
        createSpace(fixture.spaceA, "01");
        createSpace(fixture.spaceB, "02");
        createSpace(fixture.spaceC, "03");
        createSpace(fixture.spaceD, "04");
        createSpace(fixture.spaceE, "05");
    }

    @AfterEach
    void cleanUp() {
        if (fixture == null) return;
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id IN (?, ?)", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM notification.notifications WHERE user_id IN (?, ?)", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM parking.parking_control_events WHERE user_id IN (?, ?)", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM configuration.notification_rules WHERE code = ?", "LIFECYCLE-" + fixture.tag);
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_type = 'PAYMENT' AND aggregate_id IN (SELECT p.id FROM payments.payments p JOIN parking.parking_sessions s ON s.id = p.parking_session_id WHERE s.user_id IN (?, ?))", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM payments.payment_attempts WHERE payment_id IN (SELECT p.id FROM payments.payments p JOIN parking.parking_sessions s ON s.id = p.parking_session_id WHERE s.user_id IN (?, ?))", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM parking.session_extensions WHERE parking_session_id IN (SELECT id FROM parking.parking_sessions WHERE user_id IN (?, ?))", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM payments.payments WHERE parking_session_id IN (SELECT id FROM parking.parking_sessions WHERE user_id IN (?, ?))", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM parking.parking_sessions WHERE user_id IN (?, ?)", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM parking.schedules WHERE zone_id = ?", fixture.zone);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE street_id = ?", fixture.street);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", fixture.street);
        jdbc.update("DELETE FROM parking.tariffs WHERE id = ?", fixture.tariff);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", fixture.zone);
        jdbc.update("DELETE FROM identity.vehicles WHERE user_id IN (?, ?)", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?, ?)", fixture.userA, fixture.userB);
        jdbc.update("DELETE FROM identity.users WHERE id IN (?, ?)", fixture.userA, fixture.userB);
    }

    @Test
    void createsPersistsReplaysAndSerializesConcurrentRequestsAgainstRealPostgres() throws Exception {
        String userA = "citizen-a-" + fixture.tag;
        String userB = "citizen-b-" + fixture.tag;

        ResponseEntity<String> first = post(userA, "KEY-001", fixture.spaceA, fixture.vehicleA);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode firstBody = objectMapper.readTree(first.getBody());
        UUID sessionId = UUID.fromString(firstBody.get("id").asText());
        ParkingSession persisted = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(persisted.getUserId()).isEqualTo(fixture.userA);
        assertThat(persisted.getVehicleId()).isEqualTo(fixture.vehicleA);
        assertThat(persisted.getParkingSpaceId()).isEqualTo(fixture.spaceA);
        assertThat(persisted.getTariffId()).isEqualTo(fixture.tariff);
        assertThat(persisted.getStartedAt()).isNotNull();
        assertThat(persisted.getExpectedEndAt()).isAfter(persisted.getStartedAt());
        assertThat(persisted.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(persisted.getTotalAmount()).isEqualByComparingTo("0.00");

        ResponseEntity<String> replay = post(userA, "KEY-001", fixture.spaceA, fixture.vehicleA);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(replay.getBody()).get("id").asText()).isEqualTo(sessionId.toString());
        assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isEqualTo(1);

        ResponseEntity<String> differentPayload = post(userA, "KEY-001", fixture.spaceA, fixture.vehicleA2);
        assertThat(differentPayload.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isEqualTo(1);

        ResponseEntity<String> occupied = post(userB, "KEY-OCCUPIED", fixture.spaceA, fixture.vehicleB);
        assertThat(occupied.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isEqualTo(1);

        ResponseEntity<String> scopedA = post(userA, "KEY-USER-SCOPE", fixture.spaceD, fixture.vehicleA);
        ResponseEntity<String> scopedB = post(userB, "KEY-USER-SCOPE", fixture.spaceE, fixture.vehicleB);
        assertThat(scopedA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(scopedB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(scopedA.getBody()).get("id").asText())
                .isNotEqualTo(objectMapper.readTree(scopedB.getBody()).get("id").asText());

        CountDownLatch sameKeyGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> sameKeyOne = executor.submit(() -> {
                sameKeyGate.await();
                return post(userA, "KEY-CONCURRENT-SAME", fixture.spaceB, fixture.vehicleA);
            });
            Future<ResponseEntity<String>> sameKeyTwo = executor.submit(() -> {
                sameKeyGate.await();
                return post(userA, "KEY-CONCURRENT-SAME", fixture.spaceB, fixture.vehicleA);
            });
            sameKeyGate.countDown();
            ResponseEntity<String> sameOne = sameKeyOne.get();
            ResponseEntity<String> sameTwo = sameKeyTwo.get();
            assertThat(sameOne.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(sameTwo.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(objectMapper.readTree(sameOne.getBody()).get("id").asText())
                    .isEqualTo(objectMapper.readTree(sameTwo.getBody()).get("id").asText());
            assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceB)).isEqualTo(1);

            CountDownLatch spaceGate = new CountDownLatch(1);
            Future<ResponseEntity<String>> spaceOne = executor.submit(() -> {
                spaceGate.await();
                return post(userA, "KEY-CONCURRENT-A", fixture.spaceC, fixture.vehicleA);
            });
            Future<ResponseEntity<String>> spaceTwo = executor.submit(() -> {
                spaceGate.await();
                return post(userB, "KEY-CONCURRENT-B", fixture.spaceC, fixture.vehicleB);
            });
            spaceGate.countDown();
            ResponseEntity<String> resultOne = spaceOne.get();
            ResponseEntity<String> resultTwo = spaceTwo.get();
            assertThat(java.util.List.of(resultOne.getStatusCode(), resultTwo.getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            ResponseEntity<String> conflictResponse = resultOne.getStatusCode() == HttpStatus.CONFLICT
                    ? resultOne : resultTwo;
            assertThat(conflictResponse.getBody())
                    .doesNotContain("uk_sessions_occupied_space", "parking_space_id", "SQLState");
            assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceC)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiresOnceRecordsConfiguredNotificationAndReleasesSpaceOnCompletion() throws Exception {
        ResponseEntity<String> created = post("citizen-a-" + fixture.tag, "LIFECYCLE-1",
                fixture.spaceA, fixture.vehicleA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sessionId = UUID.fromString(objectMapper.readTree(created.getBody()).get("id").asText());

        OffsetDateTime end = OffsetDateTime.now().minusMinutes(20).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE parking.parking_sessions SET status = 'ACTIVE', started_at = ?, expected_end_at = ? WHERE id = ?",
                end.minusMinutes(60), end, sessionId);
        OffsetDateTime ruleStart = end.minusDays(1);
        jdbc.update("INSERT INTO configuration.notification_rules(id, code, event_type, channel, minutes_before, enabled, title_template, message_template, valid_from, created_at, updated_at) VALUES (?, ?, 'EXPIRATION', 'PUSH', 0, true, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), "LIFECYCLE-" + fixture.tag, "Sesión expirada", "El tiempo contratado terminó",
                ruleStart, ruleStart, ruleStart);

        OffsetDateTime evaluationTime = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        controlScheduler.evaluateParkingControls();
        controlEvaluationService.evaluate(sessionId, evaluationTime.plusMinutes(1));

        ParkingSession expired = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo("EXPIRED");
        assertThat(expired.getEndedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_control_events WHERE parking_session_id = ? AND event_type = 'EXPIRATION'",
                Integer.class, sessionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_control_events WHERE parking_session_id = ? AND event_type = 'EXCESS_11_30'",
                Integer.class, sessionId)).isEqualTo(1);
        Integer activeExpirationRules = jdbc.queryForObject("SELECT count(*) FROM configuration.notification_rules WHERE event_type = 'EXPIRATION' AND enabled = true AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)",
                Integer.class, evaluationTime.plusMinutes(1), evaluationTime.plusMinutes(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id = ? AND notification_type = 'EXPIRATION'",
                Integer.class, fixture.userA)).isEqualTo(activeExpirationRules);

        ResponseEntity<String> whileOccupied = post("citizen-b-" + fixture.tag, "LIFECYCLE-2",
                fixture.spaceA, fixture.vehicleB);
        assertThat(whileOccupied.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isEqualTo(1);

        ParkingSession completed = parkingSessionService.close(sessionId);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getEndedAt()).isNotNull();
        ResponseEntity<String> afterRelease = post("citizen-a-" + fixture.tag, "LIFECYCLE-3",
                fixture.spaceA, fixture.vehicleA2);
        assertThat(afterRelease.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isEqualTo(2);
    }

    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired ec.gob.simertpi.application.parking.extension.ParkingSessionExtensionService extensionService;
    @Autowired ec.gob.simertpi.application.payments.PaymentService paymentService;

    private ResponseEntity<String> queryRules(String selector) {
        HttpHeaders headers = new HttpHeaders(); headers.setBasicAuth("citizen-a-" + fixture.tag, "integration-password");
        return restTemplate.exchange("http://localhost:" + port + "/api/v1/parking/rules?" + selector,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
    @Test
    void citizenQueriesRulesBySpaceAndQrWithoutAuditNoise() throws Exception {
        Long before = jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log", Long.class);
        var bySpace = queryRules("spaceId=" + fixture.spaceA + "&durationMinutes=31");
        var byQr = queryRules("qrCode=QR-" + fixture.tag + "-01&durationMinutes=31");
        assertThat(bySpace.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(byQr.getStatusCode()).isEqualTo(HttpStatus.OK);
        var a = objectMapper.readTree(bySpace.getBody()); var b = objectMapper.readTree(byQr.getBody());
        assertThat(a.get("reasonCode").asText()).isEqualTo("RULES_RESOLVED");
        assertThat(a.get("calculatedAmount").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(a.get("billedDurationMinutes").asInt()).isEqualTo(60);
        assertThat(a.get("maximumContinuousMinutes").asInt()).isEqualTo(240);
        assertThat(a.get("gracePeriodMinutes").asInt()).isEqualTo(10);
        assertThat(a.get("spaceId")).isEqualTo(b.get("spaceId"));
        assertThat(bySpace.getBody()).doesNotContain("tariffId", "createdAt", "normativeReference");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log", Long.class)).isEqualTo(before);
        assertThat(Integer.parseInt(flyway.info().current().getVersion().toString())).isGreaterThanOrEqualTo(26);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
    @Test
    void anonymousCannotQueryRulesAndSelectorsAreValidated() {
        assertThat(restTemplate.getForEntity("http://localhost:" + port + "/api/v1/parking/rules?spaceId=" + fixture.spaceA, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(queryRules("spaceId=" + fixture.spaceA + "&qrCode=QR-" + fixture.tag + "-01").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(queryRules("durationMinutes=60").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test
    void missingConfigurationPreventsSessionAndHolidayPrevails() throws Exception {
        jdbc.update("UPDATE parking.tariffs SET grace_period_minutes = NULL WHERE id = ?", fixture.tariff);
        assertThat(objectMapper.readTree(queryRules("spaceId=" + fixture.spaceA + "&durationMinutes=60").getBody()).get("reasonCode").asText()).isEqualTo("NO_GRACE_CONFIGURATION");
        assertThat(post("citizen-a-" + fixture.tag, "NO-CONFIG", fixture.spaceA, fixture.vehicleA).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isZero();
        UUID holiday = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO parking.holidays(id, holiday_date, name, zone_id, holiday_type, tariffed, valid_from) VALUES (?, ?, 'TEST ONLY', ?, 'NON_TARIFFED', false, ?)", holiday, LocalDate.now(TEST_TIME_ZONE), fixture.zone, LocalDate.of(2026,1,1));
            assertThat(objectMapper.readTree(queryRules("spaceId=" + fixture.spaceA).getBody()).get("reasonCode").asText()).isEqualTo("HOLIDAY_NON_CHARGEABLE");
            assertThat(post("citizen-a-" + fixture.tag, "HOLIDAY", fixture.spaceA, fixture.vehicleA).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(sessionRepository.countByParkingSpaceId(fixture.spaceA)).isZero();
        } finally { jdbc.update("DELETE FROM parking.holidays WHERE id = ?", holiday); }
    }
    @Test
    void configuredQuoteMatchesPaymentAndAccumulatedExtensionLimit() throws Exception {
        var created = post("citizen-a-" + fixture.tag, "RULES-PAYMENT", fixture.spaceA, fixture.vehicleA, 90);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID session = UUID.fromString(objectMapper.readTree(created.getBody()).get("id").asText());
        HttpHeaders headers = new HttpHeaders(); headers.setBasicAuth("citizen-a-" + fixture.tag, "integration-password");
        headers.setContentType(MediaType.APPLICATION_JSON); headers.set("Idempotency-Key", "RULES-PAYMENT-KEY");
        var response = restTemplate.exchange("http://localhost:" + port + "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("parkingSessionId", session, "paymentMethod", "TEST_METHOD"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var payment = objectMapper.readTree(response.getBody());
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("1.50");
        paymentService.approve(UUID.fromString(payment.get("id").asText()), "TEST-ONLY-INITIAL");
        var extension = extensionService.requestExtension(session, 30, "TEST_PROVIDER", "TEST_METHOD", "RULES-EXTENSION");
        assertThat(extension.getAmount()).isEqualByComparingTo("0.50");
        paymentService.approve(extension.getPaymentId(), "TEST-ONLY-EXTENSION");
        var saved = sessionRepository.findById(session).orElseThrow();
        assertThat(java.time.Duration.between(saved.getStartedAt(),saved.getExpectedEndAt()).toMinutes()).isEqualTo(120);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extensionService.requestExtension(session, 121, "TEST_PROVIDER", "TEST_METHOD", "RULES-TOO-LONG"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("MAX_CONTINUOUS_EXCEEDED");
    }

    private ResponseEntity<String> post(String username, String key, UUID spaceId, UUID vehicleId) {
        return post(username, key, spaceId, vehicleId, 60);
    }
    private ResponseEntity<String> post(String username, String key, UUID spaceId, UUID vehicleId, int duration) {
        String password = "integration-password";
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        Map<String, Object> body = Map.of("parkingSpaceQrCode", "QR-" + fixture.tag + "-" + spaceSuffix(spaceId),
                "vehicleId", vehicleId, "tariffId", fixture.tariff, "durationMinutes", duration);
        return restTemplate.exchange("http://localhost:" + port + "/api/v1/parking/sessions",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String spaceSuffix(UUID spaceId) {
        if (spaceId.equals(fixture.spaceA)) return "01";
        if (spaceId.equals(fixture.spaceB)) return "02";
        if (spaceId.equals(fixture.spaceC)) return "03";
        if (spaceId.equals(fixture.spaceD)) return "04";
        return "05";
    }

    private void insertUser(UUID id, String username, String email, OffsetDateTime now) {
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                id, username, email, passwordEncoder.encode("integration-password"), "Integration", "Citizen", now, now);
    }

    private void insertVehicle(UUID id, UUID ownerId, String plate, OffsetDateTime now) {
        jdbc.update("INSERT INTO identity.vehicles(id, user_id, plate, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, ownerId, plate, now, now);
    }

    private void createSpace(UUID id, String suffix) {
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, fixture.street, "PS-" + fixture.tag + "-" + suffix,
                "QR-" + fixture.tag + "-" + suffix, suffix, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static class Fixture {
        final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final UUID userA = UUID.randomUUID();
        final UUID userB = UUID.randomUUID();
        final UUID vehicleA = UUID.randomUUID();
        final UUID vehicleA2 = UUID.randomUUID();
        final UUID vehicleB = UUID.randomUUID();
        final UUID zone = UUID.randomUUID();
        final UUID street = UUID.randomUUID();
        final UUID spaceA = UUID.randomUUID();
        final UUID spaceB = UUID.randomUUID();
        final UUID spaceC = UUID.randomUUID();
        final UUID spaceD = UUID.randomUUID();
        final UUID spaceE = UUID.randomUUID();
        final UUID tariff = UUID.randomUUID();
    }
}
