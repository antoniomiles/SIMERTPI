package ec.gob.simertpi.application.operations;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import ec.gob.simertpi.application.reconciliation.ReconciliationResult;
import java.util.*;
@Aspect @Component
public class OperationalInstrumentation {
 private final OperationalMetrics metrics;private final Environment env;
 public OperationalInstrumentation(OperationalMetrics metrics,Environment env){this.metrics=metrics;this.env=env;}
 @Around("@annotation(org.springframework.scheduling.annotation.Scheduled) && within(ec.gob.simertpi.application..*)")
 public Object scheduled(ProceedingJoinPoint call)throws Throwable{
  String method=call.getSignature().getName();String name;String enabled=null;
  switch(method){
   case "evaluateParkingControls":name="PARKING_CONTROL";break;
   case "expirePendingPayments":name="PENDING_PAYMENT";break;
   case "payments":name="PAYMENT_RECONCILIATION";enabled="simertpi.reconciliation.payments.enabled";break;
   case "evidence":name="EVIDENCE_RECONCILIATION";enabled="simertpi.reconciliation.evidence.enabled";break;
   case "outbox":name="OUTBOX_RECOVERY";enabled="simertpi.reconciliation.outbox.enabled";break;
   case "retryFailedChannels":name="NOTIFICATION_RETRY";break;
   case "generateDueReminders":name="NOTIFICATION_REMINDERS";break;
   case "scheduledProcessing":name="NOTIFICATION_OUTBOX";enabled="simertpi.notifications.outbox.enabled";break;
   case "updatePermitLifecycle":name="PERMIT_EXPIRY";break;
   default:return call.proceed();
  }
  if(enabled!=null&&!env.getProperty(enabled,Boolean.class,false))return call.proceed();
  OperationalMetrics.ITEM_FAILURE.set(false);
  long start=System.nanoTime();String result="SUCCESS";
  String previous=org.slf4j.MDC.get("correlationId");if(previous==null)org.slf4j.MDC.put("correlationId",UUID.randomUUID().toString());
  try{return call.proceed();}catch(Throwable failure){result="FAILED";throw failure;}
  finally{if(Boolean.TRUE.equals(OperationalMetrics.ITEM_FAILURE.get()))result="FAILED";OperationalMetrics.ITEM_FAILURE.remove();metrics.scheduler(name,result,System.nanoTime()-start);if(previous==null)org.slf4j.MDC.remove("correlationId");}
 }
 @Around("execution(* ec.gob.simertpi.application.reconciliation.PaymentReconciliationService.reconcile(..)) || execution(* ec.gob.simertpi.application.reconciliation.EvidenceReconciliationService.reconcile(..)) || execution(* ec.gob.simertpi.application.notifications.NotificationOutboxProcessor.recoverStale(..))")
 public Object reconcile(ProceedingJoinPoint call)throws Throwable{
  String type=call.getTarget().getClass().getSimpleName().startsWith("Payment")?"PAYMENTS":call.getTarget().getClass().getSimpleName().startsWith("Evidence")?"EVIDENCE":"OUTBOX";
  try{Object result=call.proceed();long findings=result instanceof List<?> list?list.stream().filter(x->x instanceof ReconciliationResult r&&!"CONSISTENT".equals(r.status())).count():0;
   metrics.reconciliation(type,findings==0?"SUCCESS":"FINDINGS",findings);return result;
  }catch(Throwable failure){metrics.reconciliation(type,"FAILED",0);throw failure;}
 }
 @Around("execution(* ec.gob.simertpi.application.payments.webhook.PaymentWebhookService.receive(..))")
 public Object webhook(ProceedingJoinPoint call)throws Throwable{metrics.event(OperationalMetrics.Event.WEBHOOK_RECEIVED);try{return call.proceed();}catch(Throwable failure){metrics.event(OperationalMetrics.Event.WEBHOOK_REJECTED);throw failure;}}
 @Around("execution(* ec.gob.simertpi.application.enforcement.EnforcementIdempotencyService.createEvidenceUpload(..))")
 public Object upload(ProceedingJoinPoint call)throws Throwable{try{return call.proceed();}catch(Throwable failure){metrics.event(OperationalMetrics.Event.EVIDENCE_UPLOAD_FAILED);throw failure;}}
}
