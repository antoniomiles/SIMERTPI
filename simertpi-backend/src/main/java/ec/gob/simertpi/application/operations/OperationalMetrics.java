package ec.gob.simertpi.application.operations;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.function.Supplier;

/** Fixed names/tags only. No business identifiers or provider payloads enter the registry. */
@Component
public class OperationalMetrics {
 public static final ThreadLocal<Boolean> ITEM_FAILURE = new ThreadLocal<>();
 public static void itemFailure(){if(ITEM_FAILURE.get()!=null)ITEM_FAILURE.set(true);}
 private final MeterRegistry registry;
 private static final Map<String,String> EVENTS=Map.ofEntries(
  Map.entry("PAYMENT_RECONCILIATION_RECOVERED","parking.sessions.activated"),Map.entry("PAYMENT_CREATED","payments.created"),Map.entry("PAYMENT_APPROVED","payments.approved"),
  Map.entry("PAYMENT_DECLINED","payments.declined"),Map.entry("PAYMENT_FAILED","payments.failed"),
  Map.entry("PAYMENT_PROVIDER_PENDING","payments.pending"),
  Map.entry("EVIDENCE_CREATED","evidence.uploaded"),Map.entry("EVIDENCE_DOWNLOADED","evidence.downloaded"),
  Map.entry("EVIDENCE_INCONSISTENCY_DETECTED","evidence.reconciliation_findings"),
  Map.entry("PARKING_CONTROL_EXPIRATION","parking.sessions.expired"),Map.entry("PARKING_CONTROL_MAX_TIME_REACHED","parking.sessions.max_time_reached"),
  Map.entry("PARKING_SESSION_COMPLETED","parking.sessions.completed"),Map.entry("PARKING_SESSION_EXTENDED","parking.extensions.requested"),
  Map.entry("NOTIFICATION_DELIVERED","notifications.delivered"),Map.entry("NOTIFICATION_PERMANENT_FAILURE","notifications.permanent_failure"),
  Map.entry("NOTIFICATION_RETRY_EXHAUSTED","notifications.dead"),Map.entry("NOTIFICATION_DESTINATION_DISABLED","notifications.destination_disabled"));
 public OperationalMetrics(MeterRegistry registry){this.registry=registry;}
 public enum Event { SESSION_CREATED, SESSION_ACTIVATED, EXTENSION_APPROVED, NOTIFICATION_GENERATED, NOTIFICATION_TEMPORARY_FAILURE, NOTIFICATION_NO_DESTINATION, NOTIFICATION_RETRY, WEBHOOK_RECEIVED, WEBHOOK_REJECTED, EVIDENCE_UPLOAD_FAILED, EVIDENCE_INTEGRITY_FAILURE }
 public void event(Event event){
  String name=switch(event){
   case SESSION_CREATED->"parking.sessions.created";case SESSION_ACTIVATED->"parking.sessions.activated";case EXTENSION_APPROVED->"parking.extensions.approved";
   case NOTIFICATION_GENERATED->"notifications.generated";case NOTIFICATION_TEMPORARY_FAILURE->"notifications.temporary_failure";case NOTIFICATION_NO_DESTINATION->"notifications.no_destination";case NOTIFICATION_RETRY->"notifications.retry";
   case WEBHOOK_RECEIVED->"payments.webhooks.received";case WEBHOOK_REJECTED->"payments.webhooks.rejected";case EVIDENCE_UPLOAD_FAILED->"evidence.upload_failed";case EVIDENCE_INTEGRITY_FAILURE->"evidence.integrity_failure";};
  Runnable increment=()->registry.counter("simertpi."+name).increment();
  if(Set.of(Event.EVIDENCE_UPLOAD_FAILED,Event.EVIDENCE_INTEGRITY_FAILURE,Event.WEBHOOK_RECEIVED,Event.WEBHOOK_REJECTED).contains(event))increment.run();else committed(increment);
 }
 public void auditEvent(String action,String result,Map<String,?> metadata){
  String suffix=EVENTS.get(action);
  if(suffix!=null && ("SUCCESS".equals(result)||action.startsWith("NOTIFICATION_")||action.equals("PAYMENT_WEBHOOK_REJECTED"))) committed(()->registry.counter("simertpi."+suffix).increment());
  if(action.startsWith("PAYMENT_")&&(action.endsWith("REVIEW_REQUIRED")||action.equals("PAYMENT_RECONCILIATION_MANUAL_REVIEW"))) committed(()->registry.counter("simertpi.payments.manual_review").increment());
 }
 public void reconciliation(String type,String result,long findings){
  if(!Set.of("PAYMENTS","EVIDENCE","OUTBOX").contains(type)||!Set.of("SUCCESS","FINDINGS","FAILED").contains(result))throw new IllegalArgumentException("Invalid operational tag");
  registry.counter("simertpi.reconciliation.runs","type",type,"result",result).increment();
  registry.counter("simertpi.reconciliation.findings","type",type).increment(findings);
  if("PAYMENTS".equals(type))registry.counter("simertpi.payments.reconciliation.findings").increment(findings);
 }
 public void scheduler(String name,String result,long nanos){
  if(!Set.of("PARKING_CONTROL","PENDING_PAYMENT","PAYMENT_RECONCILIATION","EVIDENCE_RECONCILIATION","OUTBOX_RECOVERY","NOTIFICATION_RETRY","NOTIFICATION_REMINDERS","NOTIFICATION_OUTBOX","PERMIT_EXPIRY").contains(name)||!Set.of("SUCCESS","FAILED").contains(result))throw new IllegalArgumentException("Invalid scheduler tag");
  registry.counter("simertpi.scheduler.runs","scheduler",name,"result",result).increment();
  registry.timer("simertpi.scheduler.duration","scheduler",name,"result",result).record(nanos,java.util.concurrent.TimeUnit.NANOSECONDS);
  LoggerFactory.getLogger(OperationalMetrics.class).info("event=scheduler_run result={} scheduler={}",result,name);
 }
 public static void committed(Runnable update){
  if(TransactionSynchronizationManager.isSynchronizationActive()&&TransactionSynchronizationManager.isActualTransactionActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){update.run();}});
  else update.run();
 }
}
