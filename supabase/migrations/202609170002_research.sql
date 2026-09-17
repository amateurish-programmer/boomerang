BEGIN;
ALTER TABLE public.candidates ADD COLUMN confidence numeric NOT NULL DEFAULT 0 CHECK(confidence BETWEEN 0 AND 1);
-- All evidence writes are fenced by the transaction RPC below, never direct service INSERT.
REVOKE INSERT ON public.candidates,public.sources,public.checks,public.check_sources FROM service_role;

CREATE FUNCTION public.enqueue_research(p_owner_id uuid,p_kind text,p_input jsonb,p_operation_id uuid,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE prior private.operations; req jsonb; jid uuid; output jsonb; r public.records; input jsonb:=p_input;
BEGIN
 IF p_owner_id IS NULL OR p_operation_id IS NULL OR p_kind NOT IN ('RESEARCH','CHECK') OR jsonb_typeof(p_input) IS DISTINCT FROM 'object' OR octet_length(p_input::text)>32768 OR p_limit NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION 'Invalid request' USING ERRCODE='22023'; END IF;
 req:=jsonb_build_object('action','research_job','kind',p_kind,'input',p_input);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_owner_id::text||p_operation_id::text,0));
 SELECT * INTO prior FROM private.operations WHERE owner_id=p_owner_id AND operation_id=p_operation_id;
 IF FOUND THEN IF prior.request<>req THEN RAISE EXCEPTION 'Operation reused' USING ERRCODE='22023'; END IF;RETURN prior.response;END IF;
 IF p_kind='CHECK' THEN
  SELECT * INTO r FROM public.records WHERE owner_id=p_owner_id AND id=(p_input->>'record_id')::uuid FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Not owned' USING ERRCODE='PT403';END IF;
  IF r.revision<>(p_input->>'expected_revision')::bigint OR r.deleted_at IS NOT NULL OR r.lifecycle<>'ACTIVE' OR r.confirmed_status IS NOT NULL THEN RAISE EXCEPTION 'Record changed' USING ERRCODE='PT409';END IF;
  input:=input||jsonb_build_object('record',to_jsonb(r));
 ELSE
  IF length(p_input->>'topic') NOT BETWEEN 1 AND 4000 OR p_input->>'said_at' IS NULL OR p_input->>'timezone' IS NULL THEN RAISE EXCEPTION 'Invalid research' USING ERRCODE='22023';END IF;
  PERFORM (p_input->>'said_at')::date;PERFORM now() AT TIME ZONE (p_input->>'timezone');
 END IF;
 IF NOT public.consume_quota(p_owner_id,(now() AT TIME ZONE 'UTC')::date,p_limit) THEN RAISE EXCEPTION 'Quota exceeded' USING ERRCODE='PT429';END IF;
 jid:=gen_random_uuid();
 INSERT INTO public.research_jobs(id,owner_id,query) VALUES(jid,p_owner_id,CASE WHEN p_kind='RESEARCH' THEN p_input->>'topic' ELSE r.original_text END);
 INSERT INTO private.jobs(id,owner_id,kind,dedupe_key,payload) VALUES(jid,p_owner_id,p_kind,p_operation_id::text,input);
 output:=jsonb_build_object('job_id',jid,'status','QUEUED');
 INSERT INTO private.operations(owner_id,operation_id,request,response) VALUES(p_owner_id,p_operation_id,req,output);
 RETURN output;
END $$;

