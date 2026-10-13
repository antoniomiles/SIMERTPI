-- Add only the missing optional channel; preserve existing administrative rules.
INSERT INTO configuration.notification_rules
 (id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from,mandatory)
SELECT md5('CP19:PARKING_ENDING_SOON:PUSH')::uuid,'CP19_PARKING_ENDING_SOON_PUSH',
 'PARKING_ENDING_SOON','PUSH',0,true,'Tu estacionamiento está por finalizar',
 'Te quedan {minutesRemaining} minutos de estacionamiento.','2000-01-01T00:00:00Z',false
WHERE NOT EXISTS (SELECT 1 FROM configuration.notification_rules
 WHERE event_type='PARKING_ENDING_SOON' AND channel='PUSH')
ON CONFLICT(code,channel) DO NOTHING;
