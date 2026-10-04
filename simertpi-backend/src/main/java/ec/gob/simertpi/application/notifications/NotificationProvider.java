package ec.gob.simertpi.application.notifications;
import java.util.Set;
public interface NotificationProvider {
 String code();
 Set<String> channels();
 default boolean supportsIdempotency() { return false; }
 NotificationProviderResult send(NotificationProviderRequest request);
}
