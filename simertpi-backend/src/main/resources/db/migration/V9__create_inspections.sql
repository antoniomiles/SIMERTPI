CREATE TABLE enforcement.inspections (
    id UUID PRIMARY KEY,
    inspector_id UUID NOT NULL,
    parking_session_id UUID,
    parking_space_id UUID NOT NULL,
    vehicle_id UUID,
    observed_plate VARCHAR(10),
    observed_at TIMESTAMPTZ NOT NULL,
    result VARCHAR(30) NOT NULL,
    notes VARCHAR(500),
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_inspections_inspector
        FOREIGN KEY (inspector_id)
        REFERENCES identity.users (id),

    CONSTRAINT fk_inspections_session
        FOREIGN KEY (parking_session_id)
        REFERENCES parking.parking_sessions (id),

    CONSTRAINT fk_inspections_space
        FOREIGN KEY (parking_space_id)
        REFERENCES parking.parking_spaces (id),

    CONSTRAINT fk_inspections_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES identity.vehicles (id),

    CONSTRAINT ck_inspections_result
        CHECK (result IN (
            'VALID',
            'EXPIRED',
            'NO_PAYMENT',
            'VIOLATION'
        ))
);

CREATE INDEX idx_inspections_inspector_id
    ON enforcement.inspections (inspector_id);

CREATE INDEX idx_inspections_session_id
    ON enforcement.inspections (parking_session_id);

CREATE INDEX idx_inspections_space_id
    ON enforcement.inspections (parking_space_id);

CREATE INDEX idx_inspections_vehicle_id
    ON enforcement.inspections (vehicle_id);

CREATE INDEX idx_inspections_observed_at
    ON enforcement.inspections (observed_at);

CREATE INDEX idx_inspections_result
    ON enforcement.inspections (result);
