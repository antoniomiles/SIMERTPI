package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.domain.configuration.entity.NotificationRule;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.configuration.repository.NotificationRuleRepository;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.notification.entity.Notification;
import ec.gob.simertpi.domain.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationGenerationServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final User user = new User();
    private NotificationRuleRepository rules;
    private NotificationRepository notifications;
    private UserRepository users;
    private NotificationGenerationService generation;
    private final Map<UUID, Notification> inserted = new HashMap<>();
    private final Map<String, FakeSender> senders = Map.of(
            "PUSH", new FakeSender("PUSH"),
            "WHATSAPP", new FakeSender("WHATSAPP"),
            "EMAIL", new FakeSender("EMAIL"));

    @BeforeEach
    void setUp() {
        rules = mock(NotificationRuleRepository.class);
        notifications = mock(NotificationRepository.class);
        users = mock(UserRepository.class);
        user.setId(userId); user.setEnabled(true); user.setEmail("user@example.test"); user.setPhone("+593999000111");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(notifications.insertIfAbsent(any(), eq(userId), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Notification notification = new Notification();
                    notification.setId(invocation.getArgument(0));
                    notification.setUserId(invocation.getArgument(1));
                    notification.setNotificationType(invocation.getArgument(2));
                    notification.setChannel(invocation.getArgument(3));
                    notification.setTitle(invocation.getArgument(4));
                    notification.setMessage(invocation.getArgument(5));
                    notification.setReferenceType(invocation.getArgument(6));
                    notification.setReferenceId(invocation.getArgument(7));
                    notification.setSourceEventId(invocation.getArgument(8));
                    notification.setOutboxEventId(invocation.getArgument(9));
                    notification.setRuleId(invocation.getArgument(10));
                    notification.setRecipient(invocation.getArgument(11));
                    notification.setCreatedAt(invocation.getArgument(12));
                    notification.setUpdatedAt(invocation.getArgument(12));
                    notification.setStatus("PENDING");
                    inserted.put(notification.getId(), notification);
                    return 1;
                });
        when(notifications.findById(any())).thenAnswer(invocation -> Optional.ofNullable(inserted.get(invocation.getArgument(0))));
        when(notifications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationDeliveryService delivery = new NotificationDeliveryService(notifications, users,
                List.copyOf(senders.values()), mock(AuditService.class));
        generation = new NotificationGenerationService(rules, notifications, delivery, users);
    }

    @Test
    void generatesPushWhatsAppAndEmailAsIndependentNotifications() {
        OffsetDateTime now = OffsetDateTime.now();
        List<NotificationRule> activeRules = List.of(rule("PUSH", now), rule("WHATSAPP", now), rule("EMAIL", now));
        when(rules.findActiveForEventAt("PAYMENT_APPROVED", now)).thenReturn(activeRules);

        assertEquals(3, generation.generate(userId, "PAYMENT_APPROVED", UUID.randomUUID(), UUID.randomUUID(),
                "PAYMENT", UUID.randomUUID(), now, Map.of("amount", "1.25")));

        assertEquals(1, senders.get("PUSH").requests.size());
        assertEquals("user:" + userId, senders.get("PUSH").requests.getFirst().recipient());
        assertEquals("+593999000111", senders.get("WHATSAPP").requests.getFirst().recipient());
        assertEquals("user@example.test", senders.get("EMAIL").requests.getFirst().recipient());
        assertTrue(inserted.values().stream().allMatch(n -> "SENT".equals(n.getStatus())));
    }

    @Test
    void failedWhatsAppDoesNotPreventPushAndEmailFromSending() {
        OffsetDateTime now = OffsetDateTime.now();
        senders.get("WHATSAPP").fail = true;
        when(rules.findActiveForEventAt("PERMIT_CREATED", now)).thenReturn(List.of(
                rule("PERMIT_CREATED", "PUSH", now), rule("PERMIT_CREATED", "WHATSAPP", now),
                rule("PERMIT_CREATED", "EMAIL", now)));

        assertEquals(3, generation.generate(userId, "PERMIT_CREATED", UUID.randomUUID(), null,
                "PERMIT", UUID.randomUUID(), now, Map.of()));

        assertEquals("SENT", status("PUSH"));
        assertEquals("FAILED", status("WHATSAPP"));
        assertEquals("SENT", status("EMAIL"));
        assertNotNull(inserted.values().stream().filter(n -> "WHATSAPP".equals(n.getChannel()))
                .findFirst().orElseThrow().getNextAttemptAt());
    }

    @Test
    void rejectsDisabledAndOutOfValidityRulesAndIgnoresEventsWithoutRules() {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationRule disabled = rule("EXPIRATION", "PUSH", now); disabled.setEnabled(false);
        NotificationRule expired = rule("EXPIRATION", "EMAIL", now.minusDays(2)); expired.setValidTo(now.minusDays(1));
        assertEquals(0, generation.createForRule(userId, disabled, "EXPIRATION", UUID.randomUUID(), null,
                "PARKING_CONTROL_EVENT", UUID.randomUUID(), now, Map.of()));
        assertEquals(0, generation.createForRule(userId, expired, "EXPIRATION", UUID.randomUUID(), null,
                "PARKING_CONTROL_EVENT", UUID.randomUUID(), now, Map.of()));
        when(rules.findActiveForEventAt("GRACE_PERIOD", now)).thenReturn(List.of());
        assertEquals(0, generation.generate(userId, "GRACE_PERIOD", UUID.randomUUID(), null,
                "PARKING_CONTROL_EVENT", UUID.randomUUID(), now, Map.of()));
        verify(notifications, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateEventRuleChannelIsIgnoredAndRetryCanSucceed() {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationRule whatsapp = rule("PERMIT_EXPIRED", "WHATSAPP", now);
        senders.get("WHATSAPP").fail = true;
        when(rules.findActiveForEventAt("PERMIT_EXPIRED", now)).thenReturn(List.of(whatsapp));
        UUID eventId = UUID.randomUUID();
        assertEquals(1, generation.generate(userId, "PERMIT_EXPIRED", eventId, null,
                "PERMIT", UUID.randomUUID(), now, Map.of()));

        senders.get("WHATSAPP").fail = false;
        when(notifications.lockDueRetries(any())).thenReturn(List.copyOf(inserted.values()));
        NotificationDeliveryService delivery = new NotificationDeliveryService(notifications, users,
                List.copyOf(senders.values()), mock(AuditService.class));
        delivery.retryDue(OffsetDateTime.now().plusMinutes(5));
        assertTrue(inserted.values().stream().allMatch(n -> "SENT".equals(n.getStatus())));
        verify(notifications).lockDueRetries(any());
    }

    @Test
    void databaseDeduplicationPreventsSecondDeliveryForSameEventRuleAndChannel() {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationRule push = rule("PAYMENT_APPROVED", "PUSH", now);
        UUID eventId = UUID.randomUUID();
        when(rules.findActiveForEventAt("PAYMENT_APPROVED", now)).thenReturn(List.of(push));
        when(notifications.insertIfAbsent(any(), eq(userId), eq("PAYMENT_APPROVED"), eq("PUSH"), anyString(),
                anyString(), anyString(), any(), eq(eventId), any(), eq(push.getId()), any(), eq(now)))
                .thenReturn(0);

        assertEquals(0, generation.generate(userId, "PAYMENT_APPROVED", eventId, null,
                "PAYMENT", UUID.randomUUID(), now, Map.of()));
        assertTrue(senders.get("PUSH").requests.isEmpty());
    }

    @Test
    void coversExistingParkingPermitAndPaymentEventTypes() {
        OffsetDateTime now = OffsetDateTime.now();
        for (String event : List.of("EXPIRATION", "GRACE_PERIOD", "MAX_TIME_REACHED", "PERMIT_CREATED",
                "PERMIT_CANCELLED", "PERMIT_EXPIRED", "PAYMENT_APPROVED")) {
            when(rules.findActiveForEventAt(event, now)).thenReturn(List.of());
            assertEquals(0, generation.generate(userId, event, UUID.randomUUID(), null,
                    "EVENT", UUID.randomUUID(), now, Map.of()));
        }
    }

    private String status(String channel) {
        return inserted.values().stream().filter(n -> channel.equals(n.getChannel())).findFirst().orElseThrow().getStatus();
    }

    private NotificationRule rule(String channel, OffsetDateTime from) {
        return rule("PAYMENT_APPROVED", channel, from);
    }

    private NotificationRule rule(String eventType, String channel, OffsetDateTime from) {
        NotificationRule rule = new NotificationRule();
        rule.setId(UUID.randomUUID()); rule.setCode("RULE-" + UUID.randomUUID());
        rule.setEventType(eventType); rule.setChannel(channel); rule.setMinutesBefore(0);
        rule.setEnabled(true); rule.setTitleTemplate("Event {amount}"); rule.setMessageTemplate("Details {amount}");
        rule.setValidFrom(from); rule.setCreatedAt(from); rule.setUpdatedAt(from);
        return rule;
    }

    private static final class FakeSender implements NotificationChannelSender {
        private final String channel;
        private final List<NotificationSendRequest> requests = new ArrayList<>();
        private boolean fail;
        private FakeSender(String channel) { this.channel = channel; }
        @Override public String channel() { return channel; }
        @Override public boolean isConfigured() { return true; }
        @Override public NotificationSendResult send(NotificationSendRequest request) {
            requests.add(request);
            if (fail) throw new IllegalStateException("controlled fake failure");
            return new NotificationSendResult("test-provider-ref");
        }
    }
}
