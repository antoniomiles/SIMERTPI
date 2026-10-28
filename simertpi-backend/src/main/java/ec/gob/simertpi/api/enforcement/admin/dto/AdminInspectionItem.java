package ec.gob.simertpi.api.enforcement.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal administrative inspection row; excludes notes, coordinates and vehicle identity. */
public record AdminInspectionItem(
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
        UUID violationId
) { }
