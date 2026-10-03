package ec.gob.simertpi.application.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryConfigurationTest {
 @Test void disabledTimeoutNeedsNoImplicitMinutes() {
  var config=new RecoveryConfiguration(new MockEnvironment());
  config.validateEnabledRecovery();assertFalse(config.enabled("simertpi.payments.pending-timeout.enabled"));
 }
 @Test void enabledTimeoutWithoutMinutesFailsControlled() {
  var config=new RecoveryConfiguration(new MockEnvironment().withProperty("simertpi.payments.pending-timeout.enabled","true"));
  assertThrows(IllegalStateException.class,config::validateEnabledRecovery);
 }
 @Test void zeroMinutesIsRejected() {
  var config=new RecoveryConfiguration(new MockEnvironment().withProperty("simertpi.payments.pending-timeout.enabled","true").withProperty("simertpi.payments.pending-timeout.minutes","0"));
  assertThrows(IllegalStateException.class,config::validateEnabledRecovery);
 }
 @Test void explicitMinutesIsAccepted() {
  var config=new RecoveryConfiguration(new MockEnvironment().withProperty("simertpi.payments.pending-timeout.enabled","true").withProperty("simertpi.payments.pending-timeout.minutes","30"));
  config.validateEnabledRecovery();assertEquals(30,config.positive("simertpi.payments.pending-timeout.minutes"));
 }
 @Test void disabledSchedulersDoNotScanOrAudit() {
  var payments=mock(PaymentReconciliationService.class);var evidence=mock(EvidenceReconciliationService.class);
  var outbox=mock(ec.gob.simertpi.application.notifications.NotificationOutboxProcessor.class);
  var jobs=new ReconciliationSchedulers(new RecoveryConfiguration(new MockEnvironment()),payments,evidence,outbox);
  jobs.payments();jobs.evidence();jobs.outbox();verifyNoInteractions(payments,evidence,outbox);
 }
 @Test void scheduledRunsGenerateAndRestoreCorrelation() {
  org.slf4j.MDC.clear();ReconciliationSchedulers.runCorrelated(()->assertNotNull(org.slf4j.MDC.get("correlationId")));
  assertNull(org.slf4j.MDC.get("correlationId"));org.slf4j.MDC.put("correlationId","existing");
  try {ReconciliationSchedulers.runCorrelated(()->assertEquals("existing",org.slf4j.MDC.get("correlationId")));assertEquals("existing",org.slf4j.MDC.get("correlationId"));}finally{org.slf4j.MDC.clear();}
 }
 @Test void schedulerSwitchesAreIndependent() {
  var payments=mock(PaymentReconciliationService.class);var evidence=mock(EvidenceReconciliationService.class);
  var outbox=mock(ec.gob.simertpi.application.notifications.NotificationOutboxProcessor.class);
  var jobs=new ReconciliationSchedulers(new RecoveryConfiguration(new MockEnvironment().withProperty("simertpi.reconciliation.evidence.enabled","true")),payments,evidence,outbox);
  jobs.payments();jobs.evidence();jobs.outbox();verify(evidence).reconcile();verifyNoInteractions(payments,outbox);
 }
}
