import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
const container=process.env.RESEARCH_SQL_CONTAINER;
const sqlUrl=process.env.RESEARCH_SQL_URL;
const hasSql=Boolean(container||sqlUrl);
function psqlCommand(db){
 let command='docker',args=['exec','-i',container,'psql','-U','postgres','-d',db,'-v','ON_ERROR_STOP=1','-qAt'],env=process.env;
 if(sqlUrl){
  const url=new URL(sqlUrl);
  assert.ok(['postgres:','postgresql:'].includes(url.protocol)&&['localhost','127.0.0.1','[::1]'].includes(url.hostname),'SQL verification requires a local isolated PostgreSQL service');
  url.pathname='/'+db;
  // Keep credentials out of subprocess arguments and assertion output.
  command='psql';args=['-v','ON_ERROR_STOP=1','-qAt'];env={...process.env,PGHOST:url.hostname,PGPORT:url.port||'5432',PGUSER:decodeURIComponent(url.username)||'postgres',PGPASSWORD:decodeURIComponent(url.password),PGDATABASE:db};
 }
 return {command,args,env};
}
function psql(db,sql){
 const {command,args,env}=psqlCommand(db);
 const p=spawnSync(command,args,{input:sql,encoding:'utf8',env});assert.equal(p.status,0,p.stderr||p.error?.code);return p.stdout;
}
function psqlConcurrent(db,sql){
 const {command,args,env}=psqlCommand(db);
 return new Promise((resolve,reject)=>{
  const child=spawn(command,args,{env,stdio:['pipe','pipe','pipe']});let output='',error='';
  child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
  child.stdout.on('data',s=>output+=s);child.stderr.on('data',s=>error+=s);
  child.on('error',reject);child.on('close',code=>code===0?resolve(output):reject(new Error(error)));
  child.stdin.end(sql);
 });
}
test('isolated research lease, quota and ownership SQL behavior',{skip:!hasSql},()=>{
 const db='research_test_'+Date.now();psql('postgres',`CREATE DATABASE ${db}`);
 try {
 psql(db,readFileSync('supabase/tests/bootstrap.sql','utf8').replace(/^CREATE ROLE .*;\r?\n/gm,''));
 for(const name of readdirSync('supabase/migrations').filter(n=>n.endsWith('.sql')).sort())psql(db,readFileSync('supabase/migrations/'+name,'utf8'));
 assert.equal(psql(db,"SELECT to_regprocedure('public.enqueue_research(uuid,text,jsonb,uuid,integer)') IS NOT NULL").trim(),'t','research enqueue RPC exists');
 psql(db,readFileSync('supabase/tests/behavior_test.sql','utf8'));
 psql(db,readFileSync('supabase/tests/sources_test.sql','utf8'));
 psql(db,readFileSync('supabase/tests/reviewed_candidate_test.sql','utf8'));
 psql(db,readFileSync('supabase/tests/snapshot_test.sql','utf8'));
 psql(db,`BEGIN;
 CREATE FUNCTION pg_temp.ok(b boolean,m text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF b IS DISTINCT FROM true THEN RAISE EXCEPTION '%',m; END IF; END $$;
 INSERT INTO auth.users VALUES('11111111-1111-4111-8111-111111111111'),('22222222-2222-4222-8222-222222222222');
 CREATE TEMP TABLE result AS SELECT public.enqueue_research('11111111-1111-4111-8111-111111111111','RESEARCH','{"topic":"test","said_at":"2026-09-17","timezone":"Asia/Shanghai"}','33333333-3333-4333-8333-333333333333',1) AS value;
 SELECT pg_temp.ok(public.enqueue_research('11111111-1111-4111-8111-111111111111','RESEARCH','{"topic":"test","said_at":"2026-09-17","timezone":"Asia/Shanghai"}','33333333-3333-4333-8333-333333333333',1)=(SELECT value FROM result),'idempotent enqueue');
 SELECT pg_temp.ok((SELECT used=1 FROM private.usage),'only first enqueue costs quota');
 DO $$BEGIN BEGIN PERFORM public.enqueue_research('11111111-1111-4111-8111-111111111111','RESEARCH','{"topic":"changed"}','33333333-3333-4333-8333-333333333333',1);RAISE EXCEPTION 'reused operation accepted';EXCEPTION WHEN invalid_parameter_value THEN NULL;END;END$$;
 DO $$BEGIN BEGIN PERFORM public.enqueue_research('11111111-1111-4111-8111-111111111111','RESEARCH','{"topic":"new","said_at":"2026-09-17","timezone":"Asia/Shanghai"}','44444444-4444-4444-8444-444444444444',1);RAISE EXCEPTION 'quota bypass';EXCEPTION WHEN SQLSTATE 'PT429' THEN NULL;END;END$$;
 SELECT pg_temp.ok((SELECT count(*)=1 FROM public.research_jobs),'quota transaction creates no orphan');
 CREATE TEMP TABLE claimed AS SELECT * FROM public.claim_jobs(1,90);
 SELECT pg_temp.ok((SELECT status='RUNNING' FROM public.research_jobs),'public claim status');
 SELECT pg_temp.ok((SELECT count(*)=0 FROM public.claim_jobs(1,90)),'active lease cannot be claimed twice');
 DO $$BEGIN BEGIN PERFORM public.complete_research_job((SELECT id FROM claimed),gen_random_uuid(),'{"candidates":[]}');RAISE EXCEPTION 'stale lease accepted';EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;END;END$$;
 SELECT public.fail_research_job(id,lease_token,'PROVIDER_UNAVAILABLE') FROM claimed;
 SELECT pg_temp.ok((SELECT status='QUEUED' FROM public.research_jobs),'retry remains queued');
 UPDATE private.jobs SET available_at=now()-interval '1 second';
 TRUNCATE claimed; INSERT INTO claimed SELECT * FROM public.claim_jobs(1,90);
 UPDATE private.jobs SET leased_until=now()-interval '1 second';
 DO $$BEGIN BEGIN PERFORM public.complete_research_job((SELECT id FROM claimed),(SELECT lease_token FROM claimed),'{"candidates":[]}');RAISE EXCEPTION 'expired lease accepted';EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;END;END$$;
 TRUNCATE claimed; INSERT INTO claimed SELECT * FROM public.claim_jobs(1,90);
 SELECT public.fail_research_job(id,lease_token,'PROVIDER_UNAVAILABLE') FROM claimed;
 SELECT pg_temp.ok((SELECT status='FAILED' FROM public.research_jobs),'third failure is terminal');
 SELECT pg_temp.ok((SELECT count(*)=1 FROM public.notifications),'terminal failure notification');
 SELECT public.enqueue_research('11111111-1111-4111-8111-111111111111','RESEARCH','{"topic":"no results","said_at":"2026-09-17","timezone":"Asia/Shanghai"}',gen_random_uuid(),20);
 TRUNCATE claimed;INSERT INTO claimed SELECT * FROM public.claim_jobs(1,90);
 SELECT public.complete_research_job(id,lease_token,'{"candidates":[]}') FROM claimed;
 SELECT pg_temp.ok((SELECT status='SUCCEEDED' FROM public.research_jobs WHERE id=(SELECT id FROM claimed)),'legitimate zero results successful');
 SELECT pg_temp.ok((SELECT count(*)=0 FROM public.candidates),'zero results creates no fake candidates');
 SET LOCAL ROLE authenticated; SELECT set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
 SELECT pg_temp.ok((SELECT count(*)=0 FROM public.research_jobs),'other owner cannot read jobs');
 DO $$BEGIN BEGIN PERFORM public.enqueue_research('11111111-1111-4111-8111-111111111111','RESEARCH','{}',gen_random_uuid(),20);RAISE EXCEPTION 'user can enqueue as arbitrary owner';EXCEPTION WHEN insufficient_privilege THEN NULL;END;END$$;
 RESET ROLE; ROLLBACK;`);
 } finally {psql('postgres',`DROP DATABASE ${db} WITH (FORCE)`);}
});
import { researchRoute, workerRequest, validateResearchResult } from '../supabase/functions/_shared/research.mjs';
const owner='11111111-1111-4111-8111-111111111111',jobid='33333333-3333-4333-8333-333333333333';
const env={SUPABASE_URL:'https://project.supabase.co',SUPABASE_ANON_KEY:'public',SUPABASE_SERVICE_ROLE_KEY:'private',DASHSCOPE_API_KEY:'supplier',QWEN_BASE_URL:'https://dashscope.aliyuncs.com/compatible-mode/v1',QWEN_MODEL:'configured',WORKER_SECRET:'dedicated-worker-secret'};
const source={url:'https://example.org/proof',title:'Evidence',publisher:'Example',published_at:null,excerpt:'原话',quoted_text:'原话',source_type:'OFFICIAL',source_level:'A'};
const record={id:jobid,record_type:'PROMISE',original_text:'原话',subject:'当事人',topic:'测试',lifecycle:'DRAFT',said_at:'2026-09-17',due_start:null,due_end:null,date_text:'',date_precision:'UNKNOWN',timezone:'Asia/Shanghai',verification_criteria:'查看证据',notes:''};
const result=()=>({candidates:[{record:{...record},confidence:0.5,sources:[{...source}]}]});
const job={id:jobid,owner_id:owner,kind:'RESEARCH',lease_token:'44444444-4444-4444-8444-444444444444',payload:{topic:'测试',said_at:'2026-09-17',timezone:'Asia/Shanghai'}};
function setup(overrides={}){const calls=[];return {env:{...env,...overrides.env},calls,fetch:async(u,i={})=>{calls.push({url:String(u),init:i});const path=new URL(u).pathname;if(path==='/auth/v1/user')return Response.json({id:owner});if(path.endsWith('/enqueue_research'))return Response.json({job_id:jobid,status:'QUEUED'});if(path.endsWith('/claim_jobs'))return Response.json(overrides.empty?[]:[job]);if(path.endsWith('/responses'))return Response.json({status:'completed',output:[{type:'web_search_call',status:'completed',action:{sources:[{url:source.url,title:source.title}]}},{type:'message',role:'assistant',content:[{type:'output_text',text:JSON.stringify(overrides.result??result())}]}]});if(path.endsWith('/complete_research_job')||path.endsWith('/fail_research_job'))return Response.json(null);if(path.endsWith('/records'))return Response.json([]);if(path.endsWith('/research_jobs'))return Response.json(overrides.foreign?[]:[{id:jobid,status:'QUEUED',error_code:null}]);if(path.endsWith('/candidates'))return Response.json([]);throw Error('Unexpected '+u);}};}
function req(path,body,token='user-jwt'){return new Request('https://project.supabase.co/functions/v1/'+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});}
test('research creates asynchronous owned job without provider request',async()=>{const f=setup();const r=await researchRoute(req('api/v1/research',{topic:'调查',said_at:'2026-09-17',timezone:'Asia/Shanghai',operation_id:jobid}),f);assert.equal(r.status,202);assert.ok(!f.calls.some(x=>x.url.endsWith('/responses')));assert.equal(JSON.parse(f.calls.at(-1).init.body).p_owner_id,owner);});
test('verify cannot queue foreign record',async()=>{const f=setup();assert.equal((await researchRoute(req('api/v1/verify',{record_id:jobid,expected_revision:1,operation_id:jobid}),f)).status,403);assert.ok(!f.calls.some(x=>x.url.includes('enqueue_research')));});
test('job read cannot leak another owner',async()=>assert.equal((await researchRoute(req('api/v1/jobs/'+jobid),setup({foreign:true}))).status,403));
test('ordinary JWT cannot trigger worker',async()=>{const f=setup();assert.equal((await workerRequest(req('worker',{},'user-jwt'),f)).status,401);assert.equal(f.calls.length,0);});
test('worker completes only through fenced RPC and enables official tools',async()=>{const f=setup();const r=await workerRequest(req('worker',{},env.WORKER_SECRET),f);assert.equal(r.status,200);const output=JSON.parse(f.calls.find(x=>x.url.includes('complete_research_job')).init.body);assert.equal(output.p_lease_token,job.lease_token);assert.equal(output.p_result.candidates[0].sources[0].content_hash.length,64);const tools=JSON.parse(f.calls.find(x=>x.url.endsWith('/responses')).init.body).tools;assert.deepEqual(tools,[{type:'web_search'},{type:'web_extractor'}]);});
test('fabricated model source fails job without committing evidence',async()=>{const p=result();p.candidates[0].sources[0].url='https://fake.example.org';const f=setup({result:p});assert.equal((await workerRequest(req('worker',{},env.WORKER_SECRET),f)).status,200);assert.ok(f.calls.some(x=>x.url.includes('fail_research_job')));assert.ok(!f.calls.some(x=>x.url.includes('complete_research_job')));});
test('unfinished search calls cannot authenticate model evidence',async()=>{
 const f=setup(),original=f.fetch;
 f.fetch=async(u,i)=>{
  const response=await original(u,i);
  if(!String(u).endsWith('/responses'))return response;
  const body=await response.json();body.output[0].status='failed';return Response.json(body);
 };
 await workerRequest(req('worker',{},env.WORKER_SECRET),f);
 assert.ok(f.calls.some(x=>x.url.includes('fail_research_job')));
 assert.ok(!f.calls.some(x=>x.url.includes('complete_research_job')));
});
for(const url of ['http://example.org','https://127.0.0.1','https://169.254.169.254/latest','https://private.local/path','https://a.example.org:8080'])test('unsafe tool source rejected '+url,async()=>{const p=result();p.candidates[0].sources[0].url=url;await assert.rejects(validateResearchResult(p,[url],job));});
test('research without actual source cannot be confirmed candidate',async()=>{const p=result();p.candidates[0].sources=[];await assert.rejects(validateResearchResult(p,[],job));});
test('insufficient check evidence is only unverifiable',async()=>{await assert.rejects(validateResearchResult({suggested_status:'FULFILLED',confidence:1,summary:'猜测',sources:[]},[],{...job,kind:'CHECK'}));});
// Complete database flow uses only authenticated acceptance/confirmation for records.
test('SQL successful research acceptance, revision fence and user confirmation',{skip:!hasSql},()=>{
 const db='research_success_'+Date.now();psql('postgres',`CREATE DATABASE ${db}`);
 try{
 psql(db,readFileSync('supabase/tests/bootstrap.sql','utf8').replace(/^CREATE ROLE .*;\r?\n/gm,''));for(const name of readdirSync('supabase/migrations').filter(n=>n.endsWith('.sql')).sort())psql(db,readFileSync('supabase/migrations/'+name,'utf8'));
 const savedSource={...source,canonical_url:null,retrieved_at:'2026-09-17T00:00:00Z',content_hash:'a'.repeat(64)};
 const resultSql=JSON.stringify({candidates:[{record,confidence:0.5,sources:[savedSource]}]}).replaceAll("'","''");
 psql(db,`BEGIN;
 CREATE FUNCTION pg_temp.ok(b boolean,m text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF b IS DISTINCT FROM true THEN RAISE EXCEPTION '%',m; END IF; END $$;
 INSERT INTO auth.users VALUES('${owner}'),('22222222-2222-4222-8222-222222222222');
 SELECT public.enqueue_research('${owner}','RESEARCH','{"topic":"test","said_at":"2026-09-17","timezone":"Asia/Shanghai"}',gen_random_uuid(),20);
 CREATE TEMP TABLE claimed AS SELECT * FROM public.claim_jobs(1,90);
 SELECT public.complete_research_job(id,lease_token,'${resultSql}') FROM claimed;
 SELECT pg_temp.ok((SELECT count(*)=0 FROM public.records),'AI cannot create record');
 SELECT pg_temp.ok((SELECT status='SUCCEEDED' FROM public.research_jobs),'success status');
 SELECT pg_temp.ok((SELECT proposal->>'original_text'='原话' AND NOT(proposal?'sources') FROM public.candidates),'proposal is RecordInput only');
 SELECT pg_temp.ok((SELECT verified_by_tool FROM public.sources),'tool evidence persisted');
 SET LOCAL ROLE authenticated;SELECT set_config('request.jwt.claim.sub','${owner}',true);
 SELECT public.accept_candidate(id,gen_random_uuid()) FROM public.candidates;
 SELECT public.accept_candidate(id,gen_random_uuid()) FROM public.candidates;
 SELECT pg_temp.ok((SELECT count(*)=1 FROM public.records),'accept double click idempotent');
 SELECT pg_temp.ok((SELECT lifecycle='ACTIVE' FROM public.records),'explicit acceptance activates draft');
 RESET ROLE;
 SELECT public.enqueue_research('${owner}','CHECK',jsonb_build_object('record_id',id,'expected_revision',revision),gen_random_uuid(),20) FROM public.records;
 TRUNCATE claimed;INSERT INTO claimed SELECT * FROM public.claim_jobs(1,90);
 SELECT public.complete_research_job(id,lease_token,'{"suggested_status":"UNVERIFIABLE","confidence":0,"summary":"暂无证据","sources":[]}') FROM claimed;
 SELECT pg_temp.ok((SELECT confirmed_status IS NULL FROM public.records),'AI never confirms');
 SET LOCAL ROLE authenticated;SELECT set_config('request.jwt.claim.sub','${owner}',true);
 SELECT public.confirm_check(id,record_revision,'UNVERIFIABLE','用户审核') FROM public.checks;
 SELECT pg_temp.ok((SELECT confirmed_status='UNVERIFIABLE' AND confirmed_by=owner_id FROM public.records),'only user confirms');
 RESET ROLE;
 SET LOCAL ROLE service_role;
 DO $$BEGIN BEGIN INSERT INTO public.sources(owner_id,record_id,url,title) SELECT '${owner}',id,'https://example.org/unsafe','bypass' FROM public.records;RAISE EXCEPTION 'direct evidence bypass';EXCEPTION WHEN insufficient_privilege THEN NULL;END;END$$;
 RESET ROLE;
 ROLLBACK;`);
 }finally{psql('postgres',`DROP DATABASE ${db} WITH (FORCE)`);}
});
test('SQL stale revisions and due scan remain bounded and idempotent',{skip:!hasSql},()=>{
 const db='research_due_'+Date.now();psql('postgres',`CREATE DATABASE ${db}`);
 try{
 psql(db,readFileSync('supabase/tests/bootstrap.sql','utf8').replace(/^CREATE ROLE .*;\r?\n/gm,''));for(const name of readdirSync('supabase/migrations').filter(n=>n.endsWith('.sql')).sort())psql(db,readFileSync('supabase/migrations/'+name,'utf8'));
 psql(db,`BEGIN;
 CREATE FUNCTION pg_temp.ok(b boolean,m text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF b IS DISTINCT FROM true THEN RAISE EXCEPTION '%',m; END IF; END $$;
 INSERT INTO auth.users VALUES('${owner}');
 SET LOCAL ROLE authenticated;SELECT set_config('request.jwt.claim.sub','${owner}',true);
 SELECT public.upsert_record('{"id":"${jobid}","record_type":"PROMISE","subject":"测试","original_text":"承诺","lifecycle":"ACTIVE","said_at":"2020-01-01","due_start":"2020-01-02","due_end":"2020-01-02","date_text":"次日","date_precision":"DAY","timezone":"Asia/Shanghai"}',0,gen_random_uuid());
 RESET ROLE;
 SELECT pg_temp.ok(public.enqueue_due(1,20)=1,'cron schedules due record');
 SELECT pg_temp.ok(public.enqueue_due(1,20)=0,'cron does not duplicate same revision');
 SELECT pg_temp.ok((SELECT used=1 FROM private.usage),'cron repeat does not charge twice');
 CREATE TEMP TABLE claimed AS SELECT * FROM public.claim_jobs(1,90);
 SET LOCAL ROLE authenticated;SELECT public.upsert_record('{"id":"${jobid}","original_text":"更新承诺"}',1,gen_random_uuid());RESET ROLE;
 DO $$BEGIN BEGIN PERFORM public.complete_research_job((SELECT id FROM claimed),(SELECT lease_token FROM claimed),'{"suggested_status":"UNVERIFIABLE","confidence":0,"summary":"旧版本","sources":[]}');RAISE EXCEPTION 'stale revision wrote check';EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;END;END$$;
 SELECT pg_temp.ok((SELECT count(*)=0 FROM public.checks),'stale completion atomic rollback');
 SELECT public.fail_research_job(id,lease_token,'STALE_RECORD') FROM claimed;
 SELECT pg_temp.ok((SELECT status='FAILED' FROM public.research_jobs),'stale revision terminal');
 SELECT pg_temp.ok(public.enqueue_due(1,20)=1,'new revision gets own due job');
 TRUNCATE claimed;INSERT INTO claimed SELECT * FROM public.claim_jobs(1,90);
 UPDATE private.jobs SET attempts=3,leased_until=now()-interval '1 second' WHERE id=(SELECT id FROM claimed);
 SELECT pg_temp.ok((SELECT count(*)=0 FROM public.claim_jobs(1,90)),'crashed last attempt not reclaimed');
 SELECT pg_temp.ok((SELECT bool_and(status='FAILED') FROM public.research_jobs),'crash exhaustion reflected publicly');
 SELECT pg_temp.ok((SELECT count(*)=2 FROM public.notifications),'one failure notification per job');
 ROLLBACK;`);
 }finally{psql('postgres',`DROP DATABASE ${db} WITH (FORCE)`);}
});
test('legitimate empty research succeeds without fictional candidates',async()=>{assert.deepEqual(await validateResearchResult({candidates:[]},[],job),{candidates:[]});const f=setup({result:{candidates:[]}});assert.equal((await workerRequest(req('worker',{},env.WORKER_SECRET),f)).status,200);assert.ok(f.calls.some(x=>x.url.includes('complete_research_job')));});
test('combined GET job response cannot exceed output byte budget',async()=>{const f=setup();const original=f.fetch;f.fetch=async(u,i)=>{const path=new URL(u).pathname;if(path.endsWith('/candidates'))return Response.json(Array(5).fill({id:jobid,proposal:record,confidence:0.5,accepted_record_id:null}));if(path.endsWith('/sources'))return Response.json(Array(3).fill({...source,excerpt:'测'.repeat(2000),quoted_text:'测'.repeat(2000)}));return original(u,i);};assert.equal((await researchRoute(req('api/v1/jobs/'+jobid),f)).status,503);});

