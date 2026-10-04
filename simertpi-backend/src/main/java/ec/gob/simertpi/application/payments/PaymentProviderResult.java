package ec.gob.simertpi.application.payments;
/** No raw responses or provider-specific transport data cross this boundary. */
public record PaymentProviderResult(ProviderPaymentStatus status, String providerPaymentId, String reference) { }
