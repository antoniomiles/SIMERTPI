CREATE TABLE identity.vehicles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    plate VARCHAR(10) NOT NULL,
    brand VARCHAR(100),
    model VARCHAR(100),
    color VARCHAR(50),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_vehicles_plate UNIQUE (plate),

    CONSTRAINT fk_vehicles_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_vehicles_user_id
    ON identity.vehicles (user_id);

CREATE INDEX idx_vehicles_active
    ON identity.vehicles (active);