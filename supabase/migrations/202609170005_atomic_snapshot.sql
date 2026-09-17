BEGIN;
-- One SQL statement and one MVCC snapshot bind the returned record to its evidence.
CREATE FUNCTION public.get_record_snapshot(p_record_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE result jsonb;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 SELECT jsonb_build_object('record',to_jsonb(r),'sources',
  (SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.id),'[]'::jsonb)
   FROM public.sources s WHERE s.owner_id=r.owner_id AND s.record_id=r.id AND s.archived_at IS NULL))
 INTO result FROM public.records r WHERE r.id=p_record_id AND r.owner_id=auth.uid();
 IF result IS NULL THEN RAISE EXCEPTION 'Record not owned' USING ERRCODE='42501'; END IF;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION public.get_record_snapshot(uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.get_record_snapshot(uuid) TO authenticated;
COMMIT;
