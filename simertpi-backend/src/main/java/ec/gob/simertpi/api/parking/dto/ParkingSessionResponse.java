package ec.gob.simertpi.api.parking.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ParkingSessionResponse(
        UUID id,
        UUID userId,
        UUID vehicleId,
        UUID parkingSpaceId,
        UUID tariffId,
        OffsetDateTime startedAt,
        OffsetDateTime expectedEndAt,
        OffsetDateTime endedAt,
        String status,
        BigDecimal totalAmount,
        Integer extensionCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String parkingSpaceCode,
        String qrCode,
        String plate,
        String tariffName,
        Integer durationMinutes,
        ec.gob.simertpi.application.parking.rules.SessionLifecycle.View operational
) {}
