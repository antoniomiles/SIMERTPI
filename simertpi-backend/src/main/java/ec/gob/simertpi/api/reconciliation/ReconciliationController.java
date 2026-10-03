package ec.gob.simertpi.api.reconciliation;
import ec.gob.simertpi.application.reconciliation.*;
import ec.gob.simertpi.application.notifications.NotificationOutboxProcessor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class ReconciliationController {
 private final PaymentReconciliationService payments;private final EvidenceReconciliationService evidence;private final NotificationOutboxProcessor outbox;
 public ReconciliationController(PaymentReconciliationService payments,EvidenceReconciliationService evidence,NotificationOutboxProcessor outbox) {this.payments=payments;this.evidence=evidence;this.outbox=outbox;}
 @ExceptionHandler({IllegalStateException.class,UnsupportedOperationException.class})
 public org.springframework.http.ResponseEntity<java.util.Map<String,String>> unavailable() {
  return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("code","RECONCILIATION_UNAVAILABLE"));
 }
 @PostMapping("/payments") public List<ReconciliationResult> payments() {payments.recoverPending();return payments.reconcile();}
 @PostMapping("/evidence") public List<ReconciliationResult> evidence() {return evidence.reconcile();}
 @PostMapping("/outbox") public List<ReconciliationResult> outbox() {return outbox.recoverStale();}
}
