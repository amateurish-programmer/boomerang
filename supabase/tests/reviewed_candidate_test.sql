\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.assert(ok boolean,message text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'ASSERT: %',message; END IF; END $$;
INSERT INTO auth.users VALUES('81000000-0000-4000-8000-000000000001'),('81000000-0000-4000-8000-000000000002');
INSERT INTO research_jobs(id,owner_id,query) VALUES('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','Topic');
INSERT INTO candidates(id,owner_id,job_id,proposal) VALUES('83000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','82000000-0000-4000-8000-000000000001','{"record_type":"PROMISE","subject":"Original subject","original_text":"Exact quotation","lifecycle":"DRAFT","date_precision":"UNKNOWN","timezone":"Asia/Shanghai"}');
INSERT INTO sources(owner_id,candidate_id,url,title,verified_by_tool) VALUES('81000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001','https://example.org/evidence','Source',true);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','81000000-0000-4000-8000-000000000002',true);
DO $$BEGIN
 BEGIN PERFORM accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{}',gen_random_uuid());RAISE EXCEPTION 'foreign candidate accepted';EXCEPTION WHEN insufficient_privilege THEN NULL;END;
END$$;
SELECT set_config('request.jwt.claim.sub','81000000-0000-4000-8000-000000000001',true);
DO $$BEGIN
 BEGIN PERFORM accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{"original_text":"Forged quote"}',gen_random_uuid());RAISE EXCEPTION 'original quote replaced';EXCEPTION WHEN invalid_parameter_value THEN NULL;END;
 BEGIN PERFORM accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{"confirmed_status":"FULFILLED"}',gen_random_uuid());RAISE EXCEPTION 'AI status confirmed';EXCEPTION WHEN invalid_parameter_value THEN NULL;END;
 BEGIN PERFORM accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{"due_start":"2026-12-02","due_end":"2026-12-01","date_precision":"RANGE"}',gen_random_uuid());RAISE EXCEPTION 'invalid dates saved';EXCEPTION WHEN check_violation THEN NULL;END;
END$$;
SELECT pg_temp.assert((SELECT count(*)=0 FROM records),'invalid review has no partial record');
SELECT pg_temp.assert((SELECT accepted_record_id IS NULL FROM candidates),'invalid review leaves candidate unaccepted');
SELECT accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{"subject":"Reviewed subject","notes":"User checked"}','84000000-0000-4000-8000-000000000001');
SELECT pg_temp.assert((SELECT subject='Reviewed subject' AND original_text='Exact quotation' AND notes='User checked' AND lifecycle='ACTIVE' AND revision=1 AND confirmed_status IS NULL FROM records),'review merges atomically without confirming result');
SELECT pg_temp.assert((SELECT proposal->>'subject'='Original subject' FROM candidates),'raw AI proposal remains unchanged');
SELECT pg_temp.assert((SELECT count(*)=1 FROM sources WHERE record_id IS NOT NULL AND verified_by_tool),'traceable source copied');
SELECT accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{"subject":"Reviewed subject","notes":"User checked"}','84000000-0000-4000-8000-000000000001');
SELECT accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{"subject":"Cannot edit accepted"}',gen_random_uuid());
SELECT pg_temp.assert((SELECT count(*)=1 AND max(revision)=1 AND min(subject)='Reviewed subject' FROM records),'replay never duplicates or edits accepted record');
DO $$BEGIN
 BEGIN PERFORM accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{}','84000000-0000-4000-8000-000000000001');RAISE EXCEPTION 'operation reused';EXCEPTION WHEN invalid_parameter_value THEN NULL;END;
END$$;
RESET ROLE;
SET LOCAL ROLE service_role;
DO $$BEGIN
 BEGIN PERFORM accept_reviewed_candidate('83000000-0000-4000-8000-000000000001','{}',gen_random_uuid());RAISE EXCEPTION 'AI role accepts candidate';EXCEPTION WHEN insufficient_privilege THEN NULL;END;
END$$;
RESET ROLE;
ROLLBACK;
\echo 'Reviewed candidate assertions passed'
