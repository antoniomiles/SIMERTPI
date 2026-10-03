package ec.gob.simertpi.application.payments.webhook;

import org.springframework.stereotype.Component;

@Component
public class ClosedPaymentWebhookVerifier implements PaymentWebhookVerifier {

    @Override
    public boolean verify(String provider, String eventId, String timestamp,
                          String signature, String payload) {
        return false;
    }
}
