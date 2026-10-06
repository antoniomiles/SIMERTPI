-- Human municipal action, separate from automatic parking control events.
CREATE TABLE enforcement.verbal_warnings (
    id uuid PRIMARY KEY,
    parking_session_id uuid NOT NULL REFERENCES parking.parking_sessions(id),
    inspector_id uuid NOT NULL REFERENCES identity.users(id),
    contract_end_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    observation varchar(500) NOT NULL CHECK (length(btrim(observation)) BETWEEN 1 AND 500),
    action_type varchar(30) NOT NULL DEFAULT 'VERBAL_WARNING' CHECK (action_type='VERBAL_WARNING'),
    active boolean NOT NULL DEFAULT true,
    idempotency_key varchar(128) NOT NULL,
    UNIQUE (inspector_id, idempotency_key),
    UNIQUE (parking_session_id, contract_end_at)
);
