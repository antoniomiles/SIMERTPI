package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.api.*;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.reconciliation.ReconciliationFindings;
import ec.gob.simertpi.domain.payments.entity.Payment;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable prepare/dispatch/apply phases. Network calls never hold a DB transaction. */
@Service
public class PaymentIntegrationService {
 private final CreatePaymentService creation;private final PaymentService payments;
 private final PaymentProviderRegistry registry;private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;private final AuditService audit;private final ReconciliationFindings findings;
 public PaymentIntegrationService(CreatePaymentService creation,PaymentService payments,PaymentProviderRegistry registry,
   JdbcTemplate jdbc,PlatformTransactionManager manager,AuditService audit,ReconciliationFindings findings) {
  this.creation=creation;this.payments=payments;this.registry=registry;this.jdbc=jdbc;
  this.tx=new TransactionTemplate(manager);this.audit=audit;this.findings=findings;
 }
 @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 public Payment create(String username,String key,UUID sessionId,String method) {
  PaymentProvider provider=registry.selected();
  Payment payment=creation.create(username,key,sessionId,method);
  PaymentProviderRequest request=tx.execute(s -> {
   lock(payment.getId());
   var current=payments.findById(payment.getId());
   if(!"NOT_STARTED".equals(current.getProviderOperationStatus()) || !"PENDING".equals(current.getStatus()))return null;
   if(!"UNCONFIGURED".equals(current.getProvider()) && !provider.providerCode().equals(current.getProvider()))throw new IdempotencyConflictException();
   UUID operation=jdbc.queryForObject("SELECT id FROM payments.payment_attempts WHERE payment_id=? ORDER BY attempt_number DESC LIMIT 1",UUID.class,payment.getId());
   jdbc.update("UPDATE payments.payments SET provider=?,provider_operation_key=?,provider_operation_status='REQUESTED',status='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE id=?",provider.providerCode(),operation,payment.getId());
   audit.success("PAYMENT_PROVIDER_REQUESTED","PAYMENT",payment.getId(),null,Map.of("provider",provider.providerCode()));
   return request(current,operation);
  });
  if(request==null)return payments.findById(payment.getId());
  PaymentProviderResult response;
  try {response=provider.createPayment(request);}catch(RuntimeException unknown) {
   tx.executeWithoutResult(s -> {lock(payment.getId());jdbc.update("UPDATE payments.payments SET provider_operation_status='UNKNOWN' WHERE id=? AND provider_operation_status='REQUESTED'",payment.getId());findings.report("PAYMENT",payment.getId(),"PROVIDER_RESULT_UNKNOWN","MANUAL_REVIEW_REQUIRED","QUERY_REQUIRED","PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED");});
   return payments.findById(payment.getId());
  }
  return apply(provider.providerCode(),payment.getId(),response,request.idempotencyKey());
 }
 public Payment owned(String username,UUID id) {
  Boolean owner=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments.payments p JOIN parking.parking_sessions s ON s.id=p.parking_session_id JOIN identity.users u ON u.id=s.user_id WHERE p.id=? AND u.username=?)",Boolean.class,id,username);
  if(!Boolean.TRUE.equals(owner))throw new ForbiddenException("Payment access denied");
  return payments.findById(id);
 }
 @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 public Payment refresh(String username,UUID id) {
  var payment=owned(username,id);var provider=registry.resolve(payment.getProvider());
  if(payment.getProviderOperationKey()==null)throw new PaymentProviderUnavailableException();
  PaymentProviderResult result;
  try {result=provider.queryPayment(payment.getProviderPaymentId(),request(payment,payment.getProviderOperationKey()));}
  catch(RuntimeException unavailable) {throw new PaymentProviderUnavailableException();}
  return apply(payment.getProvider(),id,result,payment.getProviderOperationKey());
 }
 public Payment apply(String provider,UUID id,PaymentProviderResult response) {
  return apply(provider,id,response,null);
 }
 public Payment apply(String provider,UUID id,PaymentProviderResult response,UUID expectedOperation) {
  return tx.execute(s -> {
   lock(id);Payment payment=payments.findById(id);
   if(!provider.equals(payment.getProvider()))throw new ForbiddenException("Payment provider mismatch");
   if(expectedOperation!=null && !expectedOperation.equals(payment.getProviderOperationKey())) {recordStale(id,expectedOperation,response);return payment;}
   if(response==null || response.status()==null) {
    jdbc.update("UPDATE payments.payments SET provider_operation_status='UNKNOWN' WHERE id=?",id);
    findings.report("PAYMENT",id,"PROVIDER_STATUS_UNKNOWN","MANUAL_REVIEW_REQUIRED","NONE","PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED");return payment;
   }
   validateReference(response.providerPaymentId());validateReference(response.reference());


   UUID operation=expectedOperation;
   if(response.providerPaymentId()!=null) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",provider+":"+response.providerPaymentId());
    var bound=jdbc.queryForList("SELECT a.id,a.payment_id FROM payments.payment_attempts a JOIN payments.payments p ON p.id=a.payment_id WHERE p.provider=? AND a.provider_transaction_id=?",provider,response.providerPaymentId());
    if(!bound.isEmpty()) {
     UUID previous=(UUID)bound.getFirst().get("id");
     if(!id.equals(bound.getFirst().get("payment_id")))throw new IdempotencyConflictException();
     if(operation==null)operation=previous;
     else if(!operation.equals(previous))throw new IdempotencyConflictException();
    }
   }
   if(operation!=null && !operation.equals(payment.getProviderOperationKey())) {
    recordStale(id,operation,response);
    return payment;
   }
   if(expectedOperation==null && operation==null)throw new IdempotencyConflictException();
   if(response.providerPaymentId()!=null && payment.getProviderPaymentId()!=null && !response.providerPaymentId().equals(payment.getProviderPaymentId()))throw new IdempotencyConflictException();
   if(response.providerPaymentId()!=null)jdbc.update("UPDATE payments.payments SET provider_payment_id=? WHERE id=?",response.providerPaymentId(),id);
   if(response.status()==ProviderPaymentStatus.UNKNOWN) {
    if(response.providerPaymentId()!=null)jdbc.update("UPDATE payments.payment_attempts SET provider_transaction_id=? WHERE id=? AND payment_id=?",response.providerPaymentId(),payment.getProviderOperationKey(),id);
    jdbc.update("UPDATE payments.payments SET provider_operation_status='UNKNOWN' WHERE id=?",id);
    findings.report("PAYMENT",id,"PROVIDER_STATUS_UNKNOWN","MANUAL_REVIEW_REQUIRED","NONE","PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED");
    return reload(id);
   }
   String target=response.status().name();String current=payment.getStatus();
   boolean terminal=Set.of("APPROVED","DECLINED","FAILED","REFUNDED","CANCELLED").contains(current);
   if("APPROVED".equals(target) && "CANCELLED".equals(jdbc.queryForObject("SELECT status FROM parking.parking_sessions WHERE id=?",String.class,payment.getParkingSessionId())))terminal=true;
   if(!terminal && !current.equals(target)
       && !ec.gob.simertpi.domain.payments.entity.PaymentStatus.fromCode(current)
           .canTransitionTo(ec.gob.simertpi.domain.payments.entity.PaymentStatus.fromCode(target)))
    throw new IdempotencyConflictException();
   if(terminal) {
    if(!current.equals(target) && !"PENDING".equals(target)) {
     jdbc.update("UPDATE payments.payment_attempts SET provider_transaction_id=?,response_code=?,response_message=? WHERE id=(SELECT id FROM payments.payment_attempts WHERE payment_id=? ORDER BY attempt_number DESC LIMIT 1)",response.providerPaymentId(),"APPROVED".equals(target)?"LATE_APPROVAL":"STATE_CONFLICT","Provider reference: "+String.valueOf(response.reference()),id);
     findings.report("PAYMENT",id,"APPROVED".equals(target)?"LATE_PROVIDER_APPROVAL":"PROVIDER_TERMINAL_CONFLICT","MANUAL_REVIEW_REQUIRED","NONE","APPROVED".equals(target)?"PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED":"PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED");
    }
   } else if("PENDING".equals(target)) {
    jdbc.update("UPDATE payments.payments SET status='PENDING' WHERE id=?",id);
    if(!"CONFIRMED".equals(payment.getProviderOperationStatus()))audit.success("PAYMENT_PROVIDER_PENDING","PAYMENT",id,null,Map.of());
   } else if("APPROVED".equals(target)) {
    if(response.reference()==null || response.reference().isBlank())throw new IdempotencyConflictException();
    payments.approve(id,response.reference());
   } else if("DECLINED".equals(target))payments.decline(id,"Provider declined payment");
   else if("FAILED".equals(target))payments.fail(id,"Provider failed payment");
   else if("CANCELLED".equals(target)) {
    jdbc.update("UPDATE payments.payments SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
    jdbc.update("UPDATE parking.parking_sessions SET status='CANCELLED',ended_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING_PAYMENT'",payment.getParkingSessionId());
    jdbc.update("UPDATE payments.payment_attempts SET status='CANCELLED',response_code='PROVIDER_CANCELLED' WHERE id=(SELECT id FROM payments.payment_attempts WHERE payment_id=? ORDER BY attempt_number DESC LIMIT 1)",id);
    audit.success("PAYMENT_PROVIDER_CANCELLED","PAYMENT",id,null,Map.of());
   }
   em.flush();
   if(response.providerPaymentId()!=null)
    jdbc.update("UPDATE payments.payment_attempts SET provider_transaction_id=? WHERE id=? AND payment_id=?",response.providerPaymentId(),payment.getProviderOperationKey(),id);
   jdbc.update("UPDATE payments.payments SET provider_operation_status='CONFIRMED' WHERE id=?",id);
   // PaymentService may have loaded the entity before JDBC writes; refresh it before returning.
   return reload(id);
  });
 }
 @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
 private Payment reload(UUID id) {Payment payment=payments.findById(id);em.refresh(payment);return payment;}
 private void lock(UUID id) {
  UUID session=jdbc.query("SELECT parking_session_id FROM payments.payments WHERE id=?",rs -> rs.next()?rs.getObject(1,UUID.class):null,id);
  if(session==null)throw new ResourceNotFoundException("Payment not found");
  jdbc.queryForList("SELECT id FROM parking.parking_sessions WHERE id=? FOR UPDATE",session);
  jdbc.queryForList("SELECT id FROM payments.payments WHERE id=? FOR UPDATE",id);
 }
 private PaymentProviderRequest request(Payment payment,UUID operation) {
  return new PaymentProviderRequest(payment.getId(),payment.getParkingSessionId(),payment.getAmount(),payment.getCurrency(),"SIMERTPI-"+payment.getId(),operation,MDC.get("correlationId"));
 }
 private void recordStale(UUID id,UUID operation,PaymentProviderResult response) {
  ProviderPaymentStatus status=response==null || response.status()==null?ProviderPaymentStatus.UNKNOWN:response.status();
  String reference=response==null?null:response.reference();validateReference(reference);
  jdbc.update("UPDATE payments.payment_attempts SET response_code=?,response_message=? WHERE id=? AND payment_id=?",
     status==ProviderPaymentStatus.APPROVED?"LATE_APPROVAL":"STATE_CONFLICT","Provider reference: "+String.valueOf(reference),operation,id);
  findings.report("PAYMENT",id,"STALE_PROVIDER_"+status+"_"+operation,"MANUAL_REVIEW_REQUIRED","NONE",
     status==ProviderPaymentStatus.APPROVED?"PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED":"PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED");
 }
 private void validateReference(String ref) {
  if(ref!=null && (ref.length()>150 || ref.codePoints().anyMatch(Character::isISOControl)))throw new IdempotencyConflictException();
 }
}
