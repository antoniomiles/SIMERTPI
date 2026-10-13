package ec.gob.simertpi.infrastructure.notifications;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.AndroidConfig;
import java.time.Instant;
import ec.gob.simertpi.application.notifications.NotificationProviderRequest;

public final class FirebaseAdminMessagingClient implements FirebaseCloudMessagingClient {
    private final FirebaseMessaging messaging;

    public FirebaseAdminMessagingClient(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    static AndroidConfig androidConfig(NotificationProviderRequest request) {
        return AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).setTtl(ttlMillis(request,Instant.now().toEpochMilli())).build();
    }
    static long ttlMillis(NotificationProviderRequest request,long now) {
        long ttl=6*60*60*1000L;
        String expiry=request.data().get("validUntil");
        if(expiry!=null) {
            try { ttl=Math.max(0,Math.min(ttl,Long.parseLong(expiry)-now)); }
            catch(NumberFormatException ignored) { ttl=0; }
        }
        return ttl;
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
                    .setAndroidConfig(androidConfig(request))
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
