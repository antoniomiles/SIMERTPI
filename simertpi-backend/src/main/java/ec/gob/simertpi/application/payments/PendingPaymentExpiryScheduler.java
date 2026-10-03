package ec.gob.simertpi.application.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name = "simertpi.payments.pending-timeout.enabled", havingValue = "true")
public class PendingPaymentExpiryScheduler {

    private final PendingPaymentExpiryService expiryService;
    private final Duration timeout;

    public PendingPaymentExpiryScheduler(PendingPaymentExpiryService expiryService,
                                         @Value("${simertpi.payments.pending-timeout-ms}") long timeoutMs) {
        this.expiryService = expiryService;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Scheduled(fixedDelayString = "${simertpi.payments.pending-timeout.scan-delay-ms:60000}")
    public void expirePendingPayments() {
        expiryService.cancelExpired(timeout, OffsetDateTime.now());
    }
}