CREATE OR REPLACE FUNCTION public.claim_jobs(p_limit integer DEFAULT 1,p_lease_seconds integer DEFAULT 90) RETURNS SETOF private.jobs
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE j private.jobs;
BEGIN
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 10 OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 30 AND 300 THEN RAISE EXCEPTION 'Invalid claim bounds' USING ERRCODE='22023';END IF;
 FOR j IN UPDATE private.jobs SET status='FAILED',last_error='RETRY_EXHAUSTED',lease_token=NULL,leased_until=NULL WHERE status='RUNNING' AND leased_until<=now() AND attempts>=3 RETURNING * LOOP
  UPDATE public.research_jobs SET status='FAILED',error_code='RETRY_EXHAUSTED',updated_at=now() WHERE id=j.id;
  INSERT INTO public.notifications(owner_id,record_id,dedupe_key,title,body) VALUES(j.owner_id,CASE WHEN j.kind='CHECK' THEN (j.payload->>'record_id')::uuid ELSE NULL END,'job:'||j.id,'调查未完成','重试次数已用完，请稍后手动重试。') ON CONFLICT(owner_id,dedupe_key) DO NOTHING;
 END LOOP;
 FOR j IN WITH picked AS (SELECT id FROM private.jobs WHERE attempts<3 AND ((status='QUEUED' AND available_at<=now()) OR (status='RUNNING' AND leased_until<=now())) ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT p_limit)
 UPDATE private.jobs q SET status='RUNNING',attempts=q.attempts+1,lease_token=gen_random_uuid(),leased_until=now()+make_interval(secs=>p_lease_seconds) FROM picked WHERE q.id=picked.id RETURNING q.* LOOP
  UPDATE public.research_jobs SET status='RUNNING',error_code=NULL,updated_at=now() WHERE id=j.id;
  RETURN NEXT j;
 END LOOP;
END $$;

