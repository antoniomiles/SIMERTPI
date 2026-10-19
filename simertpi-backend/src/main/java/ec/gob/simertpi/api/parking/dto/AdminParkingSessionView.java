package ec.gob.simertpi.api.parking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal administrative summary; deliberately excludes occupant and payment identifiers. */
public record AdminParkingSessionView(UUID id, String plate, String zoneCode, String zoneName,
        String streetCode, String streetName, String spaceCode, String spaceNumber, boolean spaceEnabled,
        String sessionStatus, OffsetDateTime startedAt, OffsetDateTime expectedEndAt, OffsetDateTime endedAt,
        long durationMinutes, String paymentStatus) { }