test('SQL concurrent workers claim one job exactly once',{skip:!hasSql},async()=>{
 const db='research_concurrent_'+Date.now();psql('postgres',`CREATE DATABASE ${db}`);
 try{
  psql(db,readFileSync('supabase/tests/bootstrap.sql','utf8').replace(/^CREATE ROLE .*;\r?\n/gm,''));
  for(const name of readdirSync('supabase/migrations').filter(n=>n.endsWith('.sql')).sort())psql(db,readFileSync('supabase/migrations/'+name,'utf8'));
  psql(db,`INSERT INTO auth.users VALUES('${owner}');SELECT public.enqueue_research('${owner}','RESEARCH','{"topic":"parallel","said_at":"2026-09-17","timezone":"Asia/Shanghai"}',gen_random_uuid(),20);`);
  const sql=`BEGIN;SET LOCAL ROLE service_role;SELECT count(*) FROM public.claim_jobs(1,90);SELECT pg_sleep(0.3);COMMIT;`;
  // Independent PostgreSQL sessions overlap their open transactions, retaining row locks.
  const claimed=await Promise.all([psqlConcurrent(db,sql),psqlConcurrent(db,sql)]);
  assert.deepEqual(claimed.map(value=>Number(value.trim())).sort(),[0,1]);
  assert.equal(psql(db,"SELECT attempts FROM private.jobs").trim(),'1');
 }finally{psql('postgres',`DROP DATABASE ${db} WITH (FORCE)`);}
});
