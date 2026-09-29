CREATE TABLE audit.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_outbox_status
        CHECK (status IN (
            'PENDING',
            'PUBLISHED',
            'FAILED'
        )),

    CONSTRAINT ck_outbox_retry_count
        CHECK (retry_count >= 0)
);

CREATE INDEX idx_outbox_status
    ON audit.outbox_events (status);

CREATE INDEX idx_outbox_occurred_at
    ON audit.outbox_events (occurred_at);

CREATE INDEX idx_outbox_aggregate
    ON audit.outbox_events (aggregate_type, aggregate_id);

CREATE INDEX idx_outbox_event_type
    ON audit.outbox_events (event_type);
