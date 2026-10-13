package ec.gob.simertpi.application.parking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Bounds abandoned checkouts that were never sent to a payment provider. */
@Component
public class UnstartedCheckoutCleanupScheduler {
    private final JdbcTemplate jdbc;
    private final AbandonUnpaidCheckoutService checkouts;
    private final long timeoutMinutes;

    public UnstartedCheckoutCleanupScheduler(JdbcTemplate jdbc,
                                             AbandonUnpaidCheckoutService checkouts,
                                             @Value("${simertpi.payments.unstarted-checkout-timeout-minutes:10}") long timeoutMinutes) {
        if (timeoutMinutes < 1) throw new IllegalArgumentException("Checkout timeout must be positive");
        this.jdbc = jdbc;
        this.checkouts = checkouts;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Scheduled(fixedDelayString = "${simertpi.payments.unstarted-checkout-scan-delay-ms:60000}")
    public void expireAbandonedCheckouts() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(timeoutMinutes);
        var ids = jdbc.queryForList("""
                SELECT s.id FROM parking.parking_sessions s
                WHERE s.status='PENDING_PAYMENT' AND s.created_at<=?
                  AND NOT EXISTS (SELECT 1 FROM payments.payments p WHERE p.parking_session_id=s.id)
                ORDER BY s.created_at,s.id LIMIT 200
                """, UUID.class, cutoff);
        for (UUID id : ids) {
            try {
                checkouts.expireUnstarted(id, cutoff);
            } catch (RuntimeException failure) {
                // A later pass can retry; never release uncertain/payment-bearing sessions here.
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("event=unstarted_checkout_cleanup result=FAILED");
            }
        }
    }
}
