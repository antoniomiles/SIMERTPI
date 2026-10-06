-- DEV opt-in only. Published Piñas ordinance 2021, arts. 15 and 26.
-- https://pinas.gob.ec/images/2021/ORDENANZAS/124_ORDENANZA_SUSTITUTIVA_SIMERTPI-2021.pdf
-- Historical tariffs are not changed. New sessions choose this later-dated reference.
-- Current amendments and the annual national-holiday calendar require municipal verification.
INSERT INTO parking.tariffs(id,code,name,zone_id,amount,duration_minutes,min_minutes,max_continuous_minutes,valid_from,currency,rounding_mode,grace_period_minutes) VALUES ('1dc51d89-6d12-52bd-b6fd-58efc824e93d','PIN-DEV-NORM-T01','DEV REFERENCIA ORDENANZA 2021 / VIGENCIA POR VERIFICAR','e96453f8-1929-5064-9eb7-9c9360ac77ef',0.25,60,30,240,'2026-01-02T00:00:00Z','USD','HALF_UP',10) ON CONFLICT (id) DO NOTHING;
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='8093bf13-810e-5789-ae13-cb716054afc3' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='77584d54-86b5-5dd6-a90a-0b5a8e8a5eb3' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='96a8040b-9318-5620-a675-73d2b2d14a42' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='8d5da543-ff78-5ed9-ae21-0218c34af55f' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='2bd899ea-6985-5ae7-894a-3043fc60c330' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
UPDATE parking.schedules SET start_time='08:00:00',end_time='13:00:00' WHERE id='974aa8ac-0850-5025-bdff-2c55ce5bdb66' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
UPDATE parking.schedules SET start_time='08:00:00',end_time='13:00:00' WHERE id='961229d9-7fc7-5de8-b941-972544e242dc' AND zone_id='e96453f8-1929-5064-9eb7-9c9360ac77ef';
INSERT INTO parking.tariffs(id,code,name,zone_id,amount,duration_minutes,min_minutes,max_continuous_minutes,valid_from,currency,rounding_mode,grace_period_minutes) VALUES ('cdc68ff8-bffd-5ac0-9745-d6f368db4210','PIN-DEV-NORM-T02','DEV REFERENCIA ORDENANZA 2021 / VIGENCIA POR VERIFICAR','38d82252-97e4-561c-8f46-c1abf56603e6',0.25,60,30,240,'2026-01-02T00:00:00Z','USD','HALF_UP',10) ON CONFLICT (id) DO NOTHING;
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='eeca12b6-7805-5835-adad-85539b4666d7' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='efbbddfa-c35d-5eef-815b-4477ad98cb15' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='956eed9b-1f21-5b52-b431-ba04a327f828' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='e1ea5d42-98e9-5e51-8722-2cedd0c2a6dd' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='1d5b6401-c971-519a-982e-4f1e6fed9f59' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
UPDATE parking.schedules SET start_time='08:00:00',end_time='13:00:00' WHERE id='eb8807be-67bb-5a64-ac61-4a8e88c3256d' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
UPDATE parking.schedules SET start_time='08:00:00',end_time='13:00:00' WHERE id='6e18b05e-30db-5cac-9b6f-b91295a94ec8' AND zone_id='38d82252-97e4-561c-8f46-c1abf56603e6';
INSERT INTO parking.tariffs(id,code,name,zone_id,amount,duration_minutes,min_minutes,max_continuous_minutes,valid_from,currency,rounding_mode,grace_period_minutes) VALUES ('e7f96359-0d73-55e4-b096-25c9dacc6aea','PIN-DEV-NORM-T03','DEV REFERENCIA ORDENANZA 2021 / VIGENCIA POR VERIFICAR','a56e94f2-ac70-514a-ba78-b156212d1d14',0.25,60,30,240,'2026-01-02T00:00:00Z','USD','HALF_UP',10) ON CONFLICT (id) DO NOTHING;
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='362f37b7-019f-51e9-b80a-83906516c386' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='03445d3a-4365-57c3-a5af-1a9c64a551f0' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='d5c3a9cf-241a-5f29-8b74-7a54d9dc4d26' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='c189e889-492a-5925-b8b6-c43dfda1d9f7' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
UPDATE parking.schedules SET start_time='08:00:00',end_time='18:00:00' WHERE id='232d3682-7114-5527-a998-e497c12370ba' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
UPDATE parking.schedules SET start_time='08:00:00',end_time='13:00:00' WHERE id='4ebd3e61-4ee6-5441-b5b5-fcaccf9ea867' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
UPDATE parking.schedules SET start_time='08:00:00',end_time='13:00:00' WHERE id='f5948c42-3134-5ac8-8885-5ef0b4ecf9c2' AND zone_id='a56e94f2-ac70-514a-ba78-b156212d1d14';
