package ec.gob.simertpi.application.notifications;
import java.util.UUID;
/** Destination is sensitive: never log this object. Delivery ID is the stable provider idempotency reference. */
public record NotificationProviderRequest(UUID deliveryId, UUID notificationId, String channel,
 String destination, String subject, String body) {
 @Override public String toString() { return "NotificationProviderRequest[deliveryId="+deliveryId+",channel="+channel+"]"; }
}
