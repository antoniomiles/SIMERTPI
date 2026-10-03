package ec.gob.simertpi.api.payments.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull UUID parkingSessionId,

        @NotBlank @Size(max = 50) String paymentMethod
) {}
