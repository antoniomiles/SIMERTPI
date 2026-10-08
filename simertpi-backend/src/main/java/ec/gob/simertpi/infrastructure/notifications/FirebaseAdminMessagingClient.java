package ec.gob.simertpi.infrastructure.notifications;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import ec.gob.simertpi.application.notifications.NotificationProviderRequest;

public final class FirebaseAdminMessagingClient implements FirebaseCloudMessagingClient {
    private final FirebaseMessaging messaging;

    public FirebaseAdminMessagingClient(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public String send(NotificationProviderRequest request) throws FirebasePushException {
        try {
            Message message = Message.builder()
                    .setToken(request.destination())
                    // Data-only: Android must not auto-render a notification for a
                    // stale account token. The native receiver checks the active
                    // owner/consent gate before posting a generic local notification.
                    .putAllData(request.data())
                    .build();
            return messaging.send(message);
        } catch (FirebaseMessagingException failure) {
            var code = failure.getMessagingErrorCode();
            if (code == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED) {
                throw new FirebasePushException(FirebasePushException.Kind.INVALID_TOKEN);
            }
            if (code == com.google.firebase.messaging.MessagingErrorCode.UNAVAILABLE
                    || code == com.google.firebase.messaging.MessagingErrorCode.INTERNAL
                    || code == com.google.firebase.messaging.MessagingErrorCode.QUOTA_EXCEEDED) {
                throw new FirebasePushException(FirebasePushException.Kind.RETRYABLE);
            }
            throw new FirebasePushException(FirebasePushException.Kind.PERMANENT);
        }
    }
}
