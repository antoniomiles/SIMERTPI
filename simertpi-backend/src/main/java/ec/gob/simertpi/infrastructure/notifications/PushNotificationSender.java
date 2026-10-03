package ec.gob.simertpi.infrastructure.notifications;

import ec.gob.simertpi.application.notifications.NotificationChannelSender;
import ec.gob.simertpi.application.notifications.NotificationSendRequest;
import ec.gob.simertpi.application.notifications.NotificationSendResult;
import org.springframework.stereotype.Component;

@Component
public class PushNotificationSender implements NotificationChannelSender {
    @Override public String channel() { return "PUSH"; }
    @Override public boolean isConfigured() { return false; }
    @Override public NotificationSendResult send(NotificationSendRequest request) {
        throw new IllegalStateException("Push provider is not configured");
    }
}
