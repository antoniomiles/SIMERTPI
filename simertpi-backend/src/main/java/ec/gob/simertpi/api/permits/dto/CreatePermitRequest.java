package ec.gob.simertpi.api.permits.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreatePermitRequest(
        @NotNull UUID userId,
        @NotNull UUID vehicleId,
        UUID zoneId,
        @NotBlank String permitType,
        @NotNull OffsetDateTime validFrom,
        @NotNull OffsetDateTime validTo,
        @NotBlank String authorizationCode,
        String notes
) { }
