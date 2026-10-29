package ec.gob.simertpi.api.parking.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record OperationalScheduleDeactivateRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotNull OffsetDateTime expectedUpdatedAt) {
}
