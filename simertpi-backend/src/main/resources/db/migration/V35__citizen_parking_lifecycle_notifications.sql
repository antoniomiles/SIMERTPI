ALTER TABLE configuration.notification_rules DROP CONSTRAINT ck_notification_rules_event_type;
ALTER TABLE configuration.notification_rules ADD CONSTRAINT ck_notification_rules_event_type CHECK(event_type IN (
 'EXPIRATION','GRACE_PERIOD','AMONESTACION','EXCESS_11_30','EXCESS_31_60','EXCESS_61_120','EXCESS_OVER_120',
 'IMMOBILIZATION','MAX_TIME_WARNING','MAX_TIME_REACHED','PERMIT_CREATED','PERMIT_CANCELLED','PERMIT_EXPIRED',
 'PAYMENT_CREATED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PAYMENT_CANCELLED_TIMEOUT',
 'PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED','PARKING_COMPLETED','VERBAL_WARNING',
 'PARKING_EXTENSION_CONFIRMED','PARKING_STARTED'));

ALTER TABLE notification.inbox_items ADD COLUMN reference_type VARCHAR(50);
ALTER TABLE notification.inbox_items ADD COLUMN reference_id UUID;

-- Recover the safe resource reference from an existing channel envelope. The
-- logical inbox stays one row per event; choose IN_APP first, then stable order.
UPDATE notification.inbox_items i SET reference_type=source.reference_type, reference_id=source.reference_id
FROM (
 SELECT DISTINCT ON(user_id,notification_type,COALESCE(source_event_id,id))
        user_id,notification_type,COALESCE(source_event_id,id) AS source_event_id,
        reference_type,reference_id
 FROM notification.notifications
 WHERE reference_type IS NOT NULL AND reference_id IS NOT NULL
 ORDER BY user_id,notification_type,COALESCE(source_event_id,id),
          (channel='IN_APP') DESC,created_at,id
) source
WHERE i.user_id=source.user_id AND i.event_type=source.notification_type
  AND i.source_event_id=source.source_event_id;

CREATE OR REPLACE FUNCTION notification.project_citizen_inbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE logical_source UUID;
BEGIN
 IF NEW.notification_type NOT IN ('PARKING_STARTED','PARKING_ENDING_SOON','PARKING_TIME_EXPIRED',
  'PARKING_GRACE_EXCEEDED','MAX_TIME_REACHED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED',
  'PARKING_COMPLETED','VERBAL_WARNING','PARKING_EXTENSION_CONFIRMED') THEN
  RETURN NEW;
 END IF;
 logical_source := COALESCE(NEW.source_event_id, NEW.id);
 INSERT INTO notification.inbox_items(id,user_id,source_event_id,event_type,reference_type,reference_id,
                                      title,message,created_at,read_at)
 VALUES(md5(NEW.user_id::text || ':' || NEW.notification_type || ':' || logical_source::text)::uuid,
        NEW.user_id,logical_source,NEW.notification_type,NEW.reference_type,NEW.reference_id,
        NEW.title,NEW.message,NEW.created_at,NEW.read_at)
 ON CONFLICT(user_id,event_type,source_event_id) DO UPDATE SET
   title=CASE WHEN NEW.channel='IN_APP' THEN EXCLUDED.title ELSE inbox_items.title END,
   message=CASE WHEN NEW.channel='IN_APP' THEN EXCLUDED.message ELSE inbox_items.message END,
   reference_type=COALESCE(inbox_items.reference_type,EXCLUDED.reference_type),
   reference_id=COALESCE(inbox_items.reference_id,EXCLUDED.reference_id),
   created_at=LEAST(inbox_items.created_at,EXCLUDED.created_at),
   read_at=COALESCE(inbox_items.read_at,EXCLUDED.read_at);
 RETURN NEW;
END $$;

-- Citizen inbox content is useful and authoritative, while the FCM payload
-- remains data-only and Android renders privacy-safe event text locally.
UPDATE configuration.notification_rules SET
 title_template='Estacionamiento iniciado',
 message_template='Tu estacionamiento está activo por {contractedMinutes} minutos. Finaliza a las {expectedEndTime}. Importe confirmado: {currency} {amount}.'
WHERE event_type='PARKING_STARTED' AND channel='IN_APP';

UPDATE configuration.notification_rules SET
 title_template='Próximo a vencer',
 message_template='Te quedan {minutesRemaining} minutos. Puedes extender tu estacionamiento desde SIMERTPI.'
WHERE event_type='PARKING_ENDING_SOON' AND channel IN ('IN_APP','PUSH');

UPDATE configuration.notification_rules SET
 title_template='Tiempo de estacionamiento finalizado',
 message_template='El tiempo contratado ha finalizado. Revisa el estado de tu estacionamiento.'
WHERE event_type='PARKING_TIME_EXPIRED' AND channel IN ('IN_APP','PUSH');

UPDATE configuration.notification_rules SET
 title_template='Extensión confirmada',
 message_template='Agregaste {additionalMinutes} minutos por {currency} {amount}. Nuevo vencimiento: {newExpectedEndTime}.'
WHERE event_type='PARKING_EXTENSION_CONFIRMED' AND channel IN ('IN_APP','PUSH');

UPDATE configuration.notification_rules SET
 title_template='Estacionamiento finalizado',
 message_template='Tu estacionamiento fue finalizado correctamente a las {endedAt}.'
WHERE event_type='PARKING_COMPLETED' AND channel IN ('IN_APP','PUSH');

INSERT INTO configuration.notification_rules
 (id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from,mandatory)
VALUES
 (md5('CP24.1.1:PARKING_STARTED:IN_APP')::uuid,'CP24_1_1_PARKING_STARTED_IN_APP','PARKING_STARTED','IN_APP',0,true,
  'Estacionamiento iniciado','Tu estacionamiento está activo por {contractedMinutes} minutos. Finaliza a las {expectedEndTime}. Importe confirmado: {currency} {amount}.','2000-01-01T00:00:00Z',true),
 (md5('CP24.1.1:PARKING_STARTED:PUSH')::uuid,'CP24_1_1_PARKING_STARTED_PUSH','PARKING_STARTED','PUSH',0,true,
  'Estacionamiento iniciado','Tu estacionamiento está activo.','2000-01-01T00:00:00Z',false)
ON CONFLICT(code,channel) DO UPDATE SET enabled=true,title_template=EXCLUDED.title_template,
 message_template=EXCLUDED.message_template,mandatory=EXCLUDED.mandatory;

-- Add missing PUSH rules only where no existing rule already controls that
-- event/channel, preserving administrative enablement and avoiding duplicate sends.
INSERT INTO configuration.notification_rules
 (id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from,mandatory)
SELECT md5('CP24.1.1:'||v.event_type||':PUSH')::uuid,'CP24_1_1_'||v.event_type||'_PUSH',
 v.event_type,'PUSH',0,true,v.title,v.message,'2000-01-01T00:00:00Z',false
FROM (VALUES
 ('PARKING_TIME_EXPIRED','Tiempo de estacionamiento finalizado','El tiempo contratado ha finalizado.'),
 ('PARKING_COMPLETED','Estacionamiento finalizado','Tu estacionamiento fue finalizado correctamente.')
) v(event_type,title,message)
WHERE NOT EXISTS (SELECT 1 FROM configuration.notification_rules r
                  WHERE r.event_type=v.event_type AND r.channel='PUSH')
ON CONFLICT(code,channel) DO NOTHING;
