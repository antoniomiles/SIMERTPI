package ec.gob.simertpi.application.payments.webhook;
import ec.gob.simertpi.application.payments.PaymentProviderResult;
import java.util.UUID;
/** Produced only after verifying raw bytes and binding the provider's correlation to SIMERTPI. */
public record VerifiedPaymentEvent(String externalEventId, UUID paymentId, PaymentProviderResult result,
                                   UUID providerOperationKey) {
 public VerifiedPaymentEvent(String externalEventId,UUID paymentId,PaymentProviderResult result) {
  this(externalEventId,paymentId,result,null);
 }
}
