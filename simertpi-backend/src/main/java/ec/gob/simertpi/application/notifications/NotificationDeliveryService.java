package ec.gob.simertpi.application.notifications;
import ec.gob.simertpi.domain.notification.entity.Notification;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
@Service
public class NotificationDeliveryService {
 private final NotificationDispatcher dispatcher;
 public NotificationDeliveryService(NotificationDispatcher dispatcher){this.dispatcher=dispatcher;}
 public void deliver(Notification notification){dispatcher.prepare(notification.getId());}
 public void retryDue(OffsetDateTime now){dispatcher.processDue(now);}
}
