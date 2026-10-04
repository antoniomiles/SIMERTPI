package ec.gob.simertpi.application.notifications;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="simertpi.notifications.dispatcher.enabled", havingValue="true")
public class NotificationRetryScheduler {
    private final NotificationDeliveryService delivery;

    public NotificationRetryScheduler(NotificationDeliveryService delivery) { this.delivery = delivery; }

    @Scheduled(fixedDelayString = "${simertpi.notifications.retry.fixed-delay-ms:60000}")
    public void retryFailedChannels() {
        ec.gob.simertpi.application.reconciliation.ReconciliationSchedulers.runCorrelated(() -> delivery.retryDue(OffsetDateTime.now()));
    }
}
