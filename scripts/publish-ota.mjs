import { createHash, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, stat, mkdtemp, writeFile, unlink, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const PROJECT = 'https://skeghmapzrmahxehazlp.supabase.co';
const STORAGE = `${PROJECT}/storage/v1`;
const PUBLIC = `${STORAGE}/object/public/app-updates/`;
const MAX_APK = 67108864;
const MAX_MANIFEST = 65536;
const PIN = '748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2';
const execFileAsync = promisify(execFile);
const fail = message => { throw new Error(message); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const isInt = (n, low, high = 2100000000) => Number.isSafeInteger(n) && n >= low && n <= high;
const textLength = (value, max) => typeof value === 'string' && value.trim().length > 0 && [...value].length <= max;

export function validateManifest(value) {
  const fields = ['schemaVersion', 'packageName', 'versionCode', 'versionName', 'minSdk', 'apkUrl', 'sha256', 'sizeBytes', 'notes', 'publishedAt'];
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== fields.length || fields.some(key => !Object.hasOwn(value, key))) fail('Invalid OTA manifest fields.');
  if (value.schemaVersion !== 1 || value.packageName !== 'com.boomerang.app' || !isInt(value.versionCode, 1) || !isInt(value.minSdk, 26) || !textLength(value.versionName, 80) || !textLength(value.notes, 4000)) fail('Invalid OTA manifest metadata.');
  if (value.apkUrl !== `${PUBLIC}android/${value.versionCode}/app.apk` || typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256) || !isInt(value.sizeBytes, 1, MAX_APK)) fail('Invalid OTA download identity.');
  const timestamp = value.publishedAt;
  if (typeof timestamp !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(timestamp)) fail('Invalid publication time.');
  const date = new Date(timestamp);
  if (!Number.isFinite(date.valueOf()) || date.toISOString().slice(0, 19) !== timestamp.slice(0, 19)) fail('Invalid publication time.');
  return value;
}

export function parseApkMetadata(badging, certs) {
  const packageLine = badging.split(/\r?\n/).find(line => line.startsWith('package: ')) ?? '';
  const attr = name => packageLine.match(new RegExp(`(?:^| )${name}='([^']*)'`))?.[1];
  const metadata = { packageName: attr('name'), versionCode: Number(attr('versionCode')), versionName: attr('versionName'), minSdk: Number(badging.match(/^sdkVersion:'(\d+)'$/m)?.[1]) };
  const signers = [...certs.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$/gm)];
  if (signers.length !== 1 || signers[0][1].toLowerCase() !== PIN) fail('APK release signer does not match the pinned certificate.');
  validateManifest({ schemaVersion: 1, ...metadata, apkUrl: `${PUBLIC}android/${metadata.versionCode}/app.apk`, sha256: '0'.repeat(64), sizeBytes: 1, notes: 'validation', publishedAt: '2026-01-01T00:00:00Z' });
  return metadata;
}

export async function readBounded(response, limit) {
  const length = response.headers.get('content-length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > limit)) {
    await response.body?.cancel();
    fail('Response exceeds permitted size.');
  }
  if (!response.body) return Buffer.alloc(0);
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > limit) { await reader.cancel(); fail('Response exceeds permitted size.'); }
      chunks.push(Buffer.from(value));
    }
  } finally { reader.releaseLock(); }
  const encoding = response.headers.get('content-encoding')?.trim().toLowerCase();
  // Native fetch exposes decoded bytes but retains the compressed wire length.
  if ((!encoding || encoding === 'identity') && length !== null && Number(length) !== total) fail('Response byte count differs from Content-Length.');
  return Buffer.concat(chunks, total);
}

function missing(status, body) {
  if (status === 404) return true;
  if (status !== 400) return false;
  try { return String(JSON.parse(body).statusCode) === '404'; } catch { return false; }
}