CREATE FUNCTION public.fail_research_job(p_job_id uuid,p_lease_token uuid,p_error text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE j private.jobs;
BEGIN
 SELECT * INTO j FROM private.jobs WHERE id=p_job_id AND status='RUNNING' AND lease_token=p_lease_token AND leased_until>now() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Stale lease' USING ERRCODE='PT409';END IF;
 IF p_error NOT IN ('PROVIDER_UNAVAILABLE','INVALID_OUTPUT','STALE_RECORD','SERVICE_UNAVAILABLE','NOT_CONFIGURED') THEN p_error:='SERVICE_UNAVAILABLE';END IF;
 IF p_error='STALE_RECORD' THEN UPDATE private.jobs SET attempts=3 WHERE id=j.id;j.attempts:=3;END IF;
 PERFORM public.finish_job(j.id,p_lease_token,false,p_error);
 UPDATE public.research_jobs SET status=CASE WHEN j.attempts>=3 THEN 'FAILED' ELSE 'QUEUED' END,error_code=p_error,updated_at=now() WHERE id=j.id;
 IF j.attempts>=3 THEN
  INSERT INTO public.notifications(owner_id,record_id,dedupe_key,title,body) VALUES(j.owner_id,CASE WHEN j.kind='CHECK' THEN (j.payload->>'record_id')::uuid ELSE NULL END,'job:'||j.id,'调查未完成','未能取得可用证据，不会自动判断结果。') ON CONFLICT(owner_id,dedupe_key) DO NOTHING;
 END IF;
END $$;

CREATE FUNCTION private.save_job_source(p_owner uuid,p_record uuid,p_candidate uuid,s jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE sid uuid;
BEGIN
 IF jsonb_typeof(s) IS DISTINCT FROM 'object' OR s->>'url' IS NULL OR s->>'title' IS NULL OR s->>'retrieved_at' IS NULL OR s->>'content_hash' IS NULL THEN RAISE EXCEPTION 'Invalid source' USING ERRCODE='22023';END IF;
 INSERT INTO public.sources(owner_id,record_id,candidate_id,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool)
 VALUES(p_owner,p_record,p_candidate,s->>'url',s->>'canonical_url',s->>'title',coalesce(s->>'publisher',''),(s->>'published_at')::timestamptz,(s->>'retrieved_at')::timestamptz,coalesce(s->>'excerpt',''),coalesce(s->>'quoted_text',''),s->>'source_type',s->>'source_level',s->>'content_hash',true) RETURNING id INTO sid;
 RETURN sid;
END $$;

CREATE FUNCTION public.complete_research_job(p_job_id uuid,p_lease_token uuid,p_result jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE j private.jobs;r public.records;c jsonb;s jsonb;cid uuid;sid uuid;checkid uuid;total integer:=0;
BEGIN
 SELECT * INTO j FROM private.jobs WHERE id=p_job_id AND status='RUNNING' AND lease_token=p_lease_token AND leased_until>now() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Stale lease' USING ERRCODE='PT409';END IF;
 IF jsonb_typeof(p_result) IS DISTINCT FROM 'object' OR octet_length(p_result::text)>131072 THEN RAISE EXCEPTION 'Invalid result' USING ERRCODE='22023';END IF;
 IF j.kind='RESEARCH' THEN
  IF jsonb_typeof(p_result->'candidates') IS DISTINCT FROM 'array' OR jsonb_array_length(p_result->'candidates') NOT BETWEEN 0 AND 5 THEN RAISE EXCEPTION 'Invalid candidates' USING ERRCODE='22023';END IF;
  FOR c IN SELECT * FROM jsonb_array_elements(p_result->'candidates') LOOP
   IF jsonb_typeof(c->'sources') IS DISTINCT FROM 'array' OR jsonb_array_length(c->'sources') NOT BETWEEN 1 AND 10 OR c->'record'->>'lifecycle'<>'DRAFT' OR EXISTS(SELECT 1 FROM jsonb_object_keys(c->'record') k WHERE k NOT IN ('id','record_type','original_text','subject','topic','lifecycle','said_at','due_start','due_end','date_text','date_precision','timezone','verification_criteria','notes')) THEN RAISE EXCEPTION 'Invalid proposal' USING ERRCODE='22023';END IF;
   total:=total+jsonb_array_length(c->'sources');IF total>10 THEN RAISE EXCEPTION 'Too many sources' USING ERRCODE='22023';END IF;
   INSERT INTO public.candidates(owner_id,job_id,proposal,confidence) VALUES(j.owner_id,j.id,c->'record',(c->>'confidence')::numeric) RETURNING id INTO cid;
   FOR s IN SELECT * FROM jsonb_array_elements(c->'sources') LOOP PERFORM private.save_job_source(j.owner_id,NULL,cid,s);END LOOP;
  END LOOP;
 ELSIF j.kind='CHECK' THEN
  SELECT * INTO r FROM public.records WHERE owner_id=j.owner_id AND id=(j.payload->>'record_id')::uuid FOR SHARE;
  IF NOT FOUND OR r.revision<>(j.payload->>'expected_revision')::bigint OR r.deleted_at IS NOT NULL OR r.lifecycle<>'ACTIVE' OR r.confirmed_status IS NOT NULL THEN RAISE EXCEPTION 'Stale record' USING ERRCODE='PT409';END IF;
  IF jsonb_typeof(p_result->'sources') IS DISTINCT FROM 'array' OR jsonb_array_length(p_result->'sources')>10 OR (jsonb_array_length(p_result->'sources')=0 AND p_result->>'suggested_status'<>'UNVERIFIABLE') THEN RAISE EXCEPTION 'Evidence required' USING ERRCODE='22023';END IF;
  INSERT INTO public.checks(owner_id,record_id,record_revision,suggested_status,confidence,summary) VALUES(j.owner_id,r.id,r.revision,(p_result->>'suggested_status')::public.result_status,(p_result->>'confidence')::numeric,p_result->>'summary') RETURNING id INTO checkid;
  FOR s IN SELECT * FROM jsonb_array_elements(p_result->'sources') LOOP
   sid:=private.save_job_source(j.owner_id,r.id,NULL,s-'stance');
   INSERT INTO public.check_sources(owner_id,record_id,check_id,source_id,stance) VALUES(j.owner_id,r.id,checkid,sid,s->>'stance');
  END LOOP;
 ELSE RAISE EXCEPTION 'Unsupported job' USING ERRCODE='22023';END IF;
 -- Check the real clock after evidence work as well: transaction-start now() alone is insufficient.
 IF NOT EXISTS(SELECT 1 FROM private.jobs WHERE id=j.id AND leased_until>clock_timestamp()) THEN RAISE EXCEPTION 'Lease expired during persistence' USING ERRCODE='PT409';END IF;
 PERFORM public.finish_job(j.id,p_lease_token,true,NULL);
 UPDATE public.research_jobs SET status='SUCCEEDED',error_code=NULL,updated_at=now() WHERE id=j.id;
 INSERT INTO public.notifications(owner_id,record_id,dedupe_key,title,body) VALUES(j.owner_id,CASE WHEN j.kind='CHECK' THEN r.id ELSE NULL END,'job:'||j.id,CASE WHEN j.kind='CHECK' THEN '复核建议已就绪' ELSE '调查候选已就绪' END,'请查看证据并自行确认。') ON CONFLICT(owner_id,dedupe_key) DO NOTHING;
END $$;

CREATE FUNCTION public.enqueue_due(p_limit integer DEFAULT 100,p_daily_limit integer DEFAULT 20) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE r public.records;op uuid;count integer:=0;key text;
BEGIN
 IF p_limit NOT BETWEEN 1 AND 1000 OR p_daily_limit NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION 'Invalid bounds' USING ERRCODE='22023';END IF;
 FOR r IN SELECT * FROM public.records x WHERE lifecycle='ACTIVE' AND deleted_at IS NULL AND confirmed_status IS NULL AND public.is_due(due_end,timezone,now())
 AND NOT EXISTS(SELECT 1 FROM private.jobs q WHERE q.owner_id=x.owner_id AND q.kind='CHECK' AND q.dedupe_key='due:'||x.id||':'||x.revision)
 ORDER BY due_end,id LIMIT p_limit FOR UPDATE SKIP LOCKED LOOP
  key:='due:'||r.id||':'||r.revision;
  IF EXISTS(SELECT 1 FROM private.jobs WHERE owner_id=r.owner_id AND kind='CHECK' AND dedupe_key=key) THEN CONTINUE;END IF;
  BEGIN
   op:=gen_random_uuid();PERFORM public.enqueue_research(r.owner_id,'CHECK',jsonb_build_object('record_id',r.id,'expected_revision',r.revision),op,p_daily_limit);
   UPDATE private.jobs SET dedupe_key=key WHERE owner_id=r.owner_id AND kind='CHECK' AND dedupe_key=op::text;count:=count+1;
  EXCEPTION WHEN SQLSTATE 'PT429' THEN NULL;END;
 END LOOP;
 RETURN count;
END $$;
REVOKE ALL ON FUNCTION public.enqueue_research(uuid,text,jsonb,uuid,integer),public.fail_research_job(uuid,uuid,text),public.complete_research_job(uuid,uuid,jsonb),public.enqueue_due(integer,integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.enqueue_research(uuid,text,jsonb,uuid,integer),public.fail_research_job(uuid,uuid,text),public.complete_research_job(uuid,uuid,jsonb),public.enqueue_due(integer,integer) TO service_role;
REVOKE ALL ON FUNCTION private.save_job_source(uuid,uuid,uuid,jsonb) FROM PUBLIC,anon,authenticated,service_role;
CREATE OR REPLACE FUNCTION public.accept_candidate(p_candidate_id uuid,p_operation_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid uuid:=auth.uid(); c public.candidates;output jsonb;rid uuid;req jsonb;prior private.operations;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 IF p_operation_id IS NULL THEN RAISE EXCEPTION 'Operation id required' USING ERRCODE='22023'; END IF;
 req:=jsonb_build_object('action','accept','candidate',p_candidate_id);
 PERFORM pg_advisory_xact_lock(hashtextextended(uid::text||p_operation_id::text,0));
 SELECT * INTO prior FROM private.operations WHERE owner_id=uid AND operation_id=p_operation_id;
 IF FOUND THEN
  IF prior.request<>req THEN RAISE EXCEPTION 'Operation id reused' USING ERRCODE='22023'; END IF;
  RETURN prior.response;
 END IF;
 SELECT * INTO c FROM public.candidates WHERE id=p_candidate_id AND owner_id=uid FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Candidate not owned' USING ERRCODE='42501'; END IF;
 IF c.accepted_record_id IS NOT NULL THEN SELECT to_jsonb(r.*) INTO output FROM public.records r WHERE id=c.accepted_record_id;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM public.sources WHERE owner_id=uid AND candidate_id=c.id AND verified_by_tool) THEN RAISE EXCEPTION 'Traceable source required' USING ERRCODE='23514'; END IF;
  rid:=gen_random_uuid();
  output:=public.upsert_record(c.proposal||jsonb_build_object('id',rid,'lifecycle','ACTIVE'),0,gen_random_uuid());
  INSERT INTO public.sources(owner_id,record_id,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool)
   SELECT uid,rid,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool FROM public.sources WHERE owner_id=uid AND candidate_id=c.id;
  UPDATE public.candidates SET accepted_record_id=rid WHERE id=c.id;
 END IF;
 INSERT INTO private.operations(owner_id,operation_id,request,response) VALUES(uid,p_operation_id,req,output);
 RETURN output;
END $$;

COMMIT;

