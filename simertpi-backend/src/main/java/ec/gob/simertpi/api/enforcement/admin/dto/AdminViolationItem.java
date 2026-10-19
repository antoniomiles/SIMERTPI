package ec.gob.simertpi.api.enforcement.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Administrative, read-only view with no narrative, owner identity, fine amount, or evidence metadata. */
public record AdminViolationItem(
        UUID id,
        String plate,
        String violationType,
        String status,
        OffsetDateTime occurredAt,
        UUID inspectionId,
        UUID parkingSessionId,
        String zoneCode,
        String zoneName,
        String streetCode,
        String streetName,
        String spaceCode,
        String spaceNumber,
        String inspectorUsername,
        long evidenceCount,
        OffsetDateTime createdAt,
        OffsetDateTime notifiedAt,
        OffsetDateTime paidAt
) { }
