package ec.gob.simertpi.application.reconciliation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
@Service
public class PaymentReconciliationService {
 private final JdbcTemplate jdbc; private final TransactionTemplate tx;
 private final ReconciliationFindings findings; private final RecoveryConfiguration config;
 public PaymentReconciliationService(JdbcTemplate jdbc,PlatformTransactionManager manager,ReconciliationFindings findings,RecoveryConfiguration config) {
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.findings=findings;this.config=config;
 }
 public int recoverPending() {
  if(!config.enabled("simertpi.payments.pending-timeout.enabled")) return 0;
  return cancelExpired(Duration.ofMinutes(config.positive("simertpi.payments.pending-timeout.minutes")),OffsetDateTime.now());
 }
 public int cancelExpired(Duration timeout,OffsetDateTime now) {
  if(timeout==null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required");
  OffsetDateTime cutoff=now.minus(timeout); int count=0;
  for(UUID id:jdbc.queryForList("SELECT id FROM parking.parking_sessions WHERE status='PENDING_PAYMENT' AND created_at<=? ORDER BY id",UUID.class,cutoff)) {
   Boolean changed=tx.execute(s -> {
    var sessions=jdbc.queryForList("SELECT * FROM parking.parking_sessions WHERE id=? FOR UPDATE",id);
    if(sessions.isEmpty() || !"PENDING_PAYMENT".equals(sessions.getFirst().get("status"))) return false;
    var payments=paymentsForUpdate(id);
    if(payments.stream().anyMatch(p -> "APPROVED".equals(p.get("status")))) return false;
    // A newly initiated attempt retains its configured time window.
    if(payments.stream().anyMatch(p -> Set.of("PENDING","PROCESSING").contains(p.get("status")) && ((java.sql.Timestamp)p.get("last_activity_at")).toInstant().isAfter(cutoff.toInstant()))) return false;
    for(var payment:payments) if(Set.of("PENDING","PROCESSING").contains(payment.get("status"))) {
     UUID paymentId=(UUID)payment.get("id");
     jdbc.update("UPDATE payments.payments SET status='CANCELLED',failure_reason='PAYMENT_PENDING_TIMEOUT',updated_at=? WHERE id=?",now,paymentId);
     jdbc.update("UPDATE payments.payment_attempts SET status='CANCELLED',response_code='TIMEOUT',response_message='Payment attempt timed out' WHERE id=(SELECT id FROM payments.payment_attempts WHERE payment_id=? ORDER BY attempt_number DESC LIMIT 1)",paymentId);
     jdbc.update("INSERT INTO audit.outbox_events(id,aggregate_type,aggregate_id,event_type,payload,occurred_at) VALUES(?,'PAYMENT',?,'PAYMENT_CANCELLED_TIMEOUT','{}',?)",UUID.randomUUID(),paymentId,now);
     findings.report("PAYMENT",paymentId,"PAYMENT_PENDING_EXPIRED","AUTO_RECOVERABLE","CANCELLED","PAYMENT_PENDING_TIMEOUT");
    }
    jdbc.update("UPDATE parking.parking_sessions SET status='CANCELLED',ended_at=?,updated_at=? WHERE id=?",now,now,id);
    findings.report("PARKING_SESSION",id,"SESSION_PENDING_EXPIRED","AUTO_RECOVERABLE","CANCELLED","SESSION_CANCELLED_BY_PAYMENT_TIMEOUT");
    return true;
   });
   if(Boolean.TRUE.equals(changed))count++;
  }
  return count;
 }
 public List<ReconciliationResult> reconcile() {
  List<ReconciliationResult> result=new ArrayList<>();
  for(UUID id:jdbc.queryForList("SELECT id FROM parking.parking_sessions ORDER BY id",UUID.class)) {
   var item=tx.execute(s -> reconcileOne(id)); if(item!=null)result.add(item);
  }
  return result;
 }
 private List<Map<String,Object>> paymentsForUpdate(UUID id) {
  return jdbc.queryForList("""
   SELECT p.*,
     EXISTS(SELECT 1 FROM parking.session_extensions e WHERE e.payment_id=p.id) AS extension_payment,
     GREATEST(p.created_at, COALESCE((SELECT MAX(a.created_at) FROM payments.payment_attempts a
       WHERE a.payment_id=p.id), p.created_at)) AS last_activity_at
   FROM payments.payments p WHERE parking_session_id=? ORDER BY id FOR UPDATE OF p
   """,id);
 }
 private ReconciliationResult reconcileOne(UUID id) {
  var rows=jdbc.queryForList("SELECT * FROM parking.parking_sessions WHERE id=? FOR UPDATE",id);
  if(rows.isEmpty()) return null;
  var session=rows.getFirst(); String state=(String)session.get("status");
  var payments=paymentsForUpdate(id);
  var approved=payments.stream().filter(p -> "APPROVED".equals(p.get("status")) && !Boolean.TRUE.equals(p.get("extension_payment"))).toList();
  String issue="CONSISTENT",status="CONSISTENT",action="NONE";
  if(approved.size()>1) issue="MULTIPLE_APPROVED_PAYMENTS";
  else if(approved.size()==1 && "CANCELLED".equals(state)) issue="PAYMENT_APPROVED_SESSION_CANCELLED";
  else if(approved.size()==1 && "PENDING_PAYMENT".equals(state)) {
   var payment=approved.getFirst();
   boolean trustworthy=payment.get("paid_at")!=null && payment.get("provider_transaction_id")!=null && !payment.get("provider_transaction_id").toString().isBlank();
   boolean timedOut=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM audit.reconciliation_findings WHERE resource_type='PARKING_SESSION' AND resource_id=? AND issue_code='SESSION_PENDING_EXPIRED')",Boolean.class,id));
   boolean valid=trustworthy && !timedOut && session.get("ended_at")==null && ((java.sql.Timestamp)session.get("expected_end_at")).toInstant().isAfter(java.time.Instant.now())
    && payment.get("amount").equals(session.get("total_amount")) && payments.stream().noneMatch(p -> Set.of("PENDING","PROCESSING").contains(p.get("status")));
   issue="PAYMENT_APPROVED_SESSION_PENDING";
   if(valid) { jdbc.update("UPDATE parking.parking_sessions SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);status="AUTO_RECOVERABLE";action="SESSION_ACTIVATED"; }
  } else if(Set.of("ACTIVE","EXTENDED","EXPIRED","MAX_TIME_REACHED").contains(state) && approved.isEmpty()) issue="SESSION_ACTIVE_WITHOUT_APPROVED_PAYMENT";
  else if("PENDING_PAYMENT".equals(state) && config.enabled("simertpi.payments.pending-timeout.enabled")) {
   var cutoff=java.time.Instant.now().minus(Duration.ofMinutes(config.positive("simertpi.payments.pending-timeout.minutes")));
   if(((java.sql.Timestamp)session.get("created_at")).toInstant().isBefore(cutoff)
      && payments.stream().noneMatch(p -> Set.of("PENDING","PROCESSING").contains(p.get("status")) && ((java.sql.Timestamp)p.get("last_activity_at")).toInstant().isAfter(cutoff))) issue="PAYMENT_PENDING_EXPIRED";
  }
  else if("CANCELLED".equals(state) && payments.stream().anyMatch(p -> Set.of("PENDING","PROCESSING").contains(p.get("status")))) issue="PAYMENT_STATE_SESSION_STATE_MISMATCH";
  if(!"CONSISTENT".equals(issue) && "CONSISTENT".equals(status))status="MANUAL_REVIEW_REQUIRED";
  return findings.report("PARKING_SESSION",id,issue,status,action,"AUTO_RECOVERABLE".equals(status)?"PAYMENT_RECONCILIATION_RECOVERED":"PAYMENT_RECONCILIATION_MANUAL_REVIEW");
 }
}