// metadata is an internal test seam. The CLI always obtains it by verifying the
// exact snapshotted bytes with aapt and apksigner; it accepts no metadata override.
export async function publishOta({ apk, metadata, notes, serviceKey, fetchImpl = fetch, now = () => new Date() }) {
  if (!serviceKey || /[\r\n]/.test(serviceKey)) fail('Missing or invalid BOOMERANG_SUPABASE_SERVICE_ROLE_KEY.');
  if (!Buffer.isBuffer(apk) || apk.length < 1 || apk.length > MAX_APK) fail('APK size is outside the permitted range.');
  const manifest = validateManifest({ schemaVersion: 1, ...metadata, apkUrl: `${PUBLIC}android/${metadata.versionCode}/app.apk`, sha256: hash(apk), sizeBytes: apk.length, notes, publishedAt: now().toISOString() });
  const lockData = { id: randomUUID(), startedAt: now().toISOString() };
  const manifestBytes = Buffer.byteLength(JSON.stringify(manifest));
  if (manifestBytes > MAX_MANIFEST) fail('Manifest exceeds permitted size.');
  const requiredObjectBytes = Math.max(apk.length, manifestBytes, Buffer.byteLength(JSON.stringify(lockData)));
  const request = async (path, { method = 'GET', body, publicRead = false, limit = MAX_MANIFEST, headers = {} } = {}) => {
    const url = publicRead ? `${PUBLIC}${path}` : `${STORAGE}${path}`;
    // Timeout covers both headers and streamed body. No redirects, URLs supplied
    // by remote content, provider body text, or nested errors reach the caller.
    try {
      const response = await fetchImpl(url, { method, body, redirect: 'error', signal: AbortSignal.timeout(120000), headers: { ...(!publicRead ? { authorization: `Bearer ${serviceKey}`, apikey: serviceKey } : {}), ...(method === 'GET' ? { 'cache-control': 'no-cache' } : {}), ...headers } });
      const bytes = await readBounded(response, limit);
      if (response.status >= 300 && response.status < 400) fail('Redirect refused.');
      return { status: response.status, ok: response.ok, bytes };
    } catch { fail('Storage request failed or exceeded the time/size limit.'); }
  };
  const expectOk = result => { if (!result.ok) fail(`Storage operation failed (HTTP ${result.status}).`); return result; };
  const object = key => `/object/app-updates/${key}`;
  const jsonWrite = (path, value, method = 'POST', upsert = false) => request(path, { method, body: Buffer.from(JSON.stringify(value)), headers: { 'content-type': 'application/json', 'cache-control': 'max-age=0', 'x-upsert': String(upsert) } });
  const bucket = await request('/bucket/app-updates');
  if (missing(bucket.status, bucket.bytes)) {
    // Omit a per-bucket limit to inherit the enforced tenant global limit. The
    // protocol's 64 MiB ceiling may exceed a Free project's configured limit.
    expectOk(await jsonWrite('/bucket', { id: 'app-updates', name: 'app-updates', public: true, allowed_mime_types: ['application/vnd.android.package-archive', 'application/json'] }));
  } else {
    expectOk(bucket);
    let existing;
    try { existing = JSON.parse(bucket.bytes); } catch { fail('Existing bucket metadata is invalid.'); }
    if (existing.id !== 'app-updates' || existing.public !== true || (existing.file_size_limit != null && (!Number.isSafeInteger(existing.file_size_limit) || existing.file_size_limit < requiredObjectBytes)) || (existing.allowed_mime_types != null && (!Array.isArray(existing.allowed_mime_types) || !['application/vnd.android.package-archive', 'application/json'].every(type => existing.allowed_mime_types.includes(type))))) fail('Existing bucket is private or incompatible; review its configuration without exposing preexisting files.');
  }
  // Atomic create serializes CLI and workflow publishers across machines. Never
  // steal/expire a lock while its owner might still be promoting a release.
  const lock = await jsonWrite(object('android/publish.lock'), lockData);
  if (!lock.ok) fail('Publication lock unavailable; confirm no publisher is active before manual recovery.');
  try {
    const previous = await request(object('android/latest.json'));
    let latest;
    if (!missing(previous.status, previous.bytes)) {
      expectOk(previous);
      try { latest = validateManifest(JSON.parse(previous.bytes)); } catch { fail('Existing manifest is invalid; publication stopped.'); }
      if (latest.versionCode > manifest.versionCode) fail('Downgrade publication refused.');
      if (latest.versionCode === manifest.versionCode && Object.keys(manifest).some(key => key !== 'publishedAt' && manifest[key] !== latest[key])) fail('Conflicting same-version publication refused.');
    }
    const key = `android/${manifest.versionCode}/app.apk`;
    const upload = await request(object(key), { method: 'POST', body: apk, headers: { 'content-type': 'application/vnd.android.package-archive', 'cache-control': 'max-age=31536000, immutable', 'x-upsert': 'false' } });
    // An immutable object may already exist after an interrupted publish. Accept
    // a conflict only after verifying its public bytes against this exact APK.
    if (!upload.ok && upload.status !== 409 && upload.status !== 400) expectOk(upload);
    const publicApk = expectOk(await request(key, { publicRead: true, limit: MAX_APK }));
    if (publicApk.bytes.length !== manifest.sizeBytes || hash(publicApk.bytes) !== manifest.sha256) fail('Public APK verification failed; latest was not promoted.');
    const expected = latest?.versionCode === manifest.versionCode ? latest : manifest;
    if (expected === manifest) expectOk(await jsonWrite(object('android/latest.json'), manifest, 'POST', true));
    const published = expectOk(await request('android/latest.json', { publicRead: true }));
    let confirmed;
    try { confirmed = validateManifest(JSON.parse(published.bytes)); } catch { fail('Published manifest public verification failed.'); }
    if (Object.keys(expected).some(key => confirmed[key] !== expected[key])) fail('Published manifest public verification mismatch.');
    return expected;
  } finally {
    expectOk(await request(object('android/publish.lock'), { method: 'DELETE' }));
  }
}

