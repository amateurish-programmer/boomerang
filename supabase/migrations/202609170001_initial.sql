BEGIN;
CREATE SCHEMA private;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
CREATE DOMAIN public.result_status AS text CHECK (VALUE IN ('FULFILLED','BROKEN','PARTIAL','DISPUTED','UNVERIFIABLE','REVISED'));
CREATE FUNCTION public.valid_source_url(u text) RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT length(u)<=2048 AND u ~ '^https://[A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,}(:443)?(/[^\s]*)?$'
 AND u !~* '^https://([^/]*\.)?(localhost|local|internal|test|invalid)(:|/|$)'
 AND u !~* '^https://[^/]*\.(local|internal|localhost|test|invalid)(:|/|$)'
$$;
CREATE FUNCTION public.is_due(p_due_end date,p_timezone text,p_now timestamptz) RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT coalesce((p_now AT TIME ZONE p_timezone)::date > p_due_end,false)
$$;
CREATE TABLE public.records (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES auth.users(id),
 record_type text NOT NULL CHECK(record_type IN ('FLAG','PROMISE','PREDICTION','STATEMENT','MILESTONE')),
 original_text text NOT NULL CHECK(length(original_text) BETWEEN 1 AND 4000),
 subject text NOT NULL CHECK(length(subject) BETWEEN 1 AND 200), topic text NOT NULL DEFAULT '' CHECK(length(topic)<=200),
 lifecycle text NOT NULL DEFAULT 'ACTIVE' CHECK(lifecycle IN ('DRAFT','ACTIVE','CANCELLED')),
 confirmed_status public.result_status, confirmation_reason text CHECK(length(confirmation_reason)<=4000),
 confirmed_at timestamptz, confirmed_by uuid, confirmed_check_id uuid,
 said_at date, due_start date, due_end date, date_text text NOT NULL DEFAULT '' CHECK(length(date_text)<=500),
 date_precision text NOT NULL DEFAULT 'UNKNOWN' CHECK(date_precision IN ('UNKNOWN','DAY','MONTH','QUARTER','YEAR','RANGE')),
 timezone text NOT NULL DEFAULT 'Asia/Shanghai', verification_criteria text NOT NULL DEFAULT '' CHECK(length(verification_criteria)<=4000),
 notes text NOT NULL DEFAULT '' CHECK(length(notes)<=4000), capsule_locked_at timestamptz, capsule_unlock_at timestamptz,
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(owner_id,id), CHECK((due_start IS NULL)=(due_end IS NULL)), CHECK(due_end>=due_start),
 CHECK((date_precision='UNKNOWN')=(due_end IS NULL)),
 CHECK((capsule_locked_at IS NULL)=(capsule_unlock_at IS NULL)), CHECK(capsule_unlock_at>capsule_locked_at),
 CHECK((confirmed_status IS NULL AND confirmed_at IS NULL AND confirmed_by IS NULL AND confirmed_check_id IS NULL) OR
       (confirmed_status IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_by=owner_id AND confirmed_check_id IS NOT NULL))
);
CREATE TABLE public.record_revisions (
 owner_id uuid NOT NULL, record_id uuid NOT NULL, revision bigint NOT NULL, snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(owner_id,record_id,revision),
 FOREIGN KEY(owner_id,record_id) REFERENCES public.records(owner_id,id)
);
CREATE TABLE public.research_jobs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES auth.users(id), query text NOT NULL CHECK(length(query) BETWEEN 1 AND 4000),
 status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 error_code text, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(owner_id,id)
);
CREATE TABLE public.candidates (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL,job_id uuid NOT NULL,proposal jsonb NOT NULL CHECK(jsonb_typeof(proposal)='object' AND octet_length(proposal::text)<=32768),
 accepted_record_id uuid,created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id,job_id) REFERENCES public.research_jobs(owner_id,id), FOREIGN KEY(owner_id,accepted_record_id) REFERENCES public.records(owner_id,id)
);
CREATE TABLE public.sources (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL,record_id uuid,candidate_id uuid,
 url text NOT NULL CHECK(public.valid_source_url(url)),canonical_url text CHECK(public.valid_source_url(canonical_url)),title text NOT NULL CHECK(length(title) BETWEEN 1 AND 500),
 publisher text NOT NULL DEFAULT '' CHECK(length(publisher)<=500),published_at timestamptz,retrieved_at timestamptz NOT NULL DEFAULT now(),
 excerpt text NOT NULL DEFAULT '' CHECK(length(excerpt)<=4000),quoted_text text NOT NULL DEFAULT '' CHECK(length(quoted_text)<=4000),
 source_type text NOT NULL DEFAULT 'UNKNOWN' CHECK(source_type IN ('OFFICIAL','INTERVIEW','REGULATORY','MAINSTREAM_MEDIA','SECONDARY_MEDIA','SOCIAL_MEDIA','UNKNOWN')),source_level text NOT NULL DEFAULT 'C' CHECK(source_level IN ('S','A','B','C')),content_hash text CHECK(content_hash ~ '^[a-f0-9]{64}$'),
 verified_by_tool boolean NOT NULL DEFAULT false, UNIQUE(owner_id,id),UNIQUE(owner_id,record_id,id),
 CHECK(num_nonnulls(record_id,candidate_id)=1), FOREIGN KEY(owner_id,record_id) REFERENCES public.records(owner_id,id),
 FOREIGN KEY(owner_id,candidate_id) REFERENCES public.candidates(owner_id,id)
);
CREATE TABLE public.checks (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL,record_id uuid NOT NULL,record_revision bigint NOT NULL,
 suggested_status public.result_status NOT NULL,confidence numeric NOT NULL DEFAULT 0 CHECK(confidence BETWEEN 0 AND 1),summary text NOT NULL CHECK(length(summary)<=8000),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_id,id),UNIQUE(owner_id,record_id,id), FOREIGN KEY(owner_id,record_id,record_revision) REFERENCES public.record_revisions(owner_id,record_id,revision)
);
ALTER TABLE public.records ADD FOREIGN KEY(owner_id,id,confirmed_check_id) REFERENCES public.checks(owner_id,record_id,id);
CREATE TABLE public.check_sources (
 owner_id uuid NOT NULL,record_id uuid NOT NULL,check_id uuid NOT NULL,source_id uuid NOT NULL,
 stance text NOT NULL CHECK(stance IN ('SUPPORT','OPPOSE','NEUTRAL')),PRIMARY KEY(owner_id,check_id,source_id),
 FOREIGN KEY(owner_id,record_id,check_id) REFERENCES public.checks(owner_id,record_id,id),
 FOREIGN KEY(owner_id,record_id,source_id) REFERENCES public.sources(owner_id,record_id,id)
);
CREATE TABLE public.notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL REFERENCES auth.users(id),record_id uuid,
 dedupe_key text NOT NULL,title text NOT NULL CHECK(length(title)<=500),body text NOT NULL CHECK(length(body)<=4000),
 created_at timestamptz NOT NULL DEFAULT now(),read_at timestamptz,UNIQUE(owner_id,dedupe_key),
 FOREIGN KEY(owner_id,record_id) REFERENCES public.records(owner_id,id)
);
CREATE TABLE public.reminders (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL,record_id uuid NOT NULL,record_revision bigint NOT NULL,
 days_before integer NOT NULL CHECK(days_before IN (0,1,7)),scheduled_at timestamptz NOT NULL,delivered_at timestamptz,
 UNIQUE(owner_id,record_id,record_revision,days_before), FOREIGN KEY(owner_id,record_id,record_revision) REFERENCES public.record_revisions(owner_id,record_id,revision)
);
CREATE TABLE public.ai_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL REFERENCES auth.users(id),title text NOT NULL DEFAULT '' CHECK(length(title)<=500),
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(owner_id,id)
);
CREATE TABLE public.ai_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL,session_id uuid NOT NULL,
 role text NOT NULL CHECK(role IN ('user','assistant')),content text NOT NULL CHECK(length(content)<=16000),
 created_at timestamptz NOT NULL DEFAULT now(),FOREIGN KEY(owner_id,session_id) REFERENCES public.ai_sessions(owner_id,id)
);
CREATE TABLE private.operations (
 owner_id uuid NOT NULL REFERENCES auth.users(id),operation_id uuid NOT NULL,request jsonb NOT NULL,response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(owner_id,operation_id)
);
CREATE TABLE private.usage (
 owner_id uuid NOT NULL REFERENCES auth.users(id),day date NOT NULL,used integer NOT NULL CHECK(used>0),PRIMARY KEY(owner_id,day)
);
CREATE TABLE private.jobs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),owner_id uuid NOT NULL REFERENCES auth.users(id),kind text NOT NULL CHECK(kind IN ('RESEARCH','CHECK','REMINDER')),
 dedupe_key text NOT NULL CHECK(length(dedupe_key)<=500),payload jsonb NOT NULL CHECK(octet_length(payload::text)<=131072),
 status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),available_at timestamptz NOT NULL DEFAULT now(),
 lease_token uuid,leased_until timestamptz,last_error text CHECK(length(last_error)<=1000),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_id,kind,dedupe_key)
);
CREATE INDEX records_owner_page ON public.records(owner_id,id);
CREATE INDEX records_due ON public.records(due_end) WHERE deleted_at IS NULL AND lifecycle='ACTIVE' AND confirmed_status IS NULL;
CREATE INDEX sources_record ON public.sources(owner_id,record_id);
CREATE INDEX sources_candidate ON public.sources(owner_id,candidate_id);
CREATE INDEX checks_record ON public.checks(owner_id,record_id);
CREATE INDEX candidates_job ON public.candidates(owner_id,job_id);
CREATE INDEX messages_session ON public.ai_messages(owner_id,session_id,created_at);
CREATE INDEX jobs_claim ON private.jobs(status,available_at,leased_until);
CREATE FUNCTION private.immutable_revision() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Revision history is append-only' USING ERRCODE='23514'; END $$;
CREATE TRIGGER revision_immutable BEFORE UPDATE OR DELETE ON public.record_revisions FOR EACH ROW EXECUTE FUNCTION private.immutable_revision();
CREATE FUNCTION private.audit_record() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=NEW.timezone) THEN RAISE EXCEPTION 'Invalid timezone' USING ERRCODE='22023'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.owner_id<>OLD.owner_id OR NEW.id<>OLD.id OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'Invalid revision/identity' USING ERRCODE='23514'; END IF;
  IF ROW(NEW.record_type,NEW.original_text,NEW.subject,NEW.said_at,NEW.due_start,NEW.due_end,NEW.date_text,NEW.date_precision,NEW.timezone,NEW.verification_criteria)
   IS DISTINCT FROM ROW(OLD.record_type,OLD.original_text,OLD.subject,OLD.said_at,OLD.due_start,OLD.due_end,OLD.date_text,OLD.date_precision,OLD.timezone,OLD.verification_criteria) THEN
   NEW.confirmed_status=NULL; NEW.confirmation_reason=NULL; NEW.confirmed_at=NULL; NEW.confirmed_by=NULL; NEW.confirmed_check_id=NULL;
  END IF;
  IF OLD.capsule_locked_at IS NOT NULL AND (NEW.capsule_locked_at IS DISTINCT FROM OLD.capsule_locked_at OR NEW.capsule_unlock_at IS DISTINCT FROM OLD.capsule_unlock_at) THEN RAISE EXCEPTION 'Capsule lock is immutable' USING ERRCODE='23514'; END IF;
  IF OLD.capsule_locked_at IS NOT NULL AND now()<OLD.capsule_unlock_at AND
   ROW(NEW.original_text,NEW.due_start,NEW.due_end,NEW.verification_criteria,NEW.timezone,NEW.date_text,NEW.date_precision,NEW.said_at)
   IS DISTINCT FROM ROW(OLD.original_text,OLD.due_start,OLD.due_end,OLD.verification_criteria,OLD.timezone,OLD.date_text,OLD.date_precision,OLD.said_at)
   THEN RAISE EXCEPTION 'Capsule content is locked' USING ERRCODE='23514'; END IF;
 END IF;
 NEW.updated_at=now(); RETURN NEW;
