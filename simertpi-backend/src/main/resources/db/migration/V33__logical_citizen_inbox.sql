-- Existing CP13 records remain channel envelopes. This is their logical inbox
-- projection, with independent read state; external delivery state is untouched.
CREATE TABLE notification.inbox_items (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    source_event_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    UNIQUE(user_id, event_type, source_event_id)
);
CREATE INDEX idx_inbox_owner_order ON notification.inbox_items(user_id, created_at DESC, id DESC);
CREATE INDEX idx_inbox_unread ON notification.inbox_items(user_id) WHERE read_at IS NULL;
CREATE INDEX idx_sessions_citizen_history ON parking.parking_sessions(user_id,started_at DESC,id DESC)
 WHERE status IN ('COMPLETED','CANCELLED');

ALTER TABLE notification.notifications ADD COLUMN contract_end_at TIMESTAMPTZ;
ALTER TABLE configuration.notification_rules DROP CONSTRAINT ck_notification_rules_channel;
ALTER TABLE configuration.notification_rules ADD CONSTRAINT ck_notification_rules_channel
    CHECK(channel IN ('IN_APP','PUSH','EMAIL','WHATSAPP'));
ALTER TABLE configuration.notification_rules DROP CONSTRAINT ck_notification_rules_event_type;
ALTER TABLE configuration.notification_rules ADD CONSTRAINT ck_notification_rules_event_type CHECK(event_type IN (
 'EXPIRATION','GRACE_PERIOD','AMONESTACION','EXCESS_11_30','EXCESS_31_60','EXCESS_61_120','EXCESS_OVER_120',
 'IMMOBILIZATION','MAX_TIME_WARNING','MAX_TIME_REACHED','PERMIT_CREATED','PERMIT_CANCELLED','PERMIT_EXPIRED',
 'PAYMENT_CREATED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PAYMENT_CANCELLED_TIMEOUT',
 'PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED','PARKING_COMPLETED','VERBAL_WARNING'));

-- Split old configured reminders from actual expiration without changing lead time.
UPDATE notification.notifications n SET notification_type = CASE WHEN r.minutes_before > 0
    THEN 'PARKING_ENDING_SOON' ELSE 'PARKING_TIME_EXPIRED' END
FROM configuration.notification_rules r WHERE n.rule_id=r.id AND r.event_type='EXPIRATION';
UPDATE configuration.notification_rules SET event_type = CASE WHEN minutes_before > 0
    THEN 'PARKING_ENDING_SOON' ELSE 'PARKING_TIME_EXPIRED' END WHERE event_type='EXPIRATION';

-- A control fact belongs to one contractual expiration, not the entire session.
ALTER TABLE parking.parking_control_events ADD COLUMN contract_end_at TIMESTAMPTZ;
-- Older facts never stored their contractual reference. Preserve NULL rather
-- than assigning today's end to a fact from an earlier, already extended period.
-- Every new insert is populated by control_contract_end below.
DROP INDEX parking.uk_control_events_session_type;
CREATE UNIQUE INDEX uk_control_events_session_type_end ON parking.parking_control_events
    (parking_session_id,event_type,contract_end_at);

-- Preserve direct CP13 inserts while tying every new fact to its expiration.
CREATE FUNCTION parking.control_contract_end() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.contract_end_at IS NULL THEN
  SELECT expected_end_at INTO NEW.contract_end_at FROM parking.parking_sessions WHERE id=NEW.parking_session_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_contract_end BEFORE INSERT ON parking.parking_control_events
 FOR EACH ROW EXECUTE FUNCTION parking.control_contract_end();

CREATE FUNCTION notification.project_citizen_inbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE logical_source UUID; logical_id UUID;
BEGIN
 IF NEW.notification_type NOT IN ('PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED',
  'MAX_TIME_REACHED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PARKING_COMPLETED','VERBAL_WARNING') THEN
  RETURN NEW;
 END IF;
 logical_source := COALESCE(NEW.source_event_id, NEW.id);
 logical_id := md5(NEW.user_id::text || ':' || NEW.notification_type || ':' || logical_source::text)::uuid;
 INSERT INTO notification.inbox_items(id,user_id,source_event_id,event_type,title,message,created_at,read_at)
 VALUES(logical_id,NEW.user_id,logical_source,NEW.notification_type,NEW.title,NEW.message,NEW.created_at,NEW.read_at)
 ON CONFLICT(user_id,event_type,source_event_id) DO UPDATE SET
   title=CASE WHEN NEW.channel='IN_APP' THEN EXCLUDED.title ELSE inbox_items.title END,
   message=CASE WHEN NEW.channel='IN_APP' THEN EXCLUDED.message ELSE inbox_items.message END,
   created_at=LEAST(inbox_items.created_at,EXCLUDED.created_at),
   read_at=COALESCE(inbox_items.read_at,EXCLUDED.read_at);
 RETURN NEW;
END $$;
CREATE TRIGGER project_citizen_inbox AFTER INSERT OR UPDATE OF read_at ON notification.notifications
 FOR EACH ROW EXECUTE FUNCTION notification.project_citizen_inbox();

-- Deterministic backfill: earliest channel envelope supplies citizen content;
-- any existing read state is preserved. No delivery/provider data is copied.
INSERT INTO notification.inbox_items(id,user_id,source_event_id,event_type,title,message,created_at,read_at)
SELECT md5(user_id::text || ':' || notification_type || ':' || source::text)::uuid,
 user_id,source,notification_type,title,message,created_at,read_at
FROM (SELECT DISTINCT ON(user_id,notification_type,COALESCE(source_event_id,id))
 user_id,COALESCE(source_event_id,id) AS source,notification_type,title,message,
 min(created_at) OVER(PARTITION BY user_id,notification_type,COALESCE(source_event_id,id)) AS created_at,
 min(read_at) OVER(PARTITION BY user_id,notification_type,COALESCE(source_event_id,id)) AS read_at
 FROM notification.notifications WHERE notification_type IN
 ('PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED','MAX_TIME_REACHED',
 'PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PARKING_COMPLETED','VERBAL_WARNING')
 ORDER BY user_id,notification_type,COALESCE(source_event_id,id),created_at,id) existing;

INSERT INTO configuration.notification_rules
 (id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from)
SELECT md5('CP24:IN_APP:' || kind)::uuid,'CP24_' || kind,kind,'IN_APP',0,true,title,body,'2000-01-01T00:00:00Z'
FROM (VALUES
 ('PARKING_ENDING_SOON','Próximo a vencer','Tu estacionamiento está próximo a finalizar.'),
 ('PARKING_TIME_EXPIRED','Tiempo de estacionamiento finalizado','Tu tiempo de estacionamiento terminó.'),
 ('PARKING_GRACE_EXCEEDED','Período de gracia finalizado','El tiempo permitido ha sido excedido.'),
 ('MAX_TIME_REACHED','Tiempo máximo alcanzado','Debes mover tu vehículo a otro espacio.'),
 ('PAYMENT_APPROVED','Pago aprobado','Tu pago fue aprobado.'),
 ('PAYMENT_DECLINED','Pago rechazado','Tu pago fue rechazado. Revisa su estado en la aplicación.'),
 ('PAYMENT_FAILED','No se pudo completar el pago','Revisa el estado de tu pago en la aplicación.'),
 ('PARKING_COMPLETED','Estacionamiento finalizado','Tu estacionamiento ha finalizado.'),
 ('VERBAL_WARNING','Regulariza tu estacionamiento','Se ha registrado la actuación correspondiente.')
) AS defaults(kind,title,body);
