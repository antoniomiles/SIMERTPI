ALTER TABLE parking.tariffs
    ADD COLUMN normative_reference VARCHAR(255);

COMMENT ON COLUMN parking.tariffs.normative_reference IS
    'Legal or administrative source supporting this tariff version; nullable for historical rows lacking a reference.';

CREATE TABLE parking.billing_day_calendars (
    id UUID PRIMARY KEY,
    zone_id UUID REFERENCES parking.zones(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    normative_reference VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_billing_day_calendar_validity CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_billing_day_calendars_scope_validity
    ON parking.billing_day_calendars(zone_id, valid_from, valid_to) WHERE active;

CREATE TABLE parking.billing_day_calendar_days (
    calendar_id UUID NOT NULL REFERENCES parking.billing_day_calendars(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    chargeable BOOLEAN NOT NULL,
    PRIMARY KEY (calendar_id, day_of_week)
);

COMMENT ON TABLE parking.billing_day_calendars IS
    'Versioned weekly charge-day policy. A zonal calendar replaces the general calendar for its full date range; no general rows are copied to zones.';
COMMENT ON TABLE parking.billing_day_calendar_days IS
    'One explicit chargeable/free value for each ISO weekday; free days do not create zero-valued tariffs.';
