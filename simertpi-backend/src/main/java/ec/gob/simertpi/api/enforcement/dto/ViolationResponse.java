package ec.gob.simertpi.api.enforcement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ViolationResponse(
        UUID id,
        UUID inspectionId,
        UUID parkingSessionId,
        UUID parkingSpaceId,
        UUID vehicleId,
        String plate,
        String violationType,
        String description,
        OffsetDateTime occurredAt,
        String status,
        BigDecimal fineAmount,
        OffsetDateTime notifiedAt,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) { }
