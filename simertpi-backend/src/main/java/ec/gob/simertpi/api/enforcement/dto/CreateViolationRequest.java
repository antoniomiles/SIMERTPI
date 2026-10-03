package ec.gob.simertpi.api.enforcement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateViolationRequest(

        @NotNull
        UUID inspectionId,

        @NotBlank
        @Size(max = 50)
        String violationType,

        @Size(max = 500)
        String description,

        @NotNull
        OffsetDateTime occurredAt
) {}
