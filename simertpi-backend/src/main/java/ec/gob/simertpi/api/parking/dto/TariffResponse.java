package ec.gob.simertpi.api.parking.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TariffResponse(
        UUID id,
        String code,
        String name,
        BigDecimal amount,
        Integer durationMinutes,
        Integer minMinutes,
        Integer maxContinuousMinutes,
        boolean active,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}