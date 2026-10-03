package ec.gob.simertpi.application.payments;
import ec.gob.simertpi.application.reconciliation.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PendingPaymentExpiryServiceTest {
 @Test void delegatesRecoveryToTheSingleImplementation() {
  var core=mock(PaymentReconciliationService.class);var service=new PendingPaymentExpiryService(core);
  var now=OffsetDateTime.now();when(core.cancelExpired(Duration.ofMinutes(30),now)).thenReturn(1);
  assertEquals(1,service.cancelExpired(Duration.ofMinutes(30),now));verify(core).cancelExpired(Duration.ofMinutes(30),now);
 }
 @Test void requiresExplicitPositiveTimeoutConfiguration() {
  var config=new RecoveryConfiguration(new MockEnvironment());
  assertThrows(IllegalStateException.class,()->config.positive("simertpi.payments.pending-timeout.minutes"));
 }
}
