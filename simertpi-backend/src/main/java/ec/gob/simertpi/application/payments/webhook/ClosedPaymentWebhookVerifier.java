package ec.gob.simertpi.application.payments.webhook;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class ClosedPaymentWebhookVerifier implements PaymentWebhookVerifier {
 public boolean supports(String provider) {return false;}
 public Optional<VerifiedPaymentEvent> verify(String provider,Map<String,List<String>> headers,byte[] rawBody) {return Optional.empty();}
}
