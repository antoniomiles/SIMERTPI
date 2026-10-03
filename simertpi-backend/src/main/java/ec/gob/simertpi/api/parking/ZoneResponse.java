package ec.gob.simertpi.api.parking;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ZoneResponse(
        UUID id,
        String code,
        String name,
        String description,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}