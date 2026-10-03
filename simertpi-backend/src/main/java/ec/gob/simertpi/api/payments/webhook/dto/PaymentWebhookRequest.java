package ec.gob.simertpi.api.payments.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentWebhookRequest(

        @NotBlank
        String providerEventId,

        @NotBlank
        String eventType,

        @NotBlank
        String providerTransactionId,

        @NotNull
        UUID paymentId,

        @NotBlank
        String payload
) {
}
