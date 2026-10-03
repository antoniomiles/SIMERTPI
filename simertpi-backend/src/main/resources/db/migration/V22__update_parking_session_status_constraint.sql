ALTER TABLE parking.parking_sessions
    DROP CONSTRAINT IF EXISTS ck_sessions_status;

ALTER TABLE parking.parking_sessions
    ADD CONSTRAINT ck_sessions_status
    CHECK (
        status IN (
            'PENDING_PAYMENT',
            'ACTIVE',
            'EXTENDED',
            'EXPIRED',
            'MAX_TIME_REACHED',
            'COMPLETED',
            'CANCELLED'
        )
    );
