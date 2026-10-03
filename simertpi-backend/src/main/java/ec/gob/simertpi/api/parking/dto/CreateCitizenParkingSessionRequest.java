package ec.gob.simertpi.api.parking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateCitizenParkingSessionRequest(
        @NotBlank String parkingSpaceQrCode,
        @NotNull UUID vehicleId,
        @NotNull UUID tariffId,
        @NotNull @Min(1) Integer durationMinutes
) {}
