package ec.gob.simertpi.api.parking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateParkingSessionRequest(

        @NotNull
        UUID vehicleId,

        @NotNull
        UUID parkingSpaceId,

        @NotNull
        UUID tariffId,

        @NotNull
        @Min(1)
        Integer durationMinutes
) {}
