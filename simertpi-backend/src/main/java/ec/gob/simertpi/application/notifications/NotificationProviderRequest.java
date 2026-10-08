package ec.gob.simertpi.application.notifications;
import java.util.UUID;
import java.util.Map;
/** Destination is sensitive: never log this object. Delivery ID is the stable provider idempotency reference. */
public record NotificationProviderRequest(UUID deliveryId, UUID notificationId, String channel,
 String destination, String subject, String body, Map<String,String> data) {
 public NotificationProviderRequest {
  data=data==null?Map.of():Map.copyOf(data);
 }
 public NotificationProviderRequest(UUID deliveryId,UUID notificationId,String channel,String destination,String subject,String body){
  this(deliveryId,notificationId,channel,destination,subject,body,Map.of());
 }
 @Override public String toString() { return "NotificationProviderRequest[deliveryId="+deliveryId+",channel="+channel+"]"; }
}
