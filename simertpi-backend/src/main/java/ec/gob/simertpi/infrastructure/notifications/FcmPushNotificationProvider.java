package ec.gob.simertpi.infrastructure.notifications;

import ec.gob.simertpi.application.notifications.NotificationProvider;
import ec.gob.simertpi.application.notifications.NotificationProviderRequest;
import ec.gob.simertpi.application.notifications.NotificationProviderResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(name = "simertpi.notifications.fcm.enabled", havingValue = "true")
public class FcmPushNotificationProvider implements NotificationProvider {
    private final FirebaseCloudMessagingClient client;

    public FcmPushNotificationProvider(FirebaseCloudMessagingClient client) {
        this.client = client;
    }

    @Override public String code() { return "FCM"; }
    @Override public Set<String> channels() { return Set.of("PUSH"); }
    @Override public boolean supportsIdempotency() { return false; }

    @Override
    public NotificationProviderResult send(NotificationProviderRequest request) {
        try {
            String messageId = client.send(request);
            if (messageId == null || !messageId.matches("[A-Za-z0-9._:/-]{1,150}")) {
                return NotificationProviderResult.failure(
                        NotificationProviderResult.Status.UNKNOWN, code(), false, "FCM_RESULT_INVALID");
            }
            return new NotificationProviderResult(
                    NotificationProviderResult.Status.DELIVERED, code(), messageId, false, null);
        } catch (FirebasePushException failure) {
            return switch (failure.kind()) {
                case INVALID_TOKEN -> NotificationProviderResult.failure(
                        NotificationProviderResult.Status.INVALID_DESTINATION, code(), false, "FCM_TOKEN_INVALID");
                case RETRYABLE -> NotificationProviderResult.failure(
                        NotificationProviderResult.Status.TEMPORARY_FAILURE, code(), true, "FCM_TEMPORARY_FAILURE");
                case PERMANENT -> NotificationProviderResult.failure(
                        NotificationProviderResult.Status.PERMANENT_FAILURE, code(), false, "FCM_PERMANENT_FAILURE");
            };
        }
    }
}
