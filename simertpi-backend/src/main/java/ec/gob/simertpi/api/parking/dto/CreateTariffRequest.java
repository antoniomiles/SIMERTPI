package ec.gob.simertpi.api.parking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CreateTariffRequest(

        @NotBlank
        @Size(max = 50)
        String code,

        @NotBlank
        @Size(max = 150)
        String name,

        @NotNull
        @DecimalMin(value = "0.00")
        BigDecimal amount,

        @NotNull
        @Min(1)
        Integer durationMinutes,

        @NotNull
        @Min(1)
        Integer minMinutes,

        @Min(1)
        Integer maxContinuousMinutes,

        @NotNull
        OffsetDateTime validFrom,

        OffsetDateTime validTo
) {}