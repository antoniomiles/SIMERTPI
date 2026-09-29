CREATE TABLE parking.zones (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_zones_code UNIQUE (code)
);

CREATE TABLE parking.streets (
    id UUID PRIMARY KEY,
    zone_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(150) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_streets_zone_code UNIQUE (zone_id, code),

    CONSTRAINT fk_streets_zone
        FOREIGN KEY (zone_id)
        REFERENCES parking.zones (id)
);

CREATE TABLE parking.parking_spaces (
    id UUID PRIMARY KEY,
    street_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    qr_code VARCHAR(255) NOT NULL,
    space_number VARCHAR(20) NOT NULL,
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    space_type VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_parking_spaces_code UNIQUE (code),
    CONSTRAINT uk_parking_spaces_qr UNIQUE (qr_code),
    CONSTRAINT uk_parking_spaces_street_number UNIQUE (street_id, space_number),

    CONSTRAINT fk_parking_spaces_street
        FOREIGN KEY (street_id)
        REFERENCES parking.streets (id)
);

CREATE INDEX idx_streets_zone_id
    ON parking.streets (zone_id);

CREATE INDEX idx_parking_spaces_street_id
    ON parking.parking_spaces (street_id);

CREATE INDEX idx_parking_spaces_active
    ON parking.parking_spaces (active);

CREATE INDEX idx_parking_spaces_coordinates
    ON parking.parking_spaces (latitude, longitude);