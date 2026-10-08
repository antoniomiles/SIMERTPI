package ec.gob.simertpi.infrastructure.notifications;

import ec.gob.simertpi.application.notifications.NotificationProviderRequest;

/** Narrow boundary around Firebase Admin so provider behavior is testable without Firebase. */
public interface FirebaseCloudMessagingClient {
    String send(NotificationProviderRequest request) throws FirebasePushException;
}
