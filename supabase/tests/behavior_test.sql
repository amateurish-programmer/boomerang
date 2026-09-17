\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.assert(ok boolean, message text) RETURNS void LANGUAGE plpgsql AS
$$ BEGIN IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'ASSERT: %', message; END IF; END $$;
INSERT INTO auth.users VALUES ('00000000-0000-0000-0000-000000000001'),('00000000-0000-0000-0000-000000000002');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
SELECT public.upsert_record('{"id":"10000000-0000-0000-0000-000000000001","subject":"Me","record_type":"PROMISE","original_text":"Deliver on time","said_at":"2026-09-17","due_start":"2026-09-18","due_end":"2026-09-18","date_precision":"DAY","date_text":"tomorrow","timezone":"Asia/Shanghai","verification_criteria":"Delivered"}',0,'20000000-0000-0000-0000-000000000001');
SELECT public.upsert_record('{"id":"10000000-0000-0000-0000-000000000001","subject":"Me","record_type":"PROMISE","original_text":"Deliver on time","said_at":"2026-09-17","due_start":"2026-09-18","due_end":"2026-09-18","date_precision":"DAY","date_text":"tomorrow","timezone":"Asia/Shanghai","verification_criteria":"Delivered"}',0,'20000000-0000-0000-0000-000000000001');
SELECT pg_temp.assert((SELECT count(*)=1 FROM record_revisions),'retry creates one revision');
DO $$ BEGIN
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000001","original_text":"overwrite"}',0,'20000000-0000-0000-0000-000000000002'); RAISE EXCEPTION 'conflict accepted'; EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL; END;
 BEGIN UPDATE records SET original_text='bypass'; RAISE EXCEPTION 'direct write accepted'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000001","confirmed_status":"FULFILLED"}',1,'20000000-0000-0000-0000-000000000003'); RAISE EXCEPTION 'confirmation bypass'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
END $$;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
SELECT pg_temp.assert((SELECT count(*)=0 FROM records),'second user cannot read first user');
DO $$ BEGIN
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000001","original_text":"steal"}',1,'20000000-0000-0000-0000-000000000004'); RAISE EXCEPTION 'ownership bypass'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;
DO $$ BEGIN
 BEGIN INSERT INTO sources(owner_id,record_id,url,title) VALUES ('00000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','https://example.org/proof','Proof'); RAISE EXCEPTION 'cross-owner source'; EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN UPDATE record_revisions SET snapshot='{}'; RAISE EXCEPTION 'history mutable'; EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
SELECT pg_temp.assert(NOT public.is_due('2026-09-18','Asia/Shanghai','2026-09-18 15:59:59+00'),'due_end includes local day');
SELECT pg_temp.assert(public.is_due('2026-09-18','Asia/Shanghai','2026-09-18 16:00:00+00'),'next midnight is due');
SELECT pg_temp.assert(NOT public.is_due(NULL,'Asia/Shanghai',now()),'unknown deadline never auto due');
INSERT INTO research_jobs(id,owner_id,query) VALUES ('30000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Research');
INSERT INTO candidates(id,owner_id,job_id,proposal) VALUES ('40000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','{"subject":"Subject","record_type":"STATEMENT","original_text":"Evidence backed","timezone":"Asia/Shanghai","verification_criteria":"Check source"}');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
DO $$ BEGIN
 BEGIN PERFORM accept_candidate('40000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001'); RAISE EXCEPTION 'sourceless accepted'; EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
RESET ROLE;
INSERT INTO sources(owner_id,candidate_id,url,title,verified_by_tool) VALUES ('00000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000001','https://example.org/proof','Proof',true);
SET LOCAL ROLE authenticated;
SELECT accept_candidate('40000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001');
SELECT accept_candidate('40000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000002');
SELECT pg_temp.assert((SELECT count(*)=2 FROM records),'candidate double-click does not duplicate');
RESET ROLE;
INSERT INTO checks(id,owner_id,record_id,record_revision,suggested_status,summary) VALUES ('60000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',1,'FULFILLED','AI suggestion');
SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.sub','',true);
DO $$ BEGIN
 BEGIN UPDATE records SET confirmed_status='FULFILLED'; RAISE EXCEPTION 'AI confirmed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM confirm_check('60000000-0000-0000-0000-000000000001',1,'FULFILLED',''); RAISE EXCEPTION 'service confirmation'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
SELECT confirm_check('60000000-0000-0000-0000-000000000001',1,'FULFILLED','I checked');
SELECT pg_temp.assert((SELECT confirmed_by=owner_id AND revision=2 FROM records WHERE id='10000000-0000-0000-0000-000000000001'),'user confirmation audited');
DO $$ BEGIN
 BEGIN PERFORM confirm_check('60000000-0000-0000-0000-000000000001',1,'BROKEN','stale'); RAISE EXCEPTION 'stale confirmed'; EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL; END;
