package ec.gob.simertpi.application.payments.webhook;

public interface PaymentWebhookVerifier {

    boolean verify(String provider, String eventId, String timestamp,
                   String signature, String payload);
}
