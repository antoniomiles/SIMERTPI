package ec.gob.simertpi.application.payments;
public interface PaymentProvider {
 String providerCode();
 PaymentProviderResult createPayment(PaymentProviderRequest request);
 PaymentProviderResult queryPayment(String providerPaymentId, PaymentProviderRequest request);
 PaymentProviderResult cancelPayment(String providerPaymentId, PaymentProviderRequest request);
}
