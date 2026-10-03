package ec.gob.simertpi.api.enforcement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateInspectionRequest(

        UUID parkingSessionId,

        @NotNull
        UUID parkingSpaceId,

        UUID vehicleId,

        @Size(max = 10)
        String observedPlate,

        @NotNull
        OffsetDateTime observedAt,

        @NotBlank
        String result,

        @Size(max = 500)
        String notes,

        BigDecimal latitude,

        BigDecimal longitude
) {
}
