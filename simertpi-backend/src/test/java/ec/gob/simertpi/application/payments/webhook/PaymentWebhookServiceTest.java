package ec.gob.simertpi.application.payments.webhook;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class PaymentWebhookServiceTest {
 @Test void closedVerifierNeverTrustsBodyOrHeaders() {
  var verifier=new ClosedPaymentWebhookVerifier();assertTrue(verifier.verify("SANDBOX_STUB",Map.of(),"approved".getBytes()).isEmpty());
 }
 @Test void closedVerifierDoesNotClaimAnyProvider() {assertFalse(new ClosedPaymentWebhookVerifier().supports("BANCO_PICHINCHA"));}
}
