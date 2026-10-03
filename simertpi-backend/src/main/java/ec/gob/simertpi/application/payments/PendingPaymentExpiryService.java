package ec.gob.simertpi.application.payments;
import ec.gob.simertpi.application.reconciliation.PaymentReconciliationService;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.OffsetDateTime;
/** Compatibility facade; recovery has one implementation and one locking strategy. */
@Service
public class PendingPaymentExpiryService {
 private final PaymentReconciliationService reconciliation;
 public PendingPaymentExpiryService(PaymentReconciliationService reconciliation) {this.reconciliation=reconciliation;}
 public int cancelExpired(Duration timeout,OffsetDateTime now) {return reconciliation.cancelExpired(timeout,now);}
}
