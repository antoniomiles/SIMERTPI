ALTER TABLE configuration.notification_rules DROP CONSTRAINT ck_notification_rules_event_type;
ALTER TABLE configuration.notification_rules ADD CONSTRAINT ck_notification_rules_event_type CHECK(event_type IN (
 'EXPIRATION','GRACE_PERIOD','AMONESTACION','EXCESS_11_30','EXCESS_31_60','EXCESS_61_120','EXCESS_OVER_120',
 'IMMOBILIZATION','MAX_TIME_WARNING','MAX_TIME_REACHED','PERMIT_CREATED','PERMIT_CANCELLED','PERMIT_EXPIRED',
 'PAYMENT_CREATED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PAYMENT_CANCELLED_TIMEOUT',
 'PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED','PARKING_COMPLETED','VERBAL_WARNING',
 'PARKING_EXTENSION_CONFIRMED'));

CREATE OR REPLACE FUNCTION notification.project_citizen_inbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE logical_source UUID; logical_id UUID;
BEGIN
 IF NEW.notification_type NOT IN ('PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED',
  'MAX_TIME_REACHED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PARKING_COMPLETED','VERBAL_WARNING',
  'PARKING_EXTENSION_CONFIRMED') THEN
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

INSERT INTO configuration.notification_rules
 (id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from,mandatory)
VALUES
 (md5('CP24.1:EXTENSION:IN_APP')::uuid,'CP24_1_EXTENSION_IN_APP','PARKING_EXTENSION_CONFIRMED','IN_APP',0,true,
  'Extensión confirmada','Tu estacionamiento fue extendido correctamente. Se agregaron {additionalMinutes} minutos. Nueva hora de finalización: {newExpectedEndTime}.',
  '2000-01-01T00:00:00Z',true),
 (md5('CP24.1:EXTENSION:PUSH')::uuid,'CP24_1_EXTENSION_PUSH','PARKING_EXTENSION_CONFIRMED','PUSH',0,true,
  'Extensión confirmada','Tu estacionamiento fue extendido correctamente. Se agregaron {additionalMinutes} minutos. Nueva hora de finalización: {newExpectedEndTime}.',
  '2000-01-01T00:00:00Z',false);
