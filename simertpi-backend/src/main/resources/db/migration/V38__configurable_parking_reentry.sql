CREATE TABLE configuration.parking_reentry_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    cooldown_minutes INTEGER NOT NULL CHECK (cooldown_minutes BETWEEN 1 AND 1440),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID REFERENCES identity.users(id) ON DELETE RESTRICT
);

INSERT INTO configuration.parking_reentry_settings(id, cooldown_minutes, updated_at)
VALUES (1, 5, CURRENT_TIMESTAMP);

CREATE INDEX idx_parking_sessions_reentry_lookup
    ON parking.parking_sessions(vehicle_id, parking_space_id, ended_at DESC)
    WHERE status = 'COMPLETED' AND ended_at IS NOT NULL;
