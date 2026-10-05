-- Preserve every vehicle ID and its historical foreign keys. Do not silently merge
-- or deactivate associations if existing active rows collide after normalization.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM identity.vehicles
               WHERE length(upper(btrim(plate))) NOT BETWEEN 1 AND 10) THEN
        RAISE EXCEPTION 'Vehicle plate normalization requires manual review: invalid normalized length';
    END IF;
    IF EXISTS (SELECT 1 FROM identity.vehicles WHERE active
               GROUP BY user_id, upper(btrim(plate)) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Vehicle plate normalization requires manual review: active associations collide for an owner';
    END IF;
END $$;

ALTER TABLE identity.vehicles DROP CONSTRAINT uk_vehicles_plate;
UPDATE identity.vehicles SET plate = upper(btrim(plate)) WHERE plate <> upper(btrim(plate));
CREATE UNIQUE INDEX uk_vehicles_owner_active_plate
    ON identity.vehicles (user_id, upper(btrim(plate))) WHERE active;
CREATE INDEX idx_sessions_vehicle_open ON parking.parking_sessions (vehicle_id, status);
