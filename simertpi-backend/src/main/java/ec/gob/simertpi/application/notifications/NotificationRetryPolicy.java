package ec.gob.simertpi.application.notifications;
import ec.gob.simertpi.application.reconciliation.RecoveryConfiguration;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
@Component
public class NotificationRetryPolicy {
 private final RecoveryConfiguration config;
 public NotificationRetryPolicy(RecoveryConfiguration config){this.config=config;}
 public long maximumAttempts(){return config.positive("simertpi.outbox.max-attempts");}
 public OffsetDateTime nextAttempt(OffsetDateTime now,int attempts){return now.plusSeconds(Math.multiplyExact(config.positive("simertpi.outbox.backoff-seconds"),attempts));}
 public OffsetDateTime staleBefore(OffsetDateTime now){return now.minusSeconds(config.positive("simertpi.outbox.processing-timeout-seconds"));}
}
