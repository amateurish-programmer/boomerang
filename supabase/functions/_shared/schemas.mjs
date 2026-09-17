export class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
export function requireValue(value) { if (!value) throw new Error('Invalid schema'); }
export function shape(value, allowed, required = allowed) {
  requireValue(value && typeof value === 'object' && !Array.isArray(value));
  requireValue(Object.keys(value).every(k => allowed.includes(k)) && required.every(k => Object.hasOwn(value, k)));
}
export function string(value, max, min = 0) {
  requireValue(typeof value === 'string' && [...value].length <= max && [...value].length >= min);
}
export function uuid(value) { requireValue(typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)); }
export function date(value) {
  requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && value.slice(0,4) !== '0000');
  const parsed = new Date(value + 'T00:00:00Z');
  requireValue(Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0,10) === value);
}
export function timezone(value) { string(value,80,1); new Intl.DateTimeFormat('en', { timeZone:value }).format(); }
export function requestInput(body, mode) {
  shape(body, mode === 'analyze' ? ['text','said_at','timezone'] : ['text','session_id'], mode === 'analyze' ? ['text','said_at','timezone'] : ['text']);
  string(body.text,4000,1); requireValue(body.text.trim().length > 0);
  if (mode === 'analyze') { date(body.said_at); timezone(body.timezone); }
  else if (body.session_id !== undefined) uuid(body.session_id);
}
export function analyzeOutput(value, input) {
  shape(value,['draft','questions']); shape(value.draft,['record','confidence','sources']);
  const d=value.draft, r=d.record;
  requireValue(typeof d.confidence === 'number' && Number.isFinite(d.confidence) && d.confidence >= 0 && d.confidence <= 1);
  requireValue(Array.isArray(d.sources) && d.sources.length === 0);
  shape(r,['id','record_type','original_text','subject','topic','lifecycle','said_at','due_start','due_end','date_text','date_precision','timezone','verification_criteria','notes','capsule_locked_at','capsule_unlock_at','deleted_at'],['id','record_type','original_text','subject','lifecycle','said_at','due_start','due_end','date_text','date_precision','timezone','verification_criteria']);
  uuid(r.id); requireValue(['FLAG','PROMISE','PREDICTION','STATEMENT','MILESTONE'].includes(r.record_type));
  requireValue(r.lifecycle === 'DRAFT' && r.original_text === input.text && r.said_at === input.said_at && r.timezone === input.timezone);
  string(r.original_text,4000,1); string(r.subject,200,1); string(r.date_text,500); string(r.verification_criteria,4000);
  for (const [key,max] of [['topic',200],['notes',4000]]) if (r[key] !== undefined) string(r[key],max);
  for (const key of ['capsule_locked_at','capsule_unlock_at','deleted_at']) requireValue(r[key] === undefined || r[key] === null);
  requireValue(['UNKNOWN','DAY','MONTH','QUARTER','YEAR','RANGE'].includes(r.date_precision));
  requireValue((r.due_start === null) === (r.due_end === null));
  if (r.date_precision === 'UNKNOWN') requireValue(r.due_start === null && r.due_end === null);
  else { date(r.due_start); date(r.due_end); requireValue(r.due_start <= r.due_end && r.date_text.trim().length > 0); }
  if(r.date_precision==='DAY') requireValue(r.due_start===r.due_end);
  if(['MONTH','QUARTER','YEAR'].includes(r.date_precision)) {
    const start=new Date(r.due_start+'T00:00:00Z');
    requireValue(start.getUTCDate()===1);
    const month=start.getUTCMonth();
    if(r.date_precision==='QUARTER') requireValue(month%3===0);
    if(r.date_precision==='YEAR') requireValue(month===0);
    start.setUTCMonth(month+({MONTH:1,QUARTER:3,YEAR:12})[r.date_precision]);
    start.setUTCDate(start.getUTCDate()-1);
    requireValue(start.toISOString().slice(0,10)===r.due_end);
  }
  requireValue(Array.isArray(value.questions) && value.questions.length <= 10);
  value.questions.forEach(q=>string(q,500,1));
  if(r.date_precision==='UNKNOWN') requireValue(value.questions.length>0);
  // ai_messages.content uses PostgreSQL character length, not UTF-16 units.
  string(JSON.stringify(value),16000);
  return value;
}
export function chatOutput(value) { shape(value,['message']); string(value.message,8000,1); return value; }
export async function limitedJson(body, maxBytes = 131072) {
  const reader=body.body?.getReader(); requireValue(reader);
  let size=0; const chunks=[];
  try { while(true) { const {done,value}=await reader.read(); if(done) break; size+=value.byteLength; requireValue(size<=maxBytes); chunks.push(value); } }
  finally { await reader.cancel().catch(()=>{}); reader.releaseLock(); }
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}
  return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
}
