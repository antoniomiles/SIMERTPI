package ec.gob.simertpi.domain.parking.entity;

import java.util.List;

/** Canonical lifecycle states and the database-aligned space occupancy set. */
public enum ParkingSessionStatus {
    PENDING_PAYMENT(true),
    ACTIVE(true),
    EXTENDED(true),
    EXPIRED(true),
    MAX_TIME_REACHED(true),
    COMPLETED(false),
    CANCELLED(false);

    private final boolean occupiesSpace;

    ParkingSessionStatus(boolean occupiesSpace) {
        this.occupiesSpace = occupiesSpace;
    }

    public boolean occupiesSpace() {
        return occupiesSpace;
    }

    public static List<String> occupyingCodes() {
        return List.of(PENDING_PAYMENT, ACTIVE, EXTENDED, EXPIRED, MAX_TIME_REACHED)
                .stream().map(Enum::name).toList();
    }
}
