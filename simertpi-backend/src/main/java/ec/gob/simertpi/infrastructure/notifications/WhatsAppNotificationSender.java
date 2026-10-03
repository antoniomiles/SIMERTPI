package ec.gob.simertpi.infrastructure.notifications;

import ec.gob.simertpi.application.notifications.NotificationChannelSender;
import ec.gob.simertpi.application.notifications.NotificationSendRequest;
import ec.gob.simertpi.application.notifications.NotificationSendResult;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppNotificationSender implements NotificationChannelSender {
    @Override public String channel() { return "WHATSAPP"; }
    @Override public boolean isConfigured() { return false; }
    @Override public NotificationSendResult send(NotificationSendRequest request) {
        throw new IllegalStateException("WhatsApp provider is not configured");
    }
}
