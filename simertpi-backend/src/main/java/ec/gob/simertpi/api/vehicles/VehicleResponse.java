package ec.gob.simertpi.api.vehicles;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VehicleResponse(
    UUID id,
    UUID userId,
    String plate,
    String brand,
    String model,
    String color,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
