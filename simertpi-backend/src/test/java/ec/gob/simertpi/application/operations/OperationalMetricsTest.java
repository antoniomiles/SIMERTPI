package ec.gob.simertpi.application.operations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OperationalMetricsTest {
 SimpleMeterRegistry registry=new SimpleMeterRegistry();OperationalMetrics metrics=new OperationalMetrics(registry);
 @ParameterizedTest @EnumSource(OperationalMetrics.Event.class) void functionalEventIsInstrumented(OperationalMetrics.Event event){metrics.event(event);assertThat(registry.getMeters()).hasSize(1);assertThat(registry.getMeters().getFirst().getId().getTags()).isEmpty();}
 @ParameterizedTest @CsvSource({"PAYMENT_APPROVED,payments.approved","PAYMENT_DECLINED,payments.declined","PAYMENT_FAILED,payments.failed","EVIDENCE_CREATED,evidence.uploaded","EVIDENCE_DOWNLOADED,evidence.downloaded","EVIDENCE_INCONSISTENCY_DETECTED,evidence.reconciliation_findings","NOTIFICATION_DELIVERED,notifications.delivered","NOTIFICATION_PERMANENT_FAILURE,notifications.permanent_failure","NOTIFICATION_RETRY_EXHAUSTED,notifications.dead"})
 void committedAuditEventIsInstrumented(String action,String suffix){metrics.auditEvent(action,"SUCCESS",Map.of("secret","fixture-secret","userId",UUID.randomUUID(),"providerCode",UUID.randomUUID().toString()));assertThat(registry.get("simertpi."+suffix).counter().count()).isEqualTo(1);assertThat(registry.getMeters().getFirst().getId().getTags()).isEmpty();}
 @Test void manualReviewIsCounted(){metrics.auditEvent("PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED","SUCCESS",Map.of());assertThat(registry.get("simertpi.payments.manual_review").counter().count()).isEqualTo(1);}
 @Test void rollbackDoesNotCountBusinessSuccess(){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);try{metrics.event(OperationalMetrics.Event.SESSION_CREATED);assertThat(registry.getMeters()).isEmpty();for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(1);assertThat(registry.getMeters()).isEmpty();}finally{TransactionSynchronizationManager.clear();}}
 @Test void commitCountsOnce(){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);try{metrics.event(OperationalMetrics.Event.SESSION_CREATED);for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();assertThat(registry.get("simertpi.parking.sessions.created").counter().count()).isEqualTo(1);}finally{TransactionSynchronizationManager.clear();}}
 @ParameterizedTest @ValueSource(strings={"SUCCESS","FINDINGS","FAILED"}) void reconciliationOutcomes(String result){
  var target=org.mockito.Mockito.mock(ec.gob.simertpi.application.reconciliation.EvidenceReconciliationService.class);
  var factory=new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);factory.addAspect(new OperationalInstrumentation(metrics,new org.springframework.mock.env.MockEnvironment()));
  ec.gob.simertpi.application.reconciliation.EvidenceReconciliationService proxy=factory.getProxy();
  if("FAILED".equals(result)){org.mockito.Mockito.when(target.reconcile()).thenThrow(new IllegalStateException("fixture-secret"));assertThatThrownBy(proxy::reconcile).isInstanceOf(IllegalStateException.class);}
  else{org.mockito.Mockito.when(target.reconcile()).thenReturn("FINDINGS".equals(result)?List.of(new ec.gob.simertpi.application.reconciliation.ReconciliationResult("EVIDENCE",UUID.randomUUID(),java.time.OffsetDateTime.now(),"HASH_MISMATCH","MANUAL_REVIEW_REQUIRED","NONE",true,null)):List.of());proxy.reconcile();}
  assertThat(registry.get("simertpi.reconciliation.runs").tags("type","EVIDENCE","result",result).counter().count()).isEqualTo(1);
 }
 @ParameterizedTest @ValueSource(strings={"SUCCESS","FAILED"}) void schedulerHasCountAndDuration(String result){
  var delivery=org.mockito.Mockito.mock(ec.gob.simertpi.application.notifications.NotificationDeliveryService.class);
  var target=new ec.gob.simertpi.application.notifications.NotificationRetryScheduler(delivery);
  var factory=new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);factory.addAspect(new OperationalInstrumentation(metrics,new org.springframework.mock.env.MockEnvironment()));
  ec.gob.simertpi.application.notifications.NotificationRetryScheduler proxy=factory.getProxy();
  if("FAILED".equals(result)){org.mockito.Mockito.doThrow(new IllegalStateException("fixture-secret")).when(delivery).retryDue(org.mockito.ArgumentMatchers.any());assertThatThrownBy(proxy::retryFailedChannels).isInstanceOf(IllegalStateException.class);}else proxy.retryFailedChannels();
  assertThat(registry.get("simertpi.scheduler.runs").tags("scheduler","NOTIFICATION_RETRY","result",result).counter().count()).isEqualTo(1);
  assertThat(registry.get("simertpi.scheduler.duration").tags("scheduler","NOTIFICATION_RETRY","result",result).timer().count()).isEqualTo(1);
  assertThat(org.slf4j.MDC.get("correlationId")).isNull();assertThat(OperationalMetrics.ITEM_FAILURE.get()).isNull();
 }
 @Test void dynamicTagsRejected(){assertThatThrownBy(()->metrics.scheduler(UUID.randomUUID().toString(),"SUCCESS",1)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->metrics.reconciliation("secret@example.test","SUCCESS",0)).isInstanceOf(IllegalArgumentException.class);assertThat(registry.getMeters()).isEmpty();}
}
