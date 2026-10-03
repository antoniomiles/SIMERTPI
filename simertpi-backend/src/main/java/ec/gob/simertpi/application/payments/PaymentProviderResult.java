package ec.gob.simertpi.application.payments;

public record PaymentProviderResult(
        String status,
        String providerTransactionId,
        String responseCode,
        String responseMessage
) {
}
