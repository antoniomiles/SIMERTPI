ALTER TABLE configuration.notification_rules DROP CONSTRAINT ck_notification_rules_event_type;
ALTER TABLE configuration.notification_rules ADD CONSTRAINT ck_notification_rules_event_type CHECK(event_type IN (
 'EXPIRATION','GRACE_PERIOD','AMONESTACION','EXCESS_11_30','EXCESS_31_60','EXCESS_61_120','EXCESS_OVER_120',
 'IMMOBILIZATION','MAX_TIME_WARNING','MAX_TIME_REACHED','PERMIT_CREATED','PERMIT_CANCELLED','PERMIT_EXPIRED',
 'PAYMENT_CREATED','PAYMENT_APPROVED','PAYMENT_DECLINED','PAYMENT_FAILED','PAYMENT_CANCELLED_TIMEOUT',
 'PARKING_ENDING_SOON','PARKING_TIME_EXPIRED','PARKING_GRACE_EXCEEDED','PARKING_COMPLETED','VERBAL_WARNING',
 'PARKING_EXTENSION_CONFIRMED','PARKING_STARTED','VEHICLE_REGISTERED','VEHICLE_DELETED'));

INSERT INTO configuration.notification_rules
 (id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from,mandatory)
SELECT md5('CP19.1:'||v.event_type||':PUSH')::uuid,'CP19_1_'||v.event_type||'_PUSH',
 v.event_type,'PUSH',0,true,v.title,v.message,'2000-01-01T00:00:00Z',false
FROM (VALUES
 ('VEHICLE_REGISTERED','Vehículo registrado','Tu vehículo con placa {plate} fue registrado correctamente.'),
 ('VEHICLE_DELETED','Vehículo eliminado','El vehículo con placa {plate} fue eliminado de tu cuenta.')
) v(event_type,title,message)
WHERE NOT EXISTS(SELECT 1 FROM configuration.notification_rules r WHERE r.event_type=v.event_type AND r.channel='PUSH')
ON CONFLICT(code,channel) DO NOTHING;
