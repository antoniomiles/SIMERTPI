package ec.gob.simertpi.application.payments;
import ec.gob.simertpi.application.reconciliation.PaymentReconciliationService;
import ec.gob.simertpi.application.reconciliation.RecoveryConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
@Component
@ConditionalOnProperty(name="simertpi.payments.pending-timeout.enabled",havingValue="true")
public class PendingPaymentExpiryScheduler {
 private final PaymentReconciliationService service;
 private final PaymentIntegrationService payments;
 private final RecoveryConfiguration config;
 public PendingPaymentExpiryScheduler(PaymentReconciliationService service,PaymentIntegrationService payments,RecoveryConfiguration config) {
  this.service=service;this.payments=payments;this.config=config;
 }
 @Scheduled(fixedDelayString="${simertpi.payments.pending-timeout.scan-delay-ms:60000}")
 public void expirePendingPayments() {ec.gob.simertpi.application.reconciliation.ReconciliationSchedulers.runCorrelated(() -> {
  payments.recoverExpiredPending(Duration.ofMinutes(config.positive("simertpi.payments.pending-timeout.minutes")));
  service.recoverPending();
 });}
}
