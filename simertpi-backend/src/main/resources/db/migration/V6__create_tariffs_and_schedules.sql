CREATE TABLE parking.tariffs (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(150) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    min_minutes INTEGER NOT NULL DEFAULT 30,
    max_continuous_minutes INTEGER,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_tariffs_code UNIQUE (code),
    CONSTRAINT ck_tariffs_amount CHECK (amount >= 0),
    CONSTRAINT ck_tariffs_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_tariffs_min_minutes CHECK (min_minutes > 0)
);

CREATE TABLE parking.schedules (
    id UUID PRIMARY KEY,
    zone_id UUID NOT NULL,
    day_of_week SMALLINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_schedules_zone
        FOREIGN KEY (zone_id)
        REFERENCES parking.zones (id),

    CONSTRAINT ck_schedules_day
        CHECK (day_of_week BETWEEN 1 AND 7),

    CONSTRAINT ck_schedules_time
        CHECK (start_time < end_time)
);

CREATE TABLE parking.holidays (
    id UUID PRIMARY KEY,
    holiday_date DATE NOT NULL,
    name VARCHAR(150) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_holidays_date UNIQUE (holiday_date)
);

CREATE INDEX idx_tariffs_active
    ON parking.tariffs (active);

CREATE INDEX idx_tariffs_validity
    ON parking.tariffs (valid_from, valid_to);

CREATE INDEX idx_schedules_zone_day
    ON parking.schedules (zone_id, day_of_week);

CREATE INDEX idx_holidays_date
    ON parking.holidays (holiday_date);
