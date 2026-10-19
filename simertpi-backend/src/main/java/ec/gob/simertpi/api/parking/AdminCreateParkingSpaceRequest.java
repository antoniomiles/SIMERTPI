package ec.gob.simertpi.api.parking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AdminCreateParkingSpaceRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String qrCode,
        @NotBlank @Size(max = 20) String spaceNumber,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        @Size(max = 30) String spaceType
) {
    @AssertTrue(message = "La latitud y la longitud deben informarse juntas")
    public boolean isCoordinatesProvidedAsPair() {
        return (latitude == null) == (longitude == null);
    }
}
