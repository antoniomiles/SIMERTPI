package ec.gob.simertpi.api.parking.extension.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSessionExtensionRequest(

        @NotNull
        @Min(1)
        Integer additionalMinutes,

        @NotBlank
        String provider,

        @NotBlank
        String paymentMethod,

        @NotBlank
        String idempotencyKey
) {
}