package ec.gob.simertpi.application.notifications;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class NotificationRetryScheduler {
    private final NotificationDeliveryService delivery;

    public NotificationRetryScheduler(NotificationDeliveryService delivery) { this.delivery = delivery; }

    @Scheduled(fixedDelayString = "${simertpi.notifications.retry.fixed-delay-ms:60000}")
    public void retryFailedChannels() {
        delivery.retryDue(OffsetDateTime.now());
    }
}