END $$;
SELECT upsert_record('{"id":"10000000-0000-0000-0000-000000000001","notes":"Nonsemantic supplement","topic":"Delivery"}',2,'20000000-0000-0000-0000-000000000010');
SELECT pg_temp.assert((SELECT confirmed_status='FULFILLED' AND revision=3 FROM records WHERE id='10000000-0000-0000-0000-000000000001'),'notes and topic preserve confirmation');
SELECT upsert_record('{"id":"10000000-0000-0000-0000-000000000001","original_text":"Deliver a different item"}',3,'20000000-0000-0000-0000-000000000011');
SELECT pg_temp.assert((SELECT confirmed_status IS NULL AND confirmed_at IS NULL AND confirmed_by IS NULL AND confirmed_check_id IS NULL AND confirmation_reason IS NULL AND revision=4 FROM records WHERE id='10000000-0000-0000-0000-000000000001'),'semantic edit clears old confirmation');
SELECT pg_temp.assert((SELECT snapshot->>'confirmed_status'='FULFILLED' FROM record_revisions WHERE record_id='10000000-0000-0000-0000-000000000001' AND revision=2),'prior confirmed revision remains audited');
SELECT upsert_record(jsonb_build_object('id','10000000-0000-0000-0000-000000000003','subject','Me','original_text','Locked','capsule_locked_at',now(),'capsule_unlock_at',now()+interval '1 day'),0,'20000000-0000-0000-0000-000000000005');
DO $$ BEGIN
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000003","original_text":"tamper"}',1,'20000000-0000-0000-0000-000000000006'); RAISE EXCEPTION 'capsule editable'; EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000003","capsule_locked_at":null,"capsule_unlock_at":null}',1,'20000000-0000-0000-0000-000000000007'); RAISE EXCEPTION 'capsule unlocked'; EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000003","timezone":"Invalid/Zone"}',1,'20000000-0000-0000-0000-000000000008'); RAISE EXCEPTION 'invalid timezone'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM upsert_record('{"id":"10000000-0000-0000-0000-000000000003","notes":"different request"}',1,'20000000-0000-0000-0000-000000000005'); RAISE EXCEPTION 'idempotency key reused'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
END $$;
SELECT upsert_record('{"id":"10000000-0000-0000-0000-000000000003","notes":"Allowed supplement"}',1,'20000000-0000-0000-0000-000000000009');
RESET ROLE;
SET LOCAL ROLE service_role;
SELECT pg_temp.assert(consume_quota('00000000-0000-0000-0000-000000000001',current_date,1),'first quota allowed');
SELECT pg_temp.assert(NOT consume_quota('00000000-0000-0000-0000-000000000001',current_date,1),'quota limit enforced');
SELECT public.enqueue_job('00000000-0000-0000-0000-000000000001','RESEARCH','lease-test','{}');
SELECT pg_temp.assert((SELECT count(*)=1 FROM public.claim_jobs(1,60)),'first worker claims');
SELECT pg_temp.assert((SELECT count(*)=0 FROM public.claim_jobs(1,60)),'second worker cannot claim live lease');
RESET ROLE;
UPDATE private.jobs SET leased_until=now()-interval '1 second' WHERE dedupe_key='lease-test';
CREATE TEMP TABLE old_lease AS SELECT id,lease_token FROM private.jobs;
SET LOCAL ROLE service_role;
SELECT pg_temp.assert((SELECT count(*)=1 FROM public.claim_jobs(1,60)),'expired lease reclaimed');
RESET ROLE;
DO $$ DECLARE j record; BEGIN
 SELECT * INTO j FROM old_lease;
 BEGIN PERFORM public.finish_job(j.id,j.lease_token,true,NULL); RAISE EXCEPTION 'stale lease accepted'; EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL; END;
END $$;
UPDATE private.jobs SET leased_until=now()-interval '1 second' WHERE dedupe_key='lease-test';
SET LOCAL ROLE service_role;
SELECT pg_temp.assert((SELECT count(*)=1 FROM public.claim_jobs(1,60)),'third and final attempt');
RESET ROLE;
UPDATE private.jobs SET leased_until=now()-interval '1 second' WHERE dedupe_key='lease-test';
SET LOCAL ROLE service_role;
SELECT pg_temp.assert((SELECT count(*)=0 FROM public.claim_jobs(1,60)),'no fourth attempt');
RESET ROLE;
SELECT pg_temp.assert((SELECT status='FAILED' AND attempts=3 FROM private.jobs WHERE dedupe_key='lease-test'),'exhausted job is dead letter');
SELECT pg_temp.assert(NOT public.valid_source_url('https://127.0.0.1/secret'),'private IP rejected');
SELECT pg_temp.assert(NOT public.valid_source_url('https://host.internal/path'),'internal host rejected');
INSERT INTO notifications(id,owner_id,dedupe_key,title,body) VALUES ('70000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','test-read','Ready','Check ready');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
DO $$ BEGIN
 BEGIN PERFORM mark_notification_read('70000000-0000-0000-0000-000000000001'); RAISE EXCEPTION 'cross-user read mutation'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
SELECT mark_notification_read('70000000-0000-0000-0000-000000000001');
SELECT pg_temp.assert((SELECT read_at IS NOT NULL FROM notifications WHERE id='70000000-0000-0000-0000-000000000001'),'own notification marked read');
RESET ROLE;
ROLLBACK;
\echo 'Database behavioral assertions passed'