END $$;
CREATE TRIGGER record_guard BEFORE INSERT OR UPDATE ON public.records FOR EACH ROW EXECUTE FUNCTION private.audit_record();
CREATE FUNCTION private.save_revision() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN INSERT INTO public.record_revisions(owner_id,record_id,revision,snapshot) VALUES(NEW.owner_id,NEW.id,NEW.revision,to_jsonb(NEW)); RETURN NEW; END $$;
CREATE TRIGGER record_history AFTER INSERT OR UPDATE ON public.records FOR EACH ROW EXECUTE FUNCTION private.save_revision();
CREATE FUNCTION public.upsert_record(p_record jsonb,p_expected_revision bigint,p_operation_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid uuid:=auth.uid(); rid uuid; old public.records; val public.records; req jsonb; prior private.operations; output jsonb;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 IF p_operation_id IS NULL OR p_expected_revision IS NULL OR p_expected_revision<0 OR jsonb_typeof(p_record) IS DISTINCT FROM 'object' THEN RAISE EXCEPTION 'Invalid request' USING ERRCODE='22023'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_record) k WHERE k NOT IN ('id','record_type','original_text','subject','topic','lifecycle','said_at','due_start','due_end','date_text','date_precision','timezone','verification_criteria','notes','capsule_locked_at','capsule_unlock_at','deleted_at')) THEN RAISE EXCEPTION 'Unknown or server-owned field' USING ERRCODE='22023'; END IF;
 rid:=(p_record->>'id')::uuid;
 IF rid IS NULL THEN RAISE EXCEPTION 'Record id required' USING ERRCODE='22023'; END IF;
 req:=jsonb_build_object('action','upsert','record',p_record,'expected',p_expected_revision);
 PERFORM pg_advisory_xact_lock(hashtextextended(uid::text||p_operation_id::text,0));
 SELECT * INTO prior FROM private.operations WHERE owner_id=uid AND operation_id=p_operation_id;
 IF FOUND THEN
  IF prior.request<>req THEN RAISE EXCEPTION 'Operation id reused' USING ERRCODE='22023'; END IF;
  RETURN prior.response;
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(rid::text,1));
 SELECT * INTO old FROM public.records WHERE id=rid FOR UPDATE;
 IF FOUND AND old.owner_id<>uid THEN RAISE EXCEPTION 'Not owned' USING ERRCODE='42501'; END IF;
 IF coalesce(old.revision,0)<>p_expected_revision THEN RAISE EXCEPTION 'Revision conflict' USING ERRCODE='PT409'; END IF;
 IF old.id IS NULL THEN
  val:=jsonb_populate_record(NULL::public.records,jsonb_build_object('record_type','FLAG','original_text','','subject','','topic','','lifecycle','ACTIVE','date_text','','date_precision','UNKNOWN','timezone','Asia/Shanghai','verification_criteria','','notes','')||p_record);
 ELSE val:=jsonb_populate_record(old,p_record); END IF;
 IF old.id IS NULL THEN
  INSERT INTO public.records(id,owner_id,record_type,original_text,subject,topic,lifecycle,said_at,due_start,due_end,date_text,date_precision,timezone,verification_criteria,notes,capsule_locked_at,capsule_unlock_at,deleted_at)
  VALUES(rid,uid,val.record_type,val.original_text,val.subject,val.topic,val.lifecycle,val.said_at,val.due_start,val.due_end,val.date_text,val.date_precision,val.timezone,val.verification_criteria,val.notes,val.capsule_locked_at,val.capsule_unlock_at,val.deleted_at) RETURNING to_jsonb(records.*) INTO output;
 ELSE
  UPDATE public.records SET record_type=val.record_type,original_text=val.original_text,subject=val.subject,topic=val.topic,lifecycle=val.lifecycle,
   said_at=val.said_at,due_start=val.due_start,due_end=val.due_end,date_text=val.date_text,date_precision=val.date_precision,timezone=val.timezone,
   verification_criteria=val.verification_criteria,notes=val.notes,capsule_locked_at=val.capsule_locked_at,capsule_unlock_at=val.capsule_unlock_at,
   deleted_at=val.deleted_at,revision=old.revision+1 WHERE id=rid RETURNING to_jsonb(records.*) INTO output;
 END IF;
 INSERT INTO private.operations(owner_id,operation_id,request,response) VALUES(uid,p_operation_id,req,output);
 RETURN output;
END $$;
CREATE FUNCTION public.confirm_check(p_check_id uuid,p_expected_revision bigint,p_status text,p_reason text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid uuid:=auth.uid(); c public.checks;r public.records;output jsonb;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 SELECT * INTO c FROM public.checks WHERE id=p_check_id AND owner_id=uid;
 IF NOT FOUND THEN RAISE EXCEPTION 'Check not owned' USING ERRCODE='42501'; END IF;
 SELECT * INTO r FROM public.records WHERE id=c.record_id AND owner_id=uid FOR UPDATE;
 IF p_expected_revision IS NULL OR c.record_revision<>p_expected_revision OR r.revision<>p_expected_revision THEN RAISE EXCEPTION 'Revision conflict' USING ERRCODE='PT409'; END IF;
 IF r.deleted_at IS NOT NULL OR r.lifecycle<>'ACTIVE' OR p_status IS NULL THEN RAISE EXCEPTION 'Record not confirmable' USING ERRCODE='23514'; END IF;
 UPDATE public.records SET confirmed_status=p_status::public.result_status,confirmation_reason=p_reason,confirmed_at=now(),confirmed_by=uid,confirmed_check_id=c.id,revision=revision+1
 WHERE id=r.id RETURNING to_jsonb(records.*) INTO output;
 RETURN output;
END $$;
CREATE FUNCTION public.accept_candidate(p_candidate_id uuid,p_operation_id uuid) RETURNS jsonb
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
  output:=public.upsert_record(c.proposal||jsonb_build_object('id',rid),0,gen_random_uuid());
  INSERT INTO public.sources(owner_id,record_id,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool)
   SELECT uid,rid,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool FROM public.sources WHERE owner_id=uid AND candidate_id=c.id;
  UPDATE public.candidates SET accepted_record_id=rid WHERE id=c.id;
 END IF;
 INSERT INTO private.operations(owner_id,operation_id,request,response) VALUES(uid,p_operation_id,req,output);
 RETURN output;
END $$;
CREATE FUNCTION public.mark_notification_read(p_notification_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid uuid:=auth.uid();output jsonb;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 UPDATE public.notifications SET read_at=coalesce(read_at,now()) WHERE id=p_notification_id AND owner_id=uid RETURNING to_jsonb(notifications.*) INTO output;
 IF NOT FOUND THEN RAISE EXCEPTION 'Notification not owned' USING ERRCODE='42501'; END IF;
 RETURN output;
END $$;
CREATE FUNCTION public.enqueue_job(p_owner_id uuid,p_kind text,p_dedupe_key text,p_payload jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE result uuid;BEGIN
 INSERT INTO private.jobs(owner_id,kind,dedupe_key,payload) VALUES(p_owner_id,p_kind,p_dedupe_key,p_payload)
 ON CONFLICT(owner_id,kind,dedupe_key) DO UPDATE SET dedupe_key=excluded.dedupe_key RETURNING id INTO result;
 RETURN result;
END $$;
CREATE FUNCTION public.claim_jobs(p_limit integer DEFAULT 1,p_lease_seconds integer DEFAULT 90) RETURNS SETOF private.jobs
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 10 OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 30 AND 300 THEN RAISE EXCEPTION 'Invalid claim bounds' USING ERRCODE='22023'; END IF;
 UPDATE private.jobs SET status='FAILED',last_error='Retry limit exhausted',lease_token=NULL,leased_until=NULL WHERE status='RUNNING' AND leased_until<=now() AND attempts>=3;
 RETURN QUERY WITH picked AS (
 SELECT id FROM private.jobs WHERE attempts<3 AND ((status='QUEUED' AND available_at<=now()) OR (status='RUNNING' AND leased_until<=now()))
 ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT p_limit
 ) UPDATE private.jobs j SET status='RUNNING',attempts=j.attempts+1,lease_token=gen_random_uuid(),leased_until=now()+make_interval(secs=>p_lease_seconds)
 FROM picked WHERE j.id=picked.id RETURNING j.*;
END $$;
CREATE FUNCTION public.finish_job(p_job_id uuid,p_lease_token uuid,p_success boolean,p_error text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE private.jobs SET status=CASE WHEN p_success THEN 'SUCCEEDED' WHEN attempts>=3 THEN 'FAILED' ELSE 'QUEUED' END,
 last_error=CASE WHEN p_success THEN NULL ELSE left(p_error,1000) END,available_at=now()+make_interval(secs=>30*attempts),lease_token=NULL,leased_until=NULL
 WHERE id=p_job_id AND status='RUNNING' AND lease_token=p_lease_token AND leased_until>now();
 IF NOT FOUND THEN RAISE EXCEPTION 'Stale or expired lease' USING ERRCODE='PT409'; END IF;
END $$;
CREATE FUNCTION public.consume_quota(p_owner_id uuid,p_day date,p_limit integer) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF p_day IS NULL OR p_limit IS NULL OR p_limit<1 THEN RAISE EXCEPTION 'Invalid quota' USING ERRCODE='22023'; END IF;
 INSERT INTO private.usage(owner_id,day,used) VALUES(p_owner_id,p_day,1)
 ON CONFLICT(owner_id,day) DO UPDATE SET used=private.usage.used+1 WHERE private.usage.used<p_limit;
 RETURN FOUND;
END $$;
DO $$ DECLARE t text;BEGIN
 FOREACH t IN ARRAY ARRAY['records','record_revisions','sources','checks','check_sources','research_jobs','candidates','notifications','reminders','ai_sessions','ai_messages'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY owner_read ON public.%I FOR SELECT TO authenticated USING (owner_id=auth.uid())',t);
  EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC,anon,authenticated,service_role',t);
  EXECUTE format('GRANT SELECT ON public.%I TO authenticated,service_role',t);
 END LOOP;
END $$;
ALTER TABLE private.jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE private.operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE private.usage ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA private FROM PUBLIC,anon,authenticated,service_role;
GRANT USAGE ON SCHEMA public TO authenticated,service_role;
GRANT INSERT,UPDATE ON public.research_jobs,public.ai_sessions,public.ai_messages,public.notifications,public.reminders TO service_role;
GRANT INSERT ON public.candidates,public.sources,public.checks,public.check_sources TO service_role;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA private FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.upsert_record(jsonb,bigint,uuid),public.confirm_check(uuid,bigint,text,text),public.accept_candidate(uuid,uuid),public.enqueue_job(uuid,text,text,jsonb),public.claim_jobs(integer,integer),public.finish_job(uuid,uuid,boolean,text),public.consume_quota(uuid,date,integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.upsert_record(jsonb,bigint,uuid),public.confirm_check(uuid,bigint,text,text),public.accept_candidate(uuid,uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.mark_notification_read(uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.mark_notification_read(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.enqueue_job(uuid,text,text,jsonb),public.claim_jobs(integer,integer),public.finish_job(uuid,uuid,boolean,text),public.consume_quota(uuid,date,integer) TO service_role;
COMMIT;
