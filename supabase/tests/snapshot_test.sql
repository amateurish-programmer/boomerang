\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.assert_snapshot(ok boolean) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'snapshot assertion'; END IF; END $$;
INSERT INTO auth.users(id) VALUES ('07000000-0000-0000-0000-000000000001'),('07000000-0000-0000-0000-000000000002');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','07000000-0000-0000-0000-000000000001',true);
SELECT public.sync_record('{"id":"17000000-0000-0000-0000-000000000001","subject":"Me","original_text":"Snapshot 1"}','[{"id":"27000000-0000-0000-0000-000000000001","title":"Source 1","url":"https://example.org/one"}]',0,gen_random_uuid());
SELECT public.sync_record('{"id":"17000000-0000-0000-0000-000000000001","original_text":"Snapshot 2"}','[{"id":"27000000-0000-0000-0000-000000000002","title":"Source 2","url":"https://example.org/two"}]',1,gen_random_uuid());
SELECT pg_temp.assert_snapshot((public.get_record_snapshot('17000000-0000-0000-0000-000000000001')->'record'->>'revision')::int=2);
SELECT pg_temp.assert_snapshot(public.get_record_snapshot('17000000-0000-0000-0000-000000000001')->'sources'->0->>'title'='Source 2');
SELECT pg_temp.assert_snapshot(jsonb_array_length(public.get_record_snapshot('17000000-0000-0000-0000-000000000001')->'sources')=1);
SELECT pg_temp.assert_snapshot((SELECT snapshot->0->>'title'='Source 1' FROM public.record_source_revisions WHERE record_id='17000000-0000-0000-0000-000000000001' AND revision=1));
SELECT set_config('request.jwt.claim.sub','07000000-0000-0000-0000-000000000002',true);
DO $$ BEGIN
 BEGIN PERFORM public.get_record_snapshot('17000000-0000-0000-0000-000000000001'); RAISE EXCEPTION 'other owner snapshot'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;
SET LOCAL ROLE anon;
DO $$ BEGIN
 BEGIN PERFORM public.get_record_snapshot('17000000-0000-0000-0000-000000000001'); RAISE EXCEPTION 'anonymous snapshot'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;
ROLLBACK;
\echo 'Atomic snapshot and historical source assertions passed'
