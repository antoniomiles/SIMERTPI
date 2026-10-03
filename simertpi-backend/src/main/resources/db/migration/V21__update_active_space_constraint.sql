DROP INDEX IF EXISTS parking.uk_sessions_active_space;

CREATE UNIQUE INDEX uk_sessions_occupied_space
    ON parking.parking_sessions (parking_space_id)
    WHERE status IN (
        'PENDING_PAYMENT',
        'ACTIVE',
        'EXTENDED',
        'EXPIRED',
        'MAX_TIME_REACHED'
    );