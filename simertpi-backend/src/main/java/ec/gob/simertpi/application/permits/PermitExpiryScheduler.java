package ec.gob.simertpi.application.permits;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class PermitExpiryScheduler {
    private final PermitService permits;

    public PermitExpiryScheduler(PermitService permits) {
        this.permits = permits;
    }

    @Scheduled(fixedDelayString = "${simertpi.permits.expiration.fixed-delay-ms:60000}")
    public void updatePermitLifecycle() {
        OffsetDateTime now = OffsetDateTime.now();
        permits.expireDue(now);
        permits.activateDue(now);
    }
}
