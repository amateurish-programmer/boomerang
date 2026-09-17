-- Operational configuration, not a business schema migration. Placeholder is false by default in the caller.
BEGIN;
SET LOCAL statement_timeout = '15s';
DO $configure$
DECLARE enabled boolean := __ENABLE__; enqueue_id bigint; worker_id bigint;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_extension WHERE extname='pg_cron') OR NOT EXISTS(SELECT 1 FROM pg_extension WHERE extname='pg_net') THEN
  RAISE EXCEPTION 'Enable pg_cron and pg_net through the project extension settings first';
 END IF;
 IF to_regclass('vault.decrypted_secrets') IS NULL OR to_regprocedure('public.enqueue_due(integer,integer)') IS NULL THEN
  RAISE EXCEPTION 'Vault or research migrations are not ready';
 END IF;
 IF enabled AND (SELECT count(*) FROM vault.decrypted_secrets WHERE name='WORKER_SECRET' AND length(decrypted_secret)>=32)<>1 THEN
  RAISE EXCEPTION 'Provision exactly one Vault WORKER_SECRET matching the deployed worker before activation';
 END IF;
 -- Schedule and activation changes commit together: inactive installation has no brief active window.
 enqueue_id := cron.schedule('boomerang-enqueue-due','*/5 * * * *',$enqueue$SELECT public.enqueue_due(5,20);$enqueue$);
 PERFORM cron.alter_job(enqueue_id,active:=enabled);
 worker_id := cron.schedule('boomerang-worker','* * * * *',$worker$
DO $invoke$
DECLARE worker_secret text;
BEGIN
 -- Skip idle calls, but wake expired final attempts so claim_jobs can terminalize them and notify the owner.
 IF NOT EXISTS(SELECT 1 FROM private.jobs WHERE (status='QUEUED' AND available_at<=now()) OR (status='RUNNING' AND leased_until<=now())) THEN RETURN;END IF;
 SELECT decrypted_secret INTO STRICT worker_secret FROM vault.decrypted_secrets WHERE name='WORKER_SECRET';
 IF length(worker_secret)<32 THEN RAISE EXCEPTION 'Worker credential not configured';END IF;
 PERFORM net.http_post(
   url:='https://skeghmapzrmahxehazlp.supabase.co/functions/v1/worker',
   headers:=jsonb_build_object('Content-Type','application/json','Authorization','Bearer '||worker_secret),
   body:='{}'::jsonb,
   timeout_milliseconds:=65000
 );
END $invoke$;
$worker$);
 PERFORM cron.alter_job(worker_id,active:=enabled);
END $configure$;
COMMIT;
SELECT jobname,schedule,active FROM cron.job WHERE jobname IN ('boomerang-enqueue-due','boomerang-worker') ORDER BY jobname;
