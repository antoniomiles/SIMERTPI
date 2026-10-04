package ec.gob.simertpi.application.payments.webhook;
import java.util.*;
public interface PaymentWebhookVerifier {
 boolean supports(String provider);
 Optional<VerifiedPaymentEvent> verify(String provider, Map<String,List<String>> headers, byte[] rawBody);
}
