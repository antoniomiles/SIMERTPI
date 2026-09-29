CREATE TABLE enforcement.violations (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    inspector_id UUID NOT NULL,
    parking_session_id UUID,
    parking_space_id UUID NOT NULL,
    vehicle_id UUID,
    plate VARCHAR(10),
    violation_type VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    fine_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    notified_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_violations_inspection
        FOREIGN KEY (inspection_id)
        REFERENCES enforcement.inspections (id),

    CONSTRAINT fk_violations_inspector
        FOREIGN KEY (inspector_id)
        REFERENCES identity.users (id),

    CONSTRAINT fk_violations_session
        FOREIGN KEY (parking_session_id)
        REFERENCES parking.parking_sessions (id),

    CONSTRAINT fk_violations_space
        FOREIGN KEY (parking_space_id)
        REFERENCES parking.parking_spaces (id),

    CONSTRAINT fk_violations_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES identity.vehicles (id),

    CONSTRAINT ck_violations_status
        CHECK (status IN (
            'OPEN',
            'NOTIFIED',
            'PAID',
            'CANCELLED',
            'DISPUTED'
        )),

    CONSTRAINT ck_violations_fine_amount
        CHECK (fine_amount >= 0)
);

CREATE TABLE enforcement.evidence (
    id UUID PRIMARY KEY,
    violation_id UUID NOT NULL,
    evidence_type VARCHAR(30) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255),
    content_type VARCHAR(100),
    file_size BIGINT,
    sha256_hash VARCHAR(64) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_evidence_violation
        FOREIGN KEY (violation_id)
        REFERENCES enforcement.violations (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_evidence_type
        CHECK (evidence_type IN (
            'PHOTO',
            'VIDEO',
            'DOCUMENT'
        ))
);

CREATE INDEX idx_violations_inspection_id
    ON enforcement.violations (inspection_id);

CREATE INDEX idx_violations_inspector_id
    ON enforcement.violations (inspector_id);

CREATE INDEX idx_violations_session_id
    ON enforcement.violations (parking_session_id);

CREATE INDEX idx_violations_space_id
    ON enforcement.violations (parking_space_id);

CREATE INDEX idx_violations_vehicle_id
    ON enforcement.violations (vehicle_id);

CREATE INDEX idx_violations_plate
    ON enforcement.violations (plate);

CREATE INDEX idx_violations_status
    ON enforcement.violations (status);

CREATE INDEX idx_violations_occurred_at
    ON enforcement.violations (occurred_at);

CREATE INDEX idx_evidence_violation_id
    ON enforcement.evidence (violation_id);

CREATE INDEX idx_evidence_sha256
    ON enforcement.evidence (sha256_hash);
