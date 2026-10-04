package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.domain.configuration.entity.NotificationRule;
import ec.gob.simertpi.domain.configuration.repository.NotificationRuleRepository;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.notification.entity.Notification;
import ec.gob.simertpi.domain.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationGenerationService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ec.gob.simertpi.application.operations.OperationalMetrics metrics;
    private void metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event event) { if(metrics!=null)metrics.event(event); }

    private final NotificationRuleRepository rules;
    private final NotificationRepository notifications;
    private final NotificationDeliveryService delivery;
    private final UserRepository users;

    public NotificationGenerationService(NotificationRuleRepository rules, NotificationRepository notifications,
                                         NotificationDeliveryService delivery, UserRepository users) {
        this.rules = rules;
        this.notifications = notifications;
        this.delivery = delivery;
        this.users = users;
    }

    @Transactional
    @Audited(action = "NOTIFICATIONS_GENERATED", resourceType = "NOTIFICATION",
            resourceIdArgument = 2, idempotencyArgument = 2)
    public int generate(UUID userId, String eventType, UUID sourceEventId, UUID outboxEventId,
                        String referenceType, UUID referenceId, OffsetDateTime eventAt,
                        Map<String, ?> values) {
        User recipient = users.findById(userId).filter(User::isEnabled).orElse(null);
        if (recipient == null) return 0;
        int created = 0;
        for (NotificationRule rule : rules.findActiveForEventAt(eventType, eventAt)) {
            created += createForRule(recipient, rule, eventType, sourceEventId, outboxEventId,
                    referenceType, referenceId, eventAt, values);
        }
        return created;
    }

    @Transactional
    public int createForRule(UUID userId, NotificationRule rule, String eventType, UUID sourceEventId,
                             UUID outboxEventId, String referenceType, UUID referenceId,
                             OffsetDateTime createdAt, Map<String, ?> values) {
        User recipient = users.findById(userId).filter(User::isEnabled).orElse(null);
        return recipient == null ? 0 : createForRule(recipient, rule, eventType, sourceEventId,
                outboxEventId, referenceType, referenceId, createdAt, values);
    }

    private int createForRule(User recipient, NotificationRule rule, String eventType, UUID sourceEventId,
                              UUID outboxEventId, String referenceType, UUID referenceId,
                              OffsetDateTime createdAt, Map<String, ?> values) {
        if (!rule.isEnabled() || !rule.getEventType().equals(eventType)
                || rule.getValidFrom().isAfter(createdAt)
                || (rule.getValidTo() != null && rule.getValidTo().isBefore(createdAt))) return 0;
        Map<String, Object> templateValues = new java.util.HashMap<>();
        if (values != null) templateValues.putAll(values);
        templateValues.put("eventType", eventType);
        UUID notificationId = UUID.randomUUID();
        int inserted = notifications.insertIfAbsent(notificationId, recipient.getId(), eventType,
                rule.getChannel(), NotificationTemplateRenderer.render(rule.getTitleTemplate(), templateValues, 200),
                NotificationTemplateRenderer.render(rule.getMessageTemplate(), templateValues, 1000), referenceType, referenceId,
                sourceEventId, outboxEventId, rule.getId(), null, createdAt);
        if (inserted != 1) return 0;
        metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event.NOTIFICATION_GENERATED);
        Notification notification = notifications.findById(notificationId).orElseThrow();
        delivery.deliver(notification);
        return 1;
    }

}
