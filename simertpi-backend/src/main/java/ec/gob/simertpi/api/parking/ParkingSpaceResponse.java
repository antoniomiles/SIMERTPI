package ec.gob.simertpi.api.parking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ParkingSpaceResponse(
        UUID id,
        UUID streetId,
        String code,
        String qrCode,
        String spaceNumber,
        BigDecimal latitude,
        BigDecimal longitude,
        String spaceType,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
