BEGIN;
ALTER TABLE public.sources ADD COLUMN archived_at timestamptz;
ALTER TABLE public.sources ADD COLUMN origin text NOT NULL DEFAULT 'SERVER' CHECK(origin IN ('SERVER','CLIENT'));
CREATE TABLE public.record_source_revisions (
 owner_id uuid NOT NULL,record_id uuid NOT NULL,revision bigint NOT NULL,snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='array'),
 PRIMARY KEY(owner_id,record_id,revision),
 FOREIGN KEY(owner_id,record_id,revision) REFERENCES public.record_revisions(owner_id,record_id,revision)
);
ALTER TABLE public.record_source_revisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_read ON public.record_source_revisions FOR SELECT TO authenticated USING(owner_id=auth.uid());
REVOKE ALL ON public.record_source_revisions FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT ON public.record_source_revisions TO authenticated,service_role;
CREATE TRIGGER source_revision_immutable BEFORE UPDATE OR DELETE ON public.record_source_revisions FOR EACH ROW EXECUTE FUNCTION private.immutable_revision();

CREATE FUNCTION public.sync_record(p_record jsonb,p_sources jsonb,p_expected_revision bigint,p_operation_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid uuid:=auth.uid();rid uuid;req jsonb;prior private.operations;output jsonb;item jsonb;sid uuid;existing public.sources;seen uuid[]:='{}';
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 IF p_operation_id IS NULL OR jsonb_typeof(p_record) IS DISTINCT FROM 'object' OR jsonb_typeof(p_sources) IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'Invalid sync request' USING ERRCODE='22023'; END IF;
 IF jsonb_array_length(p_sources)>10 THEN RAISE EXCEPTION 'At most ten sources allowed' USING ERRCODE='22023'; END IF;
 req:=jsonb_build_object('action','sync_record','record',p_record,'sources',p_sources,'expected',p_expected_revision);
 PERFORM pg_advisory_xact_lock(hashtextextended(uid::text||p_operation_id::text,0));
 SELECT * INTO prior FROM private.operations WHERE owner_id=uid AND operation_id=p_operation_id;
 IF FOUND THEN
  IF prior.request<>req THEN RAISE EXCEPTION 'Operation id reused' USING ERRCODE='22023'; END IF;
  RETURN prior.response;
 END IF;
 -- The nested upsert and all evidence changes roll back together on any error.
 output:=public.upsert_record(p_record,p_expected_revision,gen_random_uuid());
 rid:=(output->>'id')::uuid;
 FOR item IN SELECT value FROM jsonb_array_elements(p_sources) LOOP
  IF jsonb_typeof(item) IS DISTINCT FROM 'object' THEN RAISE EXCEPTION 'Source must be an object' USING ERRCODE='22023'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN ('id','title','url')) OR
   jsonb_typeof(item->'id') IS DISTINCT FROM 'string' OR jsonb_typeof(item->'title') IS DISTINCT FROM 'string' OR jsonb_typeof(item->'url') IS DISTINCT FROM 'string' OR
   length(item->>'title') NOT BETWEEN 1 AND 500 OR public.valid_source_url(item->>'url') IS DISTINCT FROM true THEN
   RAISE EXCEPTION 'Invalid source fields' USING ERRCODE='22023';
  END IF;
  BEGIN sid:=(item->>'id')::uuid; EXCEPTION WHEN invalid_text_representation THEN RAISE EXCEPTION 'Source id must be UUID' USING ERRCODE='22023'; END;
  IF sid=ANY(seen) THEN RAISE EXCEPTION 'Duplicate source id' USING ERRCODE='22023'; END IF;
  seen:=array_append(seen,sid);
  SELECT * INTO existing FROM public.sources WHERE id=sid FOR UPDATE;
  IF FOUND THEN
   IF existing.owner_id<>uid OR existing.record_id IS DISTINCT FROM rid THEN RAISE EXCEPTION 'Source not owned by this record' USING ERRCODE='42501'; END IF;
   IF existing.title IS DISTINCT FROM item->>'title' OR existing.url IS DISTINCT FROM item->>'url' THEN RAISE EXCEPTION 'Source content is immutable; use a new source id' USING ERRCODE='PT409'; END IF;
   IF existing.origin='CLIENT' AND NOT existing.verified_by_tool THEN UPDATE public.sources SET archived_at=NULL WHERE id=sid; END IF;
  ELSE
   INSERT INTO public.sources(id,owner_id,record_id,title,url,origin) VALUES(sid,uid,rid,item->>'title',item->>'url','CLIENT');
  END IF;
 END LOOP;
 -- Referenced manual evidence cannot be removed by a client. Tool evidence is never archived here.
 IF EXISTS(SELECT 1 FROM public.sources s JOIN public.check_sources cs ON cs.owner_id=s.owner_id AND cs.source_id=s.id
  WHERE s.owner_id=uid AND s.record_id=rid AND s.origin='CLIENT' AND NOT s.verified_by_tool AND s.archived_at IS NULL AND NOT(s.id=ANY(seen))) THEN
  RAISE EXCEPTION 'Source referenced by a check cannot be archived' USING ERRCODE='PT409';
 END IF;
 UPDATE public.sources SET archived_at=now() WHERE owner_id=uid AND record_id=rid AND origin='CLIENT' AND NOT verified_by_tool AND archived_at IS NULL AND NOT(id=ANY(seen));
 INSERT INTO public.record_source_revisions(owner_id,record_id,revision,snapshot)
  SELECT uid,rid,(output->>'revision')::bigint,coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.id),'[]'::jsonb)
  FROM public.sources s WHERE s.owner_id=uid AND s.record_id=rid AND s.archived_at IS NULL;
 INSERT INTO private.operations(owner_id,operation_id,request,response) VALUES(uid,p_operation_id,req,output);
 RETURN output;
END $$;
REVOKE ALL ON FUNCTION public.sync_record(jsonb,jsonb,bigint,uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.sync_record(jsonb,jsonb,bigint,uuid) TO authenticated;
COMMIT;
