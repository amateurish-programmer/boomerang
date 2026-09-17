BEGIN;
-- User-reviewed metadata is merged only at first acceptance; the source quote and raw proposal remain immutable.
CREATE FUNCTION public.accept_reviewed_candidate(p_candidate_id uuid,p_review jsonb,p_operation_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid uuid:=auth.uid();c public.candidates;output jsonb;rid uuid;req jsonb;prior private.operations;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501';END IF;
 IF p_operation_id IS NULL OR p_candidate_id IS NULL OR jsonb_typeof(p_review) IS DISTINCT FROM 'object' OR octet_length(p_review::text)>32768 THEN RAISE EXCEPTION 'Invalid review' USING ERRCODE='22023';END IF;
 IF EXISTS(SELECT 1 FROM jsonb_object_keys(p_review) k WHERE k NOT IN ('subject','topic','said_at','due_start','due_end','date_text','date_precision','timezone','verification_criteria','notes')) THEN RAISE EXCEPTION 'Invalid review fields' USING ERRCODE='22023';END IF;
 IF EXISTS(SELECT 1 FROM jsonb_each(p_review) e WHERE (e.key IN ('said_at','due_start','due_end') AND jsonb_typeof(e.value) NOT IN ('string','null')) OR (e.key NOT IN ('said_at','due_start','due_end') AND jsonb_typeof(e.value)<>'string')) THEN RAISE EXCEPTION 'Invalid review types' USING ERRCODE='22023';END IF;
 req:=jsonb_build_object('action','accept_reviewed','candidate',p_candidate_id,'review',p_review);
 PERFORM pg_advisory_xact_lock(hashtextextended(uid::text||p_operation_id::text,0));
 SELECT * INTO prior FROM private.operations WHERE owner_id=uid AND operation_id=p_operation_id;
 IF FOUND THEN
  IF prior.request<>req THEN RAISE EXCEPTION 'Operation id reused' USING ERRCODE='22023';END IF;
  RETURN prior.response;
 END IF;
 SELECT * INTO c FROM public.candidates WHERE id=p_candidate_id AND owner_id=uid FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Candidate not owned' USING ERRCODE='42501';END IF;
 IF c.accepted_record_id IS NOT NULL THEN
  SELECT to_jsonb(r.*) INTO output FROM public.records r WHERE id=c.accepted_record_id AND owner_id=uid;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM public.sources WHERE owner_id=uid AND candidate_id=c.id AND verified_by_tool) THEN RAISE EXCEPTION 'Traceable source required' USING ERRCODE='23514';END IF;
  rid:=gen_random_uuid();
  output:=public.upsert_record(c.proposal||p_review||jsonb_build_object('id',rid,'lifecycle','ACTIVE'),0,gen_random_uuid());
  INSERT INTO public.sources(owner_id,record_id,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool)
   SELECT uid,rid,url,canonical_url,title,publisher,published_at,retrieved_at,excerpt,quoted_text,source_type,source_level,content_hash,verified_by_tool FROM public.sources WHERE owner_id=uid AND candidate_id=c.id;
  UPDATE public.candidates SET accepted_record_id=rid WHERE id=c.id;
 END IF;
 INSERT INTO private.operations(owner_id,operation_id,request,response) VALUES(uid,p_operation_id,req,output);
 RETURN output;
END $$;
REVOKE ALL ON FUNCTION public.accept_reviewed_candidate(uuid,jsonb,uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.accept_reviewed_candidate(uuid,jsonb,uuid) TO authenticated;
COMMIT;
