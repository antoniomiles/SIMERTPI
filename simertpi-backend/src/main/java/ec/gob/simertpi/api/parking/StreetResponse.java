package ec.gob.simertpi.api.parking;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StreetResponse(
        UUID id,
        UUID zoneId,
        String code,
        String name,
        String description,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}