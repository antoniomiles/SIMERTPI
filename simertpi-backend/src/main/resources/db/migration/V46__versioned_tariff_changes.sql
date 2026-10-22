ALTER TABLE parking.tariffs
    ADD COLUMN version_group_id UUID,
    ADD COLUMN previous_version_id UUID REFERENCES parking.tariffs(id),
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

UPDATE parking.tariffs SET version_group_id = id;

ALTER TABLE parking.tariffs
    ALTER COLUMN version_group_id SET NOT NULL,
    ALTER COLUMN version_group_id SET DEFAULT gen_random_uuid(),
    DROP CONSTRAINT uk_tariffs_code;

CREATE INDEX idx_tariffs_code ON parking.tariffs(code);
CREATE INDEX idx_tariffs_version_group ON parking.tariffs(version_group_id, valid_from);

CREATE TABLE parking.tariff_change_history (
    id UUID PRIMARY KEY,
    tariff_id UUID NOT NULL REFERENCES parking.tariffs(id),
    previous_tariff_id UUID REFERENCES parking.tariffs(id),
    action VARCHAR(32) NOT NULL CHECK (action IN ('BASELINE','CREATED','SCHEDULED_UPDATED','SCHEDULED_CANCELLED','REVISED','ENDED','REVISED_IMMEDIATELY','ENDED_IMMEDIATELY')),
    actor_id UUID REFERENCES identity.users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    effective_at TIMESTAMPTZ,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    before_state JSONB,
    after_state JSONB NOT NULL,
    CONSTRAINT ck_tariff_history_baseline_actor CHECK (action <> 'BASELINE' OR actor_id IS NULL),
    CONSTRAINT ck_tariff_history_actor CHECK (action = 'BASELINE' OR actor_id IS NOT NULL)
);

CREATE INDEX idx_tariff_change_history_tariff ON parking.tariff_change_history(tariff_id, occurred_at DESC);
CREATE INDEX idx_tariff_change_history_actor ON parking.tariff_change_history(actor_id, occurred_at DESC);

INSERT INTO parking.tariff_change_history
    (id, tariff_id, previous_tariff_id, action, actor_id, occurred_at, effective_at, reason, before_state, after_state)
SELECT gen_random_uuid(), t.id, NULL, 'BASELINE', NULL, t.updated_at, t.valid_from,
       'Configuración existente al habilitar el historial detallado; el autor y motivo anteriores no están disponibles.', NULL,
       jsonb_build_object(
           'id',t.id,'code',t.code,'name',t.name,'zoneId',t.zone_id,'amount',t.amount,'currency',t.currency,
           'durationMinutes',t.duration_minutes,'minMinutes',t.min_minutes,'maxContinuousMinutes',t.max_continuous_minutes,
           'gracePeriodMinutes',t.grace_period_minutes,'roundingMode',t.rounding_mode,'validFrom',t.valid_from,
           'validTo',t.valid_to,'normativeReference',t.normative_reference,'active',t.active,
           'cancelledAt',t.cancelled_at,'cancelledByUserId',t.cancelled_by_user_id,'cancellationReason',t.cancellation_reason)
  FROM parking.tariffs t;

CREATE FUNCTION parking.reject_tariff_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'tariff change history is append-only';
END;
$$;

CREATE TRIGGER trg_tariff_history_append_only
    BEFORE UPDATE OR DELETE ON parking.tariff_change_history
    FOR EACH ROW EXECUTE FUNCTION parking.reject_tariff_history_mutation();

CREATE FUNCTION parking.validate_tariff_version_write() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('tariff-version-code:' || NEW.code));

    IF EXISTS (
        SELECT 1 FROM parking.tariffs other
         WHERE other.code = NEW.code
           AND other.version_group_id <> NEW.version_group_id
           AND other.id <> NEW.id
    ) THEN
        RAISE EXCEPTION 'tariff code belongs to another version group'
            USING ERRCODE = '23505', CONSTRAINT = 'uk_tariffs_code_version_group';
    END IF;

    IF NEW.previous_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM parking.tariffs previous
         WHERE previous.id = NEW.previous_version_id
           AND previous.version_group_id = NEW.version_group_id
           AND previous.zone_id IS NOT DISTINCT FROM NEW.zone_id
    ) THEN
        RAISE EXCEPTION 'previous tariff version must belong to the same series and scope'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_tariffs_previous_version_scope';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tariffs_version_integrity
    BEFORE INSERT OR UPDATE OF code, zone_id, valid_from, valid_to, active, cancelled_at, version_group_id, previous_version_id
    ON parking.tariffs
    FOR EACH ROW EXECUTE FUNCTION parking.validate_tariff_version_write();

COMMENT ON COLUMN parking.tariffs.version_group_id IS 'Stable identifier grouping immutable effective tariff versions.';
COMMENT ON COLUMN parking.tariffs.previous_version_id IS 'Previous version in this tariff series; null for its first version.';
COMMENT ON COLUMN parking.tariffs.row_version IS 'Optimistic concurrency token for administrative tariff edits.';
COMMENT ON TABLE parking.tariff_change_history IS 'Append-only before/after snapshots, actor, reason, and effective instant for tariff administration.';
