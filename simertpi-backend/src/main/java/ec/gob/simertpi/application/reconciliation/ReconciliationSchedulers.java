package ec.gob.simertpi.application.reconciliation;

import ec.gob.simertpi.application.notifications.NotificationOutboxProcessor;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ReconciliationSchedulers {
    private final RecoveryConfiguration config;
    private final PaymentReconciliationService payments;
    private final EvidenceReconciliationService evidence;
    private final NotificationOutboxProcessor outbox;

    public ReconciliationSchedulers(RecoveryConfiguration config, PaymentReconciliationService payments,
                                    EvidenceReconciliationService evidence, NotificationOutboxProcessor outbox) {
        this.config = config;
        this.payments = payments;
        this.evidence = evidence;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${simertpi.reconciliation.payments.fixed-delay-ms:60000}")
    public void payments() {
        if (config.enabled("simertpi.reconciliation.payments.enabled")) runCorrelated(payments::reconcile);
    }

    @Scheduled(fixedDelayString = "${simertpi.reconciliation.evidence.fixed-delay-ms:300000}")
    public void evidence() {
        if (config.enabled("simertpi.reconciliation.evidence.enabled")) runCorrelated(evidence::reconcile);
    }

    @Scheduled(fixedDelayString = "${simertpi.reconciliation.outbox.fixed-delay-ms:60000}")
    public void outbox() {
        if (config.enabled("simertpi.reconciliation.outbox.enabled")) runCorrelated(outbox::recoverStale);
    }

    public static void runCorrelated(Runnable operation) {
        String previous = MDC.get("correlationId");
        if (previous == null) MDC.put("correlationId", UUID.randomUUID().toString());
        try {
            operation.run();
        } finally {
            if (previous == null) MDC.remove("correlationId");
        }
    }
}
