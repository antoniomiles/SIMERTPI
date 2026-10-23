package ec.gob.simertpi.api.parking.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record UpdateBillingDaysRequest(@NotNull LocalDate validFrom, LocalDate validTo,
        @NotNull @Size(min = 0, max = 7) List<Integer> chargeableDays,
        @jakarta.validation.constraints.NotBlank @Size(max = 255) String normativeReference,
        @NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion,
        @jakarta.validation.constraints.NotBlank @Size(max = 500) String reason) { }
