package ec.gob.simertpi.infrastructure.notifications;

import ec.gob.simertpi.application.notifications.NotificationChannelSender;
import ec.gob.simertpi.application.notifications.NotificationSendRequest;
import ec.gob.simertpi.application.notifications.NotificationSendResult;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationSender implements NotificationChannelSender {
    @Override public String channel() { return "EMAIL"; }
    @Override public boolean isConfigured() { return false; }
    @Override public NotificationSendResult send(NotificationSendRequest request) {
        throw new IllegalStateException("Email provider is not configured");
    }
}
