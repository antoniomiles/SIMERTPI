CREATE TABLE permits.permits (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    zone_id UUID,
    permit_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ NOT NULL,
    authorization_code VARCHAR(100) NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_permits_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users (id),

    CONSTRAINT fk_permits_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES identity.vehicles (id),

    CONSTRAINT fk_permits_zone
        FOREIGN KEY (zone_id)
        REFERENCES parking.zones (id),

    CONSTRAINT uk_permits_authorization_code
        UNIQUE (authorization_code),

    CONSTRAINT ck_permits_status
        CHECK (status IN (
            'PENDING',
            'ACTIVE',
            'EXPIRED',
            'CANCELLED',
            'REJECTED'
        )),

    CONSTRAINT ck_permits_dates
        CHECK (valid_to > valid_from)
);

CREATE INDEX idx_permits_user_id
    ON permits.permits (user_id);

CREATE INDEX idx_permits_vehicle_id
    ON permits.permits (vehicle_id);

CREATE INDEX idx_permits_zone_id
    ON permits.permits (zone_id);

CREATE INDEX idx_permits_status
    ON permits.permits (status);

CREATE INDEX idx_permits_validity
    ON permits.permits (valid_from, valid_to);
