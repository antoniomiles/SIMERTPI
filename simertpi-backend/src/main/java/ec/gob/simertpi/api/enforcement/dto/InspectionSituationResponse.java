package ec.gob.simertpi.api.enforcement.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InspectionSituationResponse(
        UUID parkingSpaceId,
        String parkingSpaceCode,
        String parkingSpaceQrCode,
        UUID vehicleId,
        String plate,
        UUID parkingSessionId,
        String sessionStatus,
        OffsetDateTime startedAt,
        OffsetDateTime expectedEndAt,
        OffsetDateTime endedAt,
        List<ControlEventSummary> controlEvents
) {
    public record ControlEventSummary(String eventType, OffsetDateTime occurredAt,
                                      Integer minutesOverdue, String status) { }
}
