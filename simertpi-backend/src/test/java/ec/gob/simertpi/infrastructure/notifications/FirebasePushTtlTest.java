package ec.gob.simertpi.infrastructure.notifications;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import ec.gob.simertpi.application.notifications.NotificationProviderRequest;
import static org.assertj.core.api.Assertions.assertThat;
class FirebasePushTtlTest {
    NotificationProviderRequest request(String expiry) {
        return new NotificationProviderRequest(UUID.randomUUID(),UUID.randomUUID(),"PUSH","redacted","title","body",Map.of("validUntil",expiry));
    }
    @Test void ttlFollowsRemainingValidityAndNeverExtendsOnRetry() {
        assertThat(FirebaseAdminMessagingClient.ttlMillis(request("2000"),1000)).isEqualTo(1000);
        assertThat(FirebaseAdminMessagingClient.ttlMillis(request("2000"),1500)).isEqualTo(500);
    }
    @Test void expiredOrMalformedMessagesHaveZeroStorageTime() {
        assertThat(FirebaseAdminMessagingClient.ttlMillis(request("1000"),2000)).isZero();
        assertThat(FirebaseAdminMessagingClient.ttlMillis(request("invalid"),2000)).isZero();
    }
}
