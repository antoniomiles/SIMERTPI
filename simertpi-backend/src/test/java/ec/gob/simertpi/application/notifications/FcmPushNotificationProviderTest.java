package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.infrastructure.notifications.FcmPushNotificationProvider;
import ec.gob.simertpi.infrastructure.notifications.FirebaseCloudMessagingClient;
import ec.gob.simertpi.infrastructure.notifications.FirebasePushException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FcmPushNotificationProviderTest {
    private NotificationProviderRequest request() {
        return new NotificationProviderRequest(UUID.randomUUID(), UUID.randomUUID(), "PUSH",
                "sensitive-registration-token", "Tu estacionamiento", "Aviso ciudadano",
                Map.of("eventType", "PARKING_ENDING_SOON"));
    }

    @Test void successfulSendIsDeliveredAndDoesNotExposeDestinationInToString() {
        var provider = new FcmPushNotificationProvider(request -> "projects/dev/messages/123");
        var result = provider.send(request());
        assertThat(result.status()).isEqualTo(NotificationProviderResult.Status.DELIVERED);
        assertThat(result.externalMessageId()).isEqualTo("projects/dev/messages/123");
        assertThat(request().toString()).doesNotContain("sensitive-registration-token");
        assertThat(provider.supportsIdempotency()).isFalse();
    }

    @Test void invalidFcmTokenIsPermanentInvalidDestination() {
        FirebaseCloudMessagingClient client = request -> {
            throw new FirebasePushException(FirebasePushException.Kind.INVALID_TOKEN);
        };
        var result = new FcmPushNotificationProvider(client).send(request());
        assertThat(result.status()).isEqualTo(NotificationProviderResult.Status.INVALID_DESTINATION);
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("FCM_TOKEN_INVALID");
    }

    @Test void temporaryFcmFailureIsRetriedByExistingDispatcherPolicy() {
        FirebaseCloudMessagingClient client = request -> {
            throw new FirebasePushException(FirebasePushException.Kind.RETRYABLE);
        };
        var result = new FcmPushNotificationProvider(client).send(request());
        assertThat(result.status()).isEqualTo(NotificationProviderResult.Status.TEMPORARY_FAILURE);
        assertThat(result.retryable()).isTrue();
    }

    @Test void permanentFcmFailureDoesNotRetry() {
        FirebaseCloudMessagingClient client = request -> {
            throw new FirebasePushException(FirebasePushException.Kind.PERMANENT);
        };
        var result = new FcmPushNotificationProvider(client).send(request());
        assertThat(result.status()).isEqualTo(NotificationProviderResult.Status.PERMANENT_FAILURE);
        assertThat(result.retryable()).isFalse();
    }

    @Test void invalidProviderMessageIdIsNotReportedAsDelivered() {
        var provider = new FcmPushNotificationProvider(request -> "bad id\n");
        assertThat(provider.send(request()).status()).isEqualTo(NotificationProviderResult.Status.UNKNOWN);
    }
}
