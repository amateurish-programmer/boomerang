\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.assert(ok boolean,message text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'ASSERT: %',message; END IF; END $$;
INSERT INTO auth.users(id) VALUES ('01000000-0000-0000-0000-000000000001'),('01000000-0000-0000-0000-000000000002');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','01000000-0000-0000-0000-000000000001',true);
SELECT sync_record('{"id":"11000000-0000-0000-0000-000000000001","subject":"Me","original_text":"Promise"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"}]',0,'31000000-0000-0000-0000-000000000001');
SELECT pg_temp.assert((SELECT count(*)=1 FROM sources WHERE record_id='11000000-0000-0000-0000-000000000001' AND archived_at IS NULL AND origin='CLIENT' AND NOT verified_by_tool),'record and manual source saved');
SELECT sync_record('{"id":"11000000-0000-0000-0000-000000000001","subject":"Me","original_text":"Promise"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"}]',0,'31000000-0000-0000-0000-000000000001');
SELECT pg_temp.assert((SELECT revision=1 FROM records WHERE id='11000000-0000-0000-0000-000000000001'),'retry preserves revision');
DO $$ BEGIN
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[]',0,'31000000-0000-0000-0000-000000000001'); RAISE EXCEPTION 'reused operation accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Tampered","url":"https://example.org/proof"}]',1,'31000000-0000-0000-0000-000000000002'); RAISE EXCEPTION 'source overwritten'; EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000002","subject":"Me","original_text":"Atomic"}','[{"id":"21000000-0000-0000-0000-000000000002","title":"Bad","url":"http://127.0.0.1/private"}]',0,'31000000-0000-0000-0000-000000000003'); RAISE EXCEPTION 'unsafe source accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[{"id":"21000000-0000-0000-0000-000000000002","title":"Forged","url":"https://example.org/","verified_by_tool":true}]',1,'31000000-0000-0000-0000-000000000004'); RAISE EXCEPTION 'trust field accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','{}',1,gen_random_uuid()); RAISE EXCEPTION 'nonarray accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}',(SELECT jsonb_agg(jsonb_build_object('id',gen_random_uuid(),'title','Many','url','https://example.org/')) FROM generate_series(1,11)),1,gen_random_uuid()); RAISE EXCEPTION 'too many accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[{"id":"bad","title":"Bad UUID","url":"https://example.org/"}]',1,gen_random_uuid()); RAISE EXCEPTION 'bad UUID accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"},{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"}]',1,gen_random_uuid()); RAISE EXCEPTION 'duplicate id accepted'; EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
END $$;
SELECT pg_temp.assert((SELECT count(*)=0 FROM records WHERE id='11000000-0000-0000-0000-000000000002'),'bad source rolls back entire new record');
SELECT pg_temp.assert((SELECT revision=1 FROM records WHERE id='11000000-0000-0000-0000-000000000001'),'failed source update rolls back revision');
SELECT sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[]',1,'31000000-0000-0000-0000-000000000005');
SELECT pg_temp.assert((SELECT archived_at IS NOT NULL AND title='Original proof' FROM sources WHERE id='21000000-0000-0000-0000-000000000001'),'removed source archived without modifying content');
SELECT sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"}]',2,'31000000-0000-0000-0000-000000000006');
SELECT pg_temp.assert((SELECT archived_at IS NULL FROM sources WHERE id='21000000-0000-0000-0000-000000000001'),'same immutable source can be restored');
SELECT set_config('request.jwt.claim.sub','01000000-0000-0000-0000-000000000002',true);
DO $$ BEGIN
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000003","subject":"Other","original_text":"Other"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"}]',0,'31000000-0000-0000-0000-000000000007'); RAISE EXCEPTION 'cross-owner source reused'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT pg_temp.assert((SELECT count(*)=0 FROM records),'cross-owner failure rolls back own record');
SELECT pg_temp.assert((SELECT count(*)=0 FROM record_source_revisions),'source history owner isolation');
RESET ROLE;
INSERT INTO sources(id,owner_id,record_id,url,title,verified_by_tool) VALUES ('21000000-0000-0000-0000-000000000009','01000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000001','https://example.org/tool','Tool proof',true);
INSERT INTO checks(id,owner_id,record_id,record_revision,suggested_status,summary) VALUES ('41000000-0000-0000-0000-000000000001','01000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000001',3,'UNVERIFIABLE','Review');
INSERT INTO check_sources(owner_id,record_id,check_id,source_id,stance) VALUES ('01000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000001','41000000-0000-0000-0000-000000000001','21000000-0000-0000-0000-000000000001','NEUTRAL');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','01000000-0000-0000-0000-000000000001',true);
DO $$ BEGIN
 BEGIN PERFORM sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[]',3,'31000000-0000-0000-0000-000000000008'); RAISE EXCEPTION 'referenced evidence archived'; EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL; END;
END $$;
SELECT sync_record('{"id":"11000000-0000-0000-0000-000000000001"}','[{"id":"21000000-0000-0000-0000-000000000001","title":"Original proof","url":"https://example.org/proof"}]',3,'31000000-0000-0000-0000-000000000009');
SELECT pg_temp.assert((SELECT archived_at IS NULL AND verified_by_tool FROM sources WHERE id='21000000-0000-0000-0000-000000000009'),'omitted tool source remains active');
SELECT pg_temp.assert((SELECT jsonb_array_length(snapshot)=1 AND snapshot->0->>'title'='Original proof' FROM record_source_revisions WHERE record_id='11000000-0000-0000-0000-000000000001' AND revision=1),'original revision source membership preserved');
SELECT pg_temp.assert((SELECT jsonb_array_length(snapshot)=0 FROM record_source_revisions WHERE record_id='11000000-0000-0000-0000-000000000001' AND revision=2),'archived revision preserves empty source membership');
RESET ROLE;
DO $$ BEGIN
 BEGIN UPDATE record_source_revisions SET snapshot='[]'; RAISE EXCEPTION 'source snapshot overwritten'; EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
ROLLBACK;
\echo 'Source sync assertions passed'
