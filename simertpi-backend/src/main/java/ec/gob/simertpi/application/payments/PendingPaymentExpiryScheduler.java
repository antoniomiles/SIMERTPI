package ec.gob.simertpi.application.payments;
import ec.gob.simertpi.application.reconciliation.PaymentReconciliationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="simertpi.payments.pending-timeout.enabled",havingValue="true")
public class PendingPaymentExpiryScheduler {
 private final PaymentReconciliationService service;
 public PendingPaymentExpiryScheduler(PaymentReconciliationService service) {this.service=service;}
 @Scheduled(fixedDelayString="${simertpi.payments.pending-timeout.scan-delay-ms:60000}")
 public void expirePendingPayments() {ec.gob.simertpi.application.reconciliation.ReconciliationSchedulers.runCorrelated(service::recoverPending);}
}
