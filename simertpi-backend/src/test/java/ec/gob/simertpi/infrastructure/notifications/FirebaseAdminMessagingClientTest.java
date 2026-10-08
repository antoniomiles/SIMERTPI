package ec.gob.simertpi.infrastructure.notifications;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import ec.gob.simertpi.application.notifications.NotificationProviderRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirebaseAdminMessagingClientTest {
    @Test
    void sendsDataOnlyMessageWithRecipientGuardAndNoAutoRenderedContent() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("projects/dev/messages/42");
        UUID owner = UUID.randomUUID();
        var request = new NotificationProviderRequest(UUID.randomUUID(), UUID.randomUUID(), "PUSH",
                "fcm-token", "Sensitive title", "Sensitive message",
                Map.of("recipientOwnerId", owner.toString(), "eventType", "PARKING_ENDING_SOON"));

        assertThat(new FirebaseAdminMessagingClient(messaging).send(request))
                .isEqualTo("projects/dev/messages/42");

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(messaging).send(message.capture());
        var built = message.getValue();
        var notification = Message.class.getDeclaredMethod("getNotification");
        var data = Message.class.getDeclaredMethod("getData");
        notification.setAccessible(true);
        data.setAccessible(true);
        assertThat(notification.invoke(built)).isNull();
        assertThat((Map<String, String>) data.invoke(built))
                .containsEntry("recipientOwnerId", owner.toString())
                .containsEntry("eventType", "PARKING_ENDING_SOON")
                .doesNotContainKey("title")
                .doesNotContainKey("body");
    }
}
