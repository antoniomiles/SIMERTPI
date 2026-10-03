-- Configuration only; no normative rates, hours, holidays or grace are seeded.
ALTER TABLE parking.schedules ALTER COLUMN zone_id DROP NOT NULL;
ALTER TABLE parking.schedules ADD COLUMN valid_from DATE, ADD COLUMN valid_to DATE;
ALTER TABLE parking.schedules ADD CONSTRAINT ck_schedules_validity
    CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to >= valid_from);
ALTER TABLE parking.tariffs
    ADD COLUMN zone_id UUID REFERENCES parking.zones(id),
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN rounding_mode VARCHAR(20),
    ADD COLUMN grace_period_minutes INTEGER;
ALTER TABLE parking.tariffs ADD CONSTRAINT ck_tariffs_currency
    CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$');
ALTER TABLE parking.tariffs ADD CONSTRAINT ck_tariffs_rounding
    CHECK (rounding_mode IS NULL OR rounding_mode IN ('UP','DOWN','CEILING','FLOOR','HALF_UP','HALF_DOWN','HALF_EVEN'));
ALTER TABLE parking.tariffs ADD CONSTRAINT ck_tariffs_grace
    CHECK (grace_period_minutes IS NULL OR grace_period_minutes >= 0);
CREATE INDEX idx_tariffs_zone_validity ON parking.tariffs(zone_id, valid_from, valid_to) WHERE active;
COMMENT ON COLUMN parking.tariffs.grace_period_minutes IS 'Explicit grace; NULL means unconfigured, never a normative default.';
COMMENT ON COLUMN parking.tariffs.rounding_mode IS 'Monetary rounding after billing in min_minutes fractions; NULL means unconfigured.';
