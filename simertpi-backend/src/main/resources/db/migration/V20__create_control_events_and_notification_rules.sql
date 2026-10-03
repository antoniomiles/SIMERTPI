CREATE TABLE parking.parking_control_events (
    id UUID PRIMARY KEY,
    parking_session_id UUID NOT NULL,
    user_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    parking_space_id UUID NOT NULL,

    event_type VARCHAR(50) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    minutes_overdue INTEGER,

    source VARCHAR(30) NOT NULL DEFAULT 'SYSTEM',
    status VARCHAR(30) NOT NULL DEFAULT 'RECORDED',
    notes VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_control_events_session
        FOREIGN KEY (parking_session_id)
        REFERENCES parking.parking_sessions (id),

    CONSTRAINT fk_control_events_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users (id),

    CONSTRAINT fk_control_events_vehicle
        FOREIGN KEY (vehicle_id)
        REFERENCES identity.vehicles (id),

    CONSTRAINT fk_control_events_space
        FOREIGN KEY (parking_space_id)
        REFERENCES parking.parking_spaces (id),

    CONSTRAINT ck_control_events_type
        CHECK (event_type IN (
            'EXPIRATION',
            'GRACE_PERIOD',
            'AMONESTACION',
            'EXCESS_11_30',
            'EXCESS_31_60',
            'EXCESS_61_120',
            'EXCESS_OVER_120',
            'IMMOBILIZATION',
            'MAX_TIME_WARNING',
            'MAX_TIME_REACHED'
        )),

    CONSTRAINT ck_control_events_source
        CHECK (source IN (
            'SYSTEM',
            'INSPECTOR',
            'ADMIN'
        )),

    CONSTRAINT ck_control_events_status
        CHECK (status IN (
            'RECORDED',
            'CONFIRMED',
            'CANCELLED'
        )),

    CONSTRAINT ck_control_events_minutes_overdue
        CHECK (minutes_overdue IS NULL OR minutes_overdue >= 0)
);

CREATE INDEX idx_control_events_session_id
    ON parking.parking_control_events (parking_session_id);

CREATE INDEX idx_control_events_user_id
    ON parking.parking_control_events (user_id);

CREATE INDEX idx_control_events_vehicle_id
    ON parking.parking_control_events (vehicle_id);

CREATE INDEX idx_control_events_space_id
    ON parking.parking_control_events (parking_space_id);

CREATE INDEX idx_control_events_type
    ON parking.parking_control_events (event_type);

CREATE INDEX idx_control_events_occurred_at
    ON parking.parking_control_events (occurred_at);

CREATE UNIQUE INDEX uk_control_events_session_type
    ON parking.parking_control_events (parking_session_id, event_type);


CREATE TABLE configuration.notification_rules (
    id UUID PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    channel VARCHAR(20) NOT NULL,

    minutes_before INTEGER NOT NULL DEFAULT 0,

    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    title_template VARCHAR(255) NOT NULL,
    message_template VARCHAR(1000) NOT NULL,

    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_notification_rules_code_channel
        UNIQUE (code, channel),

    CONSTRAINT ck_notification_rules_event_type
        CHECK (event_type IN (
            'EXPIRATION',
            'GRACE_PERIOD',
            'AMONESTACION',
            'EXCESS_11_30',
            'EXCESS_31_60',
            'EXCESS_61_120',
            'EXCESS_OVER_120',
            'IMMOBILIZATION',
            'MAX_TIME_WARNING',
            'MAX_TIME_REACHED'
        )),

    CONSTRAINT ck_notification_rules_channel
        CHECK (channel IN (
            'PUSH',
            'EMAIL'
        )),

    CONSTRAINT ck_notification_rules_minutes_before
        CHECK (minutes_before >= 0),

    CONSTRAINT ck_notification_rules_dates
        CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE INDEX idx_notification_rules_event_type
    ON configuration.notification_rules (event_type);

CREATE INDEX idx_notification_rules_enabled
    ON configuration.notification_rules (enabled);

CREATE INDEX idx_notification_rules_validity
    ON configuration.notification_rules (valid_from, valid_to);
