package ec.gob.simertpi.application.parking.rules;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import java.time.*;
import java.util.Set;

/** Operational projection; never releases an occupied session or extends its contract. */
public final class SessionLifecycle {
    private SessionLifecycle() { }
    public record View(String state, Instant evaluatedAt, Instant nextTransitionAt,
                       boolean extensionEligible, boolean closeAllowed) { }
    public static View project(ParkingSession session, Integer max, Integer grace, boolean warning,
                               Instant now, long endingSoonSeconds) {
        String status = session.getStatus();
        if (!Set.of("ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED").contains(status == null ? "" : status))
            return new View(status, now, null, false, false);
        if (session.getExpectedEndAt() == null || session.getStartedAt() == null || max == null || grace == null)
            return new View("UNKNOWN", now, null, false, false);
        Instant end = session.getExpectedEndAt().toInstant();
        Instant maximum = session.getStartedAt().toInstant().plusSeconds(max * 60L);
        if (now.isBefore(end)) {
            if ("EXPIRED".equals(status)) return new View("UNKNOWN", now, null, false, false);
            Instant yellow = end.minusSeconds(endingSoonSeconds);
            return new View(now.isBefore(yellow) ? "ACTIVE" : "ENDING_SOON", now,
                    now.isBefore(yellow) ? yellow : end, end.isBefore(maximum), true);
        }
        Instant graceEnd = end.plusSeconds(grace * 60L);
        if (now.isBefore(graceEnd))
            return new View("EXPIRED_IN_GRACE", now, graceEnd, end.isBefore(maximum), true);
        if (warning && !end.isBefore(maximum))
            return new View("MAX_TIME_REACHED", now, null, false, true);
        return new View(warning ? "REGULARIZATION_ALLOWED" : "GRACE_EXCEEDED", now, null,
                warning && end.isBefore(maximum), warning);
    }
}