export async function inspectApk(apkBytes, sdk = process.env.ANDROID_HOME, runTool = execFileAsync) {
  if (!sdk) fail('ANDROID_HOME must point to the SDK with build-tools 35.0.0.');
  const dir = await mkdtemp(join(tmpdir(), 'boomerang-ota-'));
  try {
    const path = join(dir, 'app.apk');
    await writeFile(path, apkBytes, { mode: 0o600 });
    const buildTools = join(sdk, 'build-tools', '35.0.0');
    // Windows aapt does not reliably decode Unicode path arguments. Its Unicode
    // working directory is handled by the OS; the APK argument stays plain ASCII.
    const options = { cwd: dir, timeout: 120000, maxBuffer: 1024 * 1024, windowsHide: true };
    const aapt = await runTool(join(buildTools, process.platform === 'win32' ? 'aapt.exe' : 'aapt'), ['dump', 'badging', 'app.apk'], options);
    const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const apksigner = await runTool(java, ['-jar', join(buildTools, 'lib', 'apksigner.jar'), 'verify', '--verbose', '--print-certs', 'app.apk'], options);
    return parseApkMetadata(aapt.stdout.replace(/\r/g, ''), apksigner.stdout);
  } catch { fail('APK inspection failed: verify SDK/Java, package/version/minSdk and pinned release signature.'); }
  finally {
    await unlink(join(dir, 'app.apk')).catch(error => { if (error.code !== 'ENOENT') throw error; });
    await rmdir(dir);
  }
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === '--help') {
    console.log('node scripts/publish-ota.mjs --apk <signed.apk> --notes <UTF-8 notes.txt>\nRequires ANDROID_HOME (build-tools 35.0.0), Java, BOOMERANG_SUPABASE_SERVICE_ROLE_KEY.');
    return;
  }
  const values = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--apk', '--notes'].includes(args[i]) || !args[i + 1] || values[args[i]]) fail('Usage: node scripts/publish-ota.mjs --apk <signed.apk> --notes <UTF-8 notes.txt>');
    values[args[i]] = args[i + 1];
  }
  if (!values['--apk'] || !values['--notes']) fail('Both --apk and --notes are required.');
  const apkStat = await stat(values['--apk']);
  const notesStat = await stat(values['--notes']);
  if (!apkStat.isFile() || apkStat.size < 1 || apkStat.size > MAX_APK || !notesStat.isFile() || notesStat.size > MAX_MANIFEST) fail('Input file size is outside the permitted range.');
  const apk = await readFile(values['--apk']);
  if (apk.length !== apkStat.size) fail('APK changed while reading; retry with a stable artifact.');
  const notes = new TextDecoder('utf-8', { fatal: true }).decode(await readFile(values['--notes'])).trim();
  const metadata = await inspectApk(apk);
  const published = await publishOta({ apk, metadata, notes, serviceKey: process.env.BOOMERANG_SUPABASE_SERVICE_ROLE_KEY });
  console.log(JSON.stringify(published, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(() => { console.error('OTA publication failed. Check input files, pinned signing certificate, SDK, service Secret, remote version and publication lock. No secret or provider response is logged.'); process.exitCode = 1; });
}
