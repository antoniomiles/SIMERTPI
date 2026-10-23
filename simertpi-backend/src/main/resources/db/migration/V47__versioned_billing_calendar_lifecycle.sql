ALTER TABLE parking.billing_day_calendars
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN version_group_id UUID,
    ADD COLUMN previous_calendar_id UUID REFERENCES parking.billing_day_calendars(id),
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by_user_id UUID REFERENCES identity.users(id),
    ADD COLUMN cancellation_reason VARCHAR(500);

UPDATE parking.billing_day_calendars SET version_group_id = id;
ALTER TABLE parking.billing_day_calendars ALTER COLUMN version_group_id SET NOT NULL;
ALTER TABLE parking.billing_day_calendars ALTER COLUMN version_group_id SET DEFAULT gen_random_uuid();
ALTER TABLE parking.billing_day_calendars
    ADD CONSTRAINT ck_billing_calendar_cancelled_inactive CHECK (cancelled_at IS NULL OR NOT active),
    ADD CONSTRAINT ck_billing_calendar_cancel_metadata CHECK (
        (cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancellation_reason IS NULL)
        OR (cancelled_at IS NOT NULL AND cancelled_by_user_id IS NOT NULL AND cancellation_reason IS NOT NULL
            AND length(btrim(cancellation_reason)) > 0)
    ),
    ADD CONSTRAINT ck_billing_calendar_previous_scope CHECK (previous_calendar_id IS NULL OR previous_calendar_id <> id);

CREATE INDEX idx_billing_day_calendar_version_group
    ON parking.billing_day_calendars(version_group_id, valid_from);

CREATE TABLE parking.billing_day_calendar_history (
    id UUID PRIMARY KEY,
    calendar_id UUID NOT NULL REFERENCES parking.billing_day_calendars(id),
    previous_calendar_id UUID REFERENCES parking.billing_day_calendars(id),
    action VARCHAR(32) NOT NULL CHECK (action IN ('BASELINE','CREATED','SCHEDULED_UPDATED','SCHEDULED_CANCELLED','VERSIONED','ENDED')),
    actor_id UUID REFERENCES identity.users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    effective_from DATE,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    before_state JSONB,
    after_state JSONB NOT NULL,
    CONSTRAINT ck_billing_calendar_history_baseline_actor CHECK (action <> 'BASELINE' OR actor_id IS NULL),
    CONSTRAINT ck_billing_calendar_history_actor CHECK (action = 'BASELINE' OR actor_id IS NOT NULL)
);

CREATE INDEX idx_billing_calendar_history_calendar ON parking.billing_day_calendar_history(calendar_id, occurred_at DESC);

INSERT INTO parking.billing_day_calendar_history
    (id, calendar_id, previous_calendar_id, action, actor_id, occurred_at, effective_from, reason, before_state, after_state)
SELECT gen_random_uuid(), c.id, c.previous_calendar_id, 'BASELINE', NULL, c.updated_at, c.valid_from,
       'Configuración existente al habilitar el historial de calendarios; el responsable y motivo anteriores no están disponibles.', NULL,
       jsonb_build_object('id', c.id, 'zoneId', c.zone_id, 'validFrom', c.valid_from, 'validTo', c.valid_to,
           'normativeReference', c.normative_reference, 'active', c.active,
           'days', (SELECT jsonb_object_agg(d.day_of_week::text, d.chargeable) FROM parking.billing_day_calendar_days d WHERE d.calendar_id=c.id))
  FROM parking.billing_day_calendars c;

CREATE FUNCTION parking.reject_billing_calendar_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'billing calendar history is append-only';
END;
$$;

CREATE TRIGGER trg_billing_calendar_history_append_only
    BEFORE UPDATE OR DELETE ON parking.billing_day_calendar_history
    FOR EACH ROW EXECUTE FUNCTION parking.reject_billing_calendar_history_mutation();

COMMENT ON TABLE parking.billing_day_calendar_history IS
    'Append-only version history for weekly billing-day calendars, including actor, reason, and before/after state.';
