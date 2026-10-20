package ec.gob.simertpi.api.parking.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AdminBillingDaysWriteRequest(
        UUID zoneId,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull @Size(min = 0, max = 7) List<Integer> chargeableDays,
        @NotBlank @Size(max = 255) String normativeReference) { }
