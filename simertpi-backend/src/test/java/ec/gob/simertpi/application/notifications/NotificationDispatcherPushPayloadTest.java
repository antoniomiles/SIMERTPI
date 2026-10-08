package ec.gob.simertpi.application.notifications;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDispatcherPushPayloadTest {
    @Test
    void pushPayloadIsDataOnlyAndBoundToItsLogicalRecipient() {
        UUID notification = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Map<String, Object> inboxEvent = Map.of(
                "id", notification,
                "notification_type", "PARKING_ENDING_SOON",
                "user_id", ownerA,
                "reference_type", "PARKING_SESSION",
                "reference_id", session,
                "title", "A private title",
                "message", "A private message");

        Map<String, String> payload = NotificationDispatcher.pushPayload(inboxEvent);

        assertThat(payload).containsEntry("notificationId", notification.toString())
                .containsEntry("eventType", "PARKING_ENDING_SOON")
                .containsEntry("recipientOwnerId", ownerA.toString())
                .containsEntry("resourceType", "PARKING_SESSION")
                .containsEntry("resourceId", session.toString())
                .doesNotContainKey("title")
                .doesNotContainKey("message")
                .doesNotContainKey("plate")
                .doesNotContainKey("email");
    }
}
