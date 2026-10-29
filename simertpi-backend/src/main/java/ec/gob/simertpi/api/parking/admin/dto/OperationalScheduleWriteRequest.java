package ec.gob.simertpi.api.parking.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OperationalScheduleWriteRequest(
        UUID zoneId,
        @NotNull @Min(1) @Max(7) Short dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        LocalDate validFrom,
        LocalDate validTo,
        @NotBlank @Size(max = 500) String reason,
        OffsetDateTime expectedUpdatedAt) {
}
