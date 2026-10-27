package ec.gob.simertpi.api.parking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class AdminCatalogUpdateRequests {
    private AdminCatalogUpdateRequests() { }

    public record ZoneUpdate(@NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 100) String name, @Size(max = 255) String description,
            @NotNull Boolean active, @NotNull OffsetDateTime expectedUpdatedAt) { }

    public record StreetUpdate(@NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 150) String name, @Size(max = 255) String description,
            @NotNull Boolean active, @NotNull OffsetDateTime expectedUpdatedAt) { }

    public record SpaceUpdate(@NotBlank @Size(max = 50) String code,
            @Size(max = 255) String qrCode,
            @NotBlank @Size(max = 20) String spaceNumber,
            @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 3, fraction = 7) BigDecimal latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 7) BigDecimal longitude,
            @Size(max = 30) String spaceType, @NotNull Boolean active,
            @NotNull OffsetDateTime expectedUpdatedAt) {
        @AssertTrue(message = "La latitud y la longitud deben informarse juntas")
        public boolean isCoordinatesProvidedAsPair() { return (latitude == null) == (longitude == null); }
    }
}
