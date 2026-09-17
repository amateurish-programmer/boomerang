import { ApiError, analyzeOutput, chatOutput, limitedJson, requestInput, uuid } from './schemas.mjs';
import { QwenProvider } from './providers.mjs';
const headers={'content-type':'application/json; charset=utf-8','cache-control':'no-store'};
export async function handleRequest(request, {env, fetch:fetcher=fetch, now=()=>new Date()} ) {
  const requestId=crypto.randomUUID();
  // A single budget covers authentication, quota, provider body streaming and persistence.
  const signal=AbortSignal.timeout(45000);
  try {
    const path=new URL(request.url).pathname;
    const match=/^(?:\/functions\/v1)?\/api\/v1\/(analyze|chat)$/.exec(path);
    if(!match) throw new ApiError(404,'NOT_FOUND');
    if(request.method!=='POST') throw new ApiError(405,'METHOD_NOT_ALLOWED');
    const authorization=request.headers.get('authorization');
    if(!authorization || !/^Bearer [^\s]+$/i.test(authorization)) throw new ApiError(401,'UNAUTHORIZED');
    if(!env.SUPABASE_URL || !env.SUPABASE_ANON_KEY) throw new ApiError(503,'NOT_CONFIGURED');
    const auth=await fetcher(env.SUPABASE_URL+'/auth/v1/user',{headers:{apikey:env.SUPABASE_ANON_KEY,authorization},signal,redirect:'error'});
    if(auth.status===401 || auth.status===403) throw new ApiError(401,'UNAUTHORIZED');
    if(!auth.ok) throw new ApiError(503,'AUTH_UNAVAILABLE');
    const user=await limitedJson(auth);try {uuid(user.id);} catch {throw new ApiError(401,'UNAUTHORIZED');}
    let input; const mode=match[1];
    try {input=await limitedJson(request,32768);requestInput(input,mode);} catch {throw new ApiError(400,'INVALID_INPUT');}
    if(!env.SUPABASE_SERVICE_ROLE_KEY || !env.DASHSCOPE_API_KEY || !env.QWEN_BASE_URL || !env.QWEN_MODEL) throw new ApiError(503,'NOT_CONFIGURED');
    const provider=new QwenProvider(env,fetcher,signal);
    const serviceHeaders={apikey:env.SUPABASE_SERVICE_ROLE_KEY,authorization:`Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,'content-type':'application/json'};
    async function rest(path,body) {
      const result=await fetcher(env.SUPABASE_URL+'/rest/v1/'+path,{method:body===undefined?'GET':'POST',headers:serviceHeaders,body:body===undefined?undefined:JSON.stringify(body),signal,redirect:'error'});
      if(!result.ok) throw new ApiError(503,'STORAGE_UNAVAILABLE');
      return result;
    }
    let history=[];const session=input.session_id??crypto.randomUUID();
    if(input.session_id) {
      const sessions=await limitedJson(await rest(`ai_sessions?select=id&owner_id=eq.${user.id}&id=eq.${session}&limit=1`));
      if(!Array.isArray(sessions) || sessions.length!==1) throw new ApiError(403,'SESSION_FORBIDDEN');
      const messages=await limitedJson(await rest(`ai_messages?select=role,content&owner_id=eq.${user.id}&session_id=eq.${session}&order=created_at.desc,id.desc&limit=12`));
      history=messages.reverse().map(({role,content})=>({role,content}));
    }
    const configuredLimit=Number(env.AI_DAILY_LIMIT??20);
    if(!Number.isInteger(configuredLimit) || configuredLimit<1 || configuredLimit>1000) throw new ApiError(503,'NOT_CONFIGURED');
    const allowed=await limitedJson(await rest('rpc/consume_quota',{p_owner_id:user.id,p_day:now().toISOString().slice(0,10),p_limit:configuredLimit}));
    if(allowed!==true) throw new ApiError(429,'QUOTA_EXCEEDED');
    let output;
    try {output=mode==='analyze'?analyzeOutput(await provider.analyze(input),input):chatOutput(await provider.chat(input,history));}
    catch {throw new ApiError(503,'PROVIDER_UNAVAILABLE');}
    if(!input.session_id) await rest('ai_sessions',{id:session,owner_id:user.id,title:[...input.text].slice(0,100).join('')});
    // A single REST bulk INSERT keeps the user/assistant pair atomic. No records endpoint is used.
    const completedAt=now().getTime();
    await rest('ai_messages',[
      {owner_id:user.id,session_id:session,role:'user',content:input.text,created_at:new Date(completedAt-1).toISOString()},
      {owner_id:user.id,session_id:session,role:'assistant',content:mode==='analyze'?JSON.stringify(output):output.message,created_at:new Date(completedAt).toISOString()}
    ]);
    return new Response(JSON.stringify(mode==='analyze'?output:{session_id:session,message:output.message}),{status:200,headers});
  } catch(error) {
    const safe=error instanceof ApiError?error:new ApiError(503,'SERVICE_UNAVAILABLE');
    // Do not log input, JWTs, raw provider responses, or error.message from external systems.
    return new Response(JSON.stringify({error:{code:safe.code,message:({401:'请先登录',400:'输入不符合要求',403:'无法访问此对话',429:'今日额度已用完',503:'服务暂不可用，请稍后再试'})[safe.status]??'请求不可用',request_id:requestId}}),{status:safe.status,headers});
  }
}
