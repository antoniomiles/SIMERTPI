package ec.gob.simertpi.api.parking.admin.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminTariffWriteRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 150) String name,
        UUID zoneId,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Pattern(regexp = "^\\d{1,10}(?:\\.\\d{1,2})?$") String amount,
        @NotNull @Min(1) Integer durationMinutes,
        @NotNull @Min(1) Integer minMinutes,
        @NotNull @Min(1) Integer maxContinuousMinutes,
        @NotNull @Min(0) Integer gracePeriodMinutes,
        @NotBlank @Pattern(regexp = "^(UP|DOWN|CEILING|FLOOR|HALF_UP|HALF_DOWN|HALF_EVEN)$") String roundingMode,
        @NotNull OffsetDateTime validFrom,
        OffsetDateTime validTo,
        @NotBlank @Size(max = 255) String normativeReference) { }
