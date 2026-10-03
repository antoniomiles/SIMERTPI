package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.application.audit.AuditService;

import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.notification.entity.Notification;
import ec.gob.simertpi.domain.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NotificationDeliveryService {
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final Map<String, NotificationChannelSender> senders;
    private final AuditService audit;

    public NotificationDeliveryService(NotificationRepository notifications, UserRepository users,
                                       List<NotificationChannelSender> senders, AuditService audit) {
        this.notifications = notifications;
        this.users = users;
        this.audit = audit;
        this.senders = senders.stream().collect(Collectors.toUnmodifiableMap(
                sender -> sender.channel().toUpperCase(), Function.identity()));
    }

    public void deliver(Notification notification) {
        OffsetDateTime now = OffsetDateTime.now();
        User user = users.findById(notification.getUserId()).filter(User::isEnabled).orElse(null);
        String destination = notification.getRecipient();
        if (destination == null && user != null) destination = destinationFor(notification.getChannel(), user);
        notification.setRecipient(destination);
        notification.setAttemptCount(notification.getAttemptCount() + 1);
        notification.setLastAttemptAt(now);

        NotificationChannelSender sender = senders.get(notification.getChannel().toUpperCase());
        if (user == null || destination == null || destination.isBlank()) {
            fail(notification, now, "DESTINATION_UNAVAILABLE");
        } else if (sender == null || !sender.isConfigured()) {
            fail(notification, now, "PROVIDER_NOT_CONFIGURED");
        } else {
            try {
                NotificationSendResult result = sender.send(new NotificationSendRequest(
                        notification.getId(), notification.getUserId(), notification.getChannel(),
                        destination, notification.getTitle(), notification.getMessage(), Map.of()));
                notification.setStatus("SENT");
                notification.setSentAt(now);
                notification.setProviderReference(result == null ? null : result.providerReference());
                notification.setFailureReason(null);
                notification.setNextAttemptAt(null);
            } catch (RuntimeException providerFailure) {
                fail(notification, now, "DELIVERY_FAILED");
            }
        }
        notification.setUpdatedAt(now);
        notifications.save(notification);
        audit.recordOutcome("NOTIFICATION_DELIVERY", "NOTIFICATION", notification.getId(),
                "FAILED".equals(notification.getStatus()) ? "FAILURE" : "SUCCESS",
                Map.of("channel", notification.getChannel(), "status", notification.getStatus(),
                        "attemptCount", notification.getAttemptCount()));
    }

    @Transactional
    public void retryDue(OffsetDateTime now) {
        for (Notification notification : notifications.lockDueRetries(now)) {
            deliver(notification);
        }
    }

    private String destinationFor(String channel, User user) {
        return switch (channel.toUpperCase()) {
            case "EMAIL" -> user.getEmail();
            case "WHATSAPP" -> user.getPhone();
            // Device tokens are intentionally resolved by the future Push provider.
            case "PUSH" -> "user:" + user.getId();
            default -> null;
        };
    }

    private void fail(Notification notification, OffsetDateTime now, String reason) {
        long delayMinutes = Math.min(60, 1L << Math.min(notification.getAttemptCount() - 1, 5));
        notification.setStatus("FAILED");
        notification.setFailureReason(reason);
        notification.setNextAttemptAt(now.plusMinutes(delayMinutes));
    }
}
