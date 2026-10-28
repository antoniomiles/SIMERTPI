package ec.gob.simertpi.api.enforcement.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only administrative inspection detail; contains a bounded notes excerpt only. */
public record AdminInspectionDetail(
        UUID id,
        OffsetDateTime observedAt,
        String result,
        String plate,
        String zoneCode,
        String zoneName,
        String streetCode,
        String streetName,
        String spaceCode,
        String spaceNumber,
        String inspectorUsername,
        UUID violationId,
        String notes,
        OffsetDateTime createdAt,
        BigDecimal latitude,
        BigDecimal longitude
) { }
