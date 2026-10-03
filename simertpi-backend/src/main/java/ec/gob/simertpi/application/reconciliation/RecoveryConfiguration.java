package ec.gob.simertpi.application.reconciliation;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RecoveryConfiguration {
    private final Environment env;

    public RecoveryConfiguration(Environment env) {
        this.env = env;
    }

    @PostConstruct
    public void validateEnabledRecovery() {
        if (enabled("simertpi.payments.pending-timeout.enabled")) {
            positive("simertpi.payments.pending-timeout.minutes");
        }
        if (enabled("simertpi.notifications.outbox.enabled")
                || enabled("simertpi.reconciliation.outbox.enabled")) {
            positive("simertpi.outbox.max-attempts");
            positive("simertpi.outbox.backoff-seconds");
            positive("simertpi.outbox.processing-timeout-seconds");
        }
    }

    public boolean enabled(String key) {
        return env.getProperty(key, Boolean.class, false);
    }

    public long positive(String key) {
        Long value = env.getProperty(key, Long.class);
        if (value == null || value <= 0) {
            throw new IllegalStateException("Positive recovery configuration required: " + key);
        }
        return value;
    }
}
