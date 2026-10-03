package ec.gob.simertpi.api.enforcement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InspectionResponse(
        UUID id,
        UUID parkingSessionId,
        UUID parkingSpaceId,
        UUID vehicleId,
        String observedPlate,
        OffsetDateTime observedAt,
        String result,
        String notes,
        BigDecimal latitude,
        BigDecimal longitude,
        OffsetDateTime createdAt
) { }
