CREATE TABLE parking.parking_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    parking_space_id UUID NOT NULL,
    tariff_id UUID,
    started_at TIMESTAMPTZ NOT NULL,
    expected_end_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    extension_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_sessions_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users (id),

    CONSTRAINT fk_sessions_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES identity.vehicles (id),

    CONSTRAINT fk_sessions_space
        FOREIGN KEY (parking_space_id)
        REFERENCES parking.parking_spaces (id),

    CONSTRAINT fk_sessions_tariff
        FOREIGN KEY (tariff_id)
        REFERENCES parking.tariffs (id),

    CONSTRAINT ck_sessions_status
        CHECK (status IN (
            'PENDING_PAYMENT',
            'ACTIVE',
            'EXTENDED',
            'COMPLETED',
            'CANCELLED'
        )),

    CONSTRAINT ck_sessions_amount
        CHECK (total_amount >= 0),

    CONSTRAINT ck_sessions_extension_count
        CHECK (extension_count >= 0),

    CONSTRAINT ck_sessions_dates
        CHECK (expected_end_at > started_at)
);

CREATE INDEX idx_sessions_user_id
    ON parking.parking_sessions (user_id);

CREATE INDEX idx_sessions_vehicle_id
    ON parking.parking_sessions (vehicle_id);

CREATE INDEX idx_sessions_space_id
    ON parking.parking_sessions (parking_space_id);

CREATE INDEX idx_sessions_status
    ON parking.parking_sessions (status);

CREATE INDEX idx_sessions_started_at
    ON parking.parking_sessions (started_at);

CREATE UNIQUE INDEX uk_sessions_active_space
    ON parking.parking_sessions (parking_space_id)
    WHERE status IN ('PENDING_PAYMENT', 'ACTIVE', 'EXTENDED');
