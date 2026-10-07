package ec.gob.simertpi.api.notifications;

import ec.gob.simertpi.application.notifications.NotificationGenerationService;
import ec.gob.simertpi.application.notifications.NotificationOutboxProcessor;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationHttpPostgresIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "notification-test-password";
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired NotificationOutboxProcessor outboxProcessor;
    @Autowired NotificationGenerationService generation;
    @Autowired ec.gob.simertpi.application.notifications.NotificationDispatcher dispatcher;

    private final Fixture fixture = new Fixture();

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID citizenRole = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code='CITIZEN'", UUID.class);
        insertUser(fixture.ownerId, fixture.ownerName, now);
        insertUser(fixture.otherId, fixture.otherName, now);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.ownerId, citizenRole);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", fixture.otherId, citizenRole);
        jdbc.update("INSERT INTO identity.vehicles(id, user_id, plate, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                fixture.vehicleId, fixture.ownerId, fixture.plate, now, now);
        for (String channel : java.util.List.of("PUSH","WHATSAPP","EMAIL")) jdbc.update("INSERT INTO notification.preferences(user_id,channel,enabled) VALUES (?,?,true)",fixture.ownerId,channel);
        insertInboxNotification(fixture.ownNotificationId, fixture.ownerId, now);
        insertInboxNotification(fixture.foreignNotificationId, fixture.otherId, now);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notification.notifications WHERE user_id IN (?, ?)", fixture.ownerId, fixture.otherId);
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_id IN (?, ?)", fixture.permitId, fixture.paymentId);
        jdbc.update("DELETE FROM configuration.notification_rules WHERE code LIKE ?", fixture.rulePrefix + "%");
        jdbc.update("DELETE FROM payments.payments WHERE id = ?", fixture.paymentId);
        jdbc.update("DELETE FROM parking.parking_sessions WHERE id = ?", fixture.sessionId);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE id = ?", fixture.spaceId);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", fixture.streetId);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", fixture.zoneId);
        jdbc.update("DELETE FROM identity.vehicles WHERE id = ?", fixture.vehicleId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?, ?)", fixture.ownerId, fixture.otherId);
        jdbc.update("DELETE FROM notification.preferences WHERE user_id IN (?,?)",fixture.ownerId,fixture.otherId);
        jdbc.update("DELETE FROM identity.users WHERE id IN (?, ?)", fixture.ownerId, fixture.otherId);
    }

    @Test
    void citizenCanOnlyReadAndMarkOwnNotifications() throws Exception {
        ResponseEntity<String> anonymous = http.getForEntity(url("/api/v1/notifications/mine"), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> own = exchange("/api/v1/notifications/mine", HttpMethod.GET,
                fixture.ownerName, null);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody()).contains(fixture.ownNotificationId.toString())
                .doesNotContain(fixture.foreignNotificationId.toString(), "recipient", "providerReference");

        ResponseEntity<String> marked = exchange("/api/v1/notifications/" + fixture.ownNotificationId + "/read",
                HttpMethod.PATCH, fixture.ownerName, null);
        assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM notification.notifications WHERE id = ?",
                String.class, fixture.ownNotificationId)).isEqualTo("READ");
        OffsetDateTime readAt = jdbc.queryForObject("SELECT read_at FROM notification.notifications WHERE id = ?",
                OffsetDateTime.class, fixture.ownNotificationId);
        exchange("/api/v1/notifications/" + fixture.ownNotificationId + "/read", HttpMethod.PATCH,
                fixture.ownerName, null);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification.notifications WHERE id = ?",
                OffsetDateTime.class, fixture.ownNotificationId)).isEqualTo(readAt);

        ResponseEntity<String> foreign = exchange("/api/v1/notifications/" + fixture.foreignNotificationId + "/read",
                HttpMethod.PATCH, fixture.ownerName, null);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT status FROM notification.notifications WHERE id = ?",
                String.class, fixture.foreignNotificationId)).isEqualTo("PENDING");
    }

    @Test
    void processesPermitEventsForAllConfiguredChannelsAndDeduplicatesConcurrentRetries() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        insertRule("PERMIT_CREATED", "PUSH", now);
        insertRule("PERMIT_CREATED", "WHATSAPP", now);
        insertRule("PERMIT_CREATED", "EMAIL", now);
        UUID outboxId = UUID.randomUUID();
        String payload = "{\"beneficiaryUserId\":\"" + fixture.ownerId + "\",\"permitId\":\"" + fixture.permitId + "\"}";
        jdbc.update("INSERT INTO audit.outbox_events(id, aggregate_type, aggregate_id, event_type, payload, status, occurred_at, created_at, updated_at) VALUES (?, 'PERMIT', ?, 'PERMIT_CREATED', ?, 'PENDING', ?, ?, ?)",
                outboxId, fixture.permitId, payload, now, now, now);

        outboxProcessor.processPending();
        dispatcher.processDue(OffsetDateTime.now());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE outbox_event_id = ?",
                Integer.class, outboxId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE outbox_event_id = ? AND failure_reason = 'PROVIDER_NOT_CONFIGURED'",
                Integer.class, outboxId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE outbox_event_id = ? AND channel = 'WHATSAPP' AND recipient IS NULL",
                Integer.class, outboxId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM audit.outbox_events WHERE id = ?", String.class, outboxId))
                .isEqualTo("PUBLISHED");
        outboxProcessor.processPending();
        dispatcher.processDue(OffsetDateTime.now());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE outbox_event_id = ?",
                Integer.class, outboxId)).isEqualTo(3);

        insertPaymentFixture(now);
        insertRule("PAYMENT_APPROVED", "EMAIL", now);
        UUID paymentOutboxId = UUID.randomUUID();
        String paymentPayload = "{\"paymentId\":\"" + fixture.paymentId + "\",\"parkingSessionId\":\"" +
                fixture.sessionId + "\",\"status\":\"APPROVED\",\"amount\":\"1.00\",\"currency\":\"USD\"}";
        jdbc.update("INSERT INTO audit.outbox_events(id, aggregate_type, aggregate_id, event_type, payload, status, occurred_at, created_at, updated_at) VALUES (?, 'PAYMENT', ?, 'PAYMENT_APPROVED', ?, 'PENDING', ?, ?, ?)",
                paymentOutboxId, fixture.paymentId, paymentPayload, now, now, now);
        outboxProcessor.processPending();
        dispatcher.processDue(OffsetDateTime.now());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE outbox_event_id = ? AND channel = 'EMAIL'",
                Integer.class, paymentOutboxId)).isEqualTo(1);

        insertRule("PAYMENT_DECLINED", "PUSH", now);
        UUID sharedEventId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> concurrentGenerate(sharedEventId, now, ready, start));
            Future<Integer> second = executor.submit(() -> concurrentGenerate(sharedEventId, now, ready, start));
            ready.await(); start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE source_event_id = ?",
                Integer.class, sharedEventId)).isEqualTo(2);
    }

    private int concurrentGenerate(UUID eventId, OffsetDateTime at, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        return generation.generate(fixture.ownerId, "PAYMENT_DECLINED", eventId, null,
                "PAYMENT", fixture.permitId, at, Map.of("amount", "1.00"));
    }

    private void insertRule(String eventType, String channel, OffsetDateTime now) {
        jdbc.update("INSERT INTO configuration.notification_rules(id, code, event_type, channel, minutes_before, enabled, title_template, message_template, valid_from, created_at, updated_at) VALUES (?, ?, ?, ?, 0, true, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), fixture.rulePrefix + eventType + "-" + channel, eventType, channel,
                "{eventType}", eventType.startsWith("PAYMENT") ? "Notification {amount}" : "Notification {eventType}", now.minusDays(1), now, now);
    }

    private void insertPaymentFixture(OffsetDateTime now) {
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)", fixture.zoneId, "NZ-" + fixture.suffix, "Notification zone");
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, ?)",
                fixture.streetId, fixture.zoneId, "NS-" + fixture.suffix, "Notification street");
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number) VALUES (?, ?, ?, ?, ?)",
                fixture.spaceId, fixture.streetId, "NP-" + fixture.suffix, "NQ-" + fixture.suffix, "01");
        jdbc.update("INSERT INTO parking.parking_sessions(id, user_id, vehicle_id, parking_space_id, started_at, expected_end_at, status, total_amount, extension_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 1, 0, ?, ?)",
                fixture.sessionId, fixture.ownerId, fixture.vehicleId, fixture.spaceId, now.minusMinutes(5), now.plusMinutes(55), now, now);
        jdbc.update("INSERT INTO payments.payments(id, parking_session_id, provider, idempotency_key, amount, currency, status, created_at, updated_at) VALUES (?, ?, 'INTERNAL', ?, 1, 'USD', 'APPROVED', ?, ?)",
                fixture.paymentId, fixture.sessionId, "NKEY-" + fixture.suffix, now, now);
    }

    private void insertInboxNotification(UUID id, UUID userId, OffsetDateTime now) {
        jdbc.update("INSERT INTO notification.notifications(id, user_id, notification_type, channel, title, message, status, created_at, updated_at) VALUES (?, ?, 'EXPIRATION', 'PUSH', 'Title', 'Message', 'PENDING', ?, ?)",
                id, userId, now, now);
    }
    private void insertUser(UUID id, String username, OffsetDateTime now) {
        jdbc.update("INSERT INTO identity.users(id, username, email, phone, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'Notify', 'Test', true, ?, ?)",
                id, username, username + "@test.invalid", "+593999000222", passwordEncoder.encode(PASSWORD), now, now);
    }
    private ResponseEntity<String> exchange(String path, HttpMethod method, String username, Object body) {
        HttpHeaders headers = new HttpHeaders(); headers.setBasicAuth(username, PASSWORD);
        return http.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }
    private String url(String path) { return "http://localhost:" + port + path; }

    private static final class Fixture {
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final String rulePrefix = "NOTIFY-" + suffix + "-";
        final UUID ownerId = UUID.randomUUID();
        final UUID otherId = UUID.randomUUID();
        final UUID ownNotificationId = UUID.randomUUID();
        final UUID foreignNotificationId = UUID.randomUUID();
        final UUID permitId = UUID.randomUUID();
        final UUID vehicleId = UUID.randomUUID();
        final UUID zoneId = UUID.randomUUID();
        final UUID streetId = UUID.randomUUID();
        final UUID spaceId = UUID.randomUUID();
        final UUID sessionId = UUID.randomUUID();
        final UUID paymentId = UUID.randomUUID();
        final String plate = "N" + suffix.substring(0, 8).toUpperCase();
        final String ownerName = "notify-owner-" + suffix;
        final String otherName = "notify-other-" + suffix;
    }
}
