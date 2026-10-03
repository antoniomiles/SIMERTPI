package ec.gob.simertpi.application.notifications;

public interface NotificationChannelSender {
    String channel();
    boolean isConfigured();
    NotificationSendResult send(NotificationSendRequest request);
}
