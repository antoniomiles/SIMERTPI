package ec.gob.simertpi.application.notifications;

import java.util.Map;
import java.util.UUID;

public record NotificationSendRequest(UUID notificationId, UUID userId, String channel,
                                      String recipient, String title, String message,
                                      Map<String, Object> data) { }
