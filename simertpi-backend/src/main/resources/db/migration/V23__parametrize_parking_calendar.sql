-- V23__parametrize_parking_calendar.sql

ALTER TABLE parking.holidays
    ADD COLUMN zone_id UUID,
    ADD COLUMN holiday_type VARCHAR(30) NOT NULL DEFAULT 'NATIONAL_HOLIDAY',
    ADD COLUMN tariffed BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN start_time TIME,
    ADD COLUMN end_time TIME,
    ADD COLUMN normative_reference VARCHAR(255),
    ADD COLUMN valid_from DATE NOT NULL DEFAULT CURRENT_DATE,
    ADD COLUMN valid_to DATE;

ALTER TABLE parking.holidays
    ADD CONSTRAINT fk_holidays_zone
    FOREIGN KEY (zone_id)
    REFERENCES parking.zones (id);

ALTER TABLE parking.holidays
    ADD CONSTRAINT ck_holidays_type
    CHECK (
        holiday_type IN (
            'NATIONAL_HOLIDAY',
            'NON_TARIFFED',
            'SPECIAL_SCHEDULE',
            'MUNICIPAL_EXCEPTION'
        )
    );

ALTER TABLE parking.holidays
    ADD CONSTRAINT ck_holidays_time_range
    CHECK (
        (start_time IS NULL AND end_time IS NULL)
        OR
        (start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)
    );

ALTER TABLE parking.holidays
    ADD CONSTRAINT ck_holidays_validity
    CHECK (
        valid_to IS NULL
        OR valid_to >= valid_from
    );

ALTER TABLE parking.holidays
    DROP CONSTRAINT IF EXISTS uk_holidays_date;

CREATE UNIQUE INDEX uk_holidays_date_zone
    ON parking.holidays (
        holiday_date,
        COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'::UUID)
    );

CREATE INDEX idx_holidays_zone_date
    ON parking.holidays (zone_id, holiday_date);

CREATE INDEX idx_holidays_validity
    ON parking.holidays (valid_from, valid_to);

CREATE INDEX idx_holidays_active_date
    ON parking.holidays (holiday_date, active);

COMMENT ON COLUMN parking.holidays.zone_id IS
    'Zona a la que aplica la excepción. NULL significa todas las zonas.';

COMMENT ON COLUMN parking.holidays.holiday_type IS
    'Tipo de regla de calendario parametrizable.';

COMMENT ON COLUMN parking.holidays.tariffed IS
    'Indica si el estacionamiento es tarifado en la fecha configurada.';

COMMENT ON COLUMN parking.holidays.normative_reference IS
    'Referencia normativa, resolución u ordenanza que sustenta la configuración.';
