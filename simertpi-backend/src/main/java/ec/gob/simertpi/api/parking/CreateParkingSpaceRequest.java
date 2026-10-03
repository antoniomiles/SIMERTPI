package ec.gob.simertpi.api.parking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateParkingSpaceRequest(
        @NotNull UUID streetId,
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String qrCode,
        @NotBlank @Size(max = 20) String spaceNumber,
        BigDecimal latitude,
        BigDecimal longitude,
        @Size(max = 30) String spaceType
) {}
