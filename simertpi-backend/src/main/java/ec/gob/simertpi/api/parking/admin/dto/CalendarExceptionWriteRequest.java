package ec.gob.simertpi.api.parking.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CalendarExceptionWriteRequest(
        @NotNull LocalDate holidayDate,
        @NotBlank @Size(max = 150) String name,
        UUID zoneId,
        @NotBlank @Size(max = 30) String holidayType,
        @NotNull Boolean tariffed,
        LocalTime startTime,
        LocalTime endTime,
        @Size(max = 255) String normativeReference,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotBlank @Size(max = 500) String reason,
        OffsetDateTime expectedUpdatedAt) {
}
