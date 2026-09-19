import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parseApkMetadata, validateManifest, publishOta, readBounded, inspectApk } from './publish-ota.mjs';

const base = 'https://skeghmapzrmahxehazlp.supabase.co';
const publicBase = `${base}/storage/v1/object/public/app-updates/`;
const signer = '748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2';
const apk = Buffer.from('signed apk fixture');
const metadata = { packageName: 'com.boomerang.app', versionCode: 6, versionName: '0.4.1', minSdk: 26 };
const manifest = { schemaVersion: 1, ...metadata, apkUrl: `${publicBase}android/6/app.apk`, sha256: createHash('sha256').update(apk).digest('hex'), sizeBytes: apk.length, notes: '修复更新体验', publishedAt: '2026-09-19T00:00:00.000Z' };
const badging = "package: name='com.boomerang.app' versionCode='6' versionName='0.4.1' platformBuildVersionName='15'\nsdkVersion:'26'\ntargetSdkVersion:'35'\n";
const certs = `Signer #1 certificate SHA-256 digest: ${signer}\n`;

test('reads package, integer version, name and minimum SDK from actual tool output', () => {
  assert.deepEqual(parseApkMetadata(badging, certs), metadata);
});
test('APK tools receive a relative ASCII snapshot name and inspect exact bytes from their working directory', async () => {
  const tool = async (_command, args, options) => {
    assert.equal(args.at(-1), 'app.apk');
    assert.deepEqual(await readFile(resolve(options.cwd, args.at(-1))), apk);
    return { stdout: args[0] === 'dump' ? badging : certs };
  };
  assert.deepEqual(await inspectApk(apk, 'fixture-sdk', tool), metadata);
});
test('rejects APKs with wrong signer, extra signer, package, missing SDK or invalid version', () => {
  for (const [b, c] of [[badging, certs.replace(signer, 'a'.repeat(64))], [badging, certs + certs.replace('#1', '#2')], [badging.replace('com.boomerang.app', 'other.app'), certs], [badging.replace("sdkVersion:'26'", ''), certs], [badging.replace("versionCode='6'", "versionCode='0'"), certs]]) {
    assert.throws(() => parseApkMetadata(b, c));
  }
});
test('accepts manifest and rejects malformed or untrusted fields', () => {
  assert.deepEqual(validateManifest(manifest), manifest);
  for (const change of [{ apkUrl: manifest.apkUrl + '?key=secret' }, { apkUrl: 'https://evil.example/app.apk' }, { sha256: 'A'.repeat(64) }, { sizeBytes: 67108865 }, { versionCode: 1.5 }, { versionCode: 2147483648 }, { minSdk: 25 }, { notes: '' }, { notes: 'x'.repeat(4001) }, { publishedAt: '2026-02-30T00:00:00Z' }, { schemaVersion: 2 }, { extra: true }]) {
    assert.throws(() => validateManifest({ ...manifest, ...change }));
  }
});

// Only the external HTTP boundary is simulated: the real publisher controls every
// request, hash, promotion, guard and error. No live keys or network are used.
function storage({ latest, corrupt = false, locked = false, existingApk, redirect = false, existingBucket, globalLimit = Infinity } = {}) {
  const objects = new Map();
  if (latest) objects.set('android/latest.json', Buffer.from(JSON.stringify(latest)));
  if (existingApk) objects.set('android/6/app.apk', existingApk);
  if (locked) objects.set('android/publish.lock', Buffer.from('other owner'));
  const calls = [];
  let bucket = Boolean(existingBucket);
  const fetchImpl = async (url, options = {}) => {
    const method = options.method ?? 'GET';
    calls.push({ url, ...options, method });
    const pathname = new URL(url).pathname;
    if (pathname.startsWith('/storage/v1/bucket')) {
      if (method === 'GET') return new Response(bucket ? JSON.stringify(existingBucket ?? { id: 'app-updates', public: true }) : '{"statusCode":"404","message":"Bucket not found"}', { status: bucket ? 200 : 400 });
      if (JSON.parse(options.body).file_size_limit > globalLimit) return new Response('{}', { status: 413 });
      bucket = true;
      return new Response('{}');
    }
    const isPublic = pathname.includes('/object/public/');
    const key = pathname.split('/app-updates/')[1];
    if (method === 'GET') {
      if (redirect && isPublic && key.endsWith('.apk')) return new Response(null, { status: 302, headers: { location: 'https://evil.example/apk' } });
      if (!objects.has(key)) return new Response('{"statusCode":"404","message":"Object not found"}', { status: 400 });
      const bytes = corrupt && isPublic && key.endsWith('.apk') ? Buffer.from('corrupted') : objects.get(key);
      return new Response(bytes);
    }
    if (method === 'DELETE') { objects.delete(key); return new Response('{}'); }
    if (Buffer.byteLength(options.body) > globalLimit) return new Response('{}', { status: 413 });
    if (objects.has(key) && options.headers['x-upsert'] !== 'true') return new Response('{}', { status: 409 });
    objects.set(key, Buffer.from(options.body));
    return new Response('{}');
  };
  return { objects, calls, fetchImpl };
}
const run = (fake, overrides = {}) => publishOta({ apk, metadata, notes: manifest.notes, serviceKey: 'test-secret-never-log', fetchImpl: fake.fetchImpl, now: () => new Date(manifest.publishedAt), ...overrides });

test('uploads immutable APK, publicly verifies bytes, then promotes zero-cache manifest', async () => {
  const fake = storage();
  const result = await run(fake);
  assert.deepEqual(result, manifest);
  assert.deepEqual(JSON.parse(fake.objects.get('android/latest.json')), manifest);
  assert.equal(fake.objects.has('android/publish.lock'), false);
  const upload = fake.calls.findIndex(c => c.method === 'POST' && c.url.endsWith('/android/6/app.apk'));
  const verify = fake.calls.findIndex(c => c.method === 'GET' && c.url === manifest.apkUrl);
  const promote = fake.calls.findIndex(c => c.method === 'POST' && c.url.endsWith('/android/latest.json'));
  assert.ok(upload < verify && verify < promote);
  assert.equal(fake.calls[upload].headers['x-upsert'], 'false');
  assert.equal(fake.calls[upload].headers['cache-control'], 'max-age=31536000, immutable');
  assert.equal(fake.calls[promote].headers['cache-control'], 'max-age=0');
  for (const call of fake.calls.filter(c => c.url.startsWith(publicBase))) {
    assert.equal(call.headers?.authorization, undefined);
    assert.equal(call.redirect, 'error');
  }
});
test('identical retries verify existing bytes without replacing the published manifest', async () => {
  const fake = storage({ latest: manifest, existingApk: apk });
  await run(fake);
  assert.equal(fake.calls.some(c => c.method === 'POST' && c.url.endsWith('/android/latest.json')), false);
});
test('identical retry fails when the public manifest still differs from authenticated latest', async () => {
  const fake = storage({ latest: manifest, existingApk: apk });
  const fetchImpl = async (url, options) => url === `${publicBase}android/latest.json`
    ? new Response(JSON.stringify({ ...manifest, notes: 'stale cached notes' }))
    : fake.fetchImpl(url, options);
  await assert.rejects(run(fake, { fetchImpl }));
  assert.deepEqual(JSON.parse(fake.objects.get('android/latest.json')), manifest);
});
test('rejects downgrade and same-version differing notes or hash before APK mutation', async () => {
  for (const latest of [{ ...manifest, versionCode: 7, apkUrl: `${publicBase}android/7/app.apk` }, { ...manifest, notes: 'different' }, { ...manifest, sha256: '0'.repeat(64) }]) {
    const fake = storage({ latest });
    await assert.rejects(run(fake));
    assert.equal(fake.calls.some(c => c.method === 'POST' && c.url.endsWith('/app.apk')), false);
  }
});
test('conflicting immutable APK, corrupt public bytes and foreign redirects never promote', async () => {
  for (const config of [{ existingApk: Buffer.from('different') }, { corrupt: true }, { redirect: true }]) {
    const fake = storage(config);
    await assert.rejects(run(fake));
    assert.equal(fake.objects.has('android/latest.json'), false);
    assert.equal(fake.objects.has('android/publish.lock'), false);
  }
});
test('publisher cannot remove another publisher lock or start APK upload', async () => {
  const fake = storage({ locked: true });
  await assert.rejects(run(fake));
  assert.equal(fake.objects.get('android/publish.lock').toString(), 'other owner');
  assert.equal(fake.calls.some(c => c.url.endsWith('/app.apk')), false);
});
test('existing private or incompatible bucket is rejected without changing bucket or objects', async () => {
  for (const existingBucket of [{ id: 'app-updates', public: false }, { id: 'app-updates', public: true, file_size_limit: 100 }, { id: 'app-updates', public: true, allowed_mime_types: ['image/png'] }]) {
    const fake = storage({ existingBucket });
    await assert.rejects(run(fake));
    assert.equal(fake.calls.some(call => call.method !== 'GET'), false);
  }
});
test('first publish inherits a 50 MB tenant limit instead of requesting a larger bucket limit', async () => {
  const fake = storage({ globalLimit: 50000000 });
  assert.deepEqual(await run(fake), manifest);
  const creation = fake.calls.find(call => call.method === 'POST' && call.url.endsWith('/bucket'));
  assert.equal(Object.hasOwn(JSON.parse(creation.body), 'file_size_limit'), false);
});
test('existing bucket accepts the actual largest file size and rejects one byte less without writes', async () => {
  for (const bytes of [apk, Buffer.alloc(1024, 42)]) {
    const expected = { ...manifest, sizeBytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    const required = Math.max(bytes.length, Buffer.byteLength(JSON.stringify(expected)));
    const fake = storage({ existingBucket: { id: 'app-updates', public: true, file_size_limit: required } });
    assert.deepEqual(await run(fake, { apk: bytes }), expected);
    const tooSmall = storage({ existingBucket: { id: 'app-updates', public: true, file_size_limit: required - 1 } });
    await assert.rejects(run(tooSmall, { apk: bytes }));
    assert.equal(tooSmall.calls.some(call => call.method !== 'GET'), false);
  }
});
test('bounded streaming rejects announced and unannounced oversized responses', async () => {
  await assert.rejects(readBounded(new Response('abcd', { headers: { 'content-length': '4' } }), 3));
  await assert.rejects(readBounded(new Response('abcd'), 3));
  assert.equal((await readBounded(new Response('abc'), 3)).toString(), 'abc');
});
test('decoded compressed responses use the decoded limit without comparing wire byte count', async () => {
  assert.equal((await readBounded(new Response('abcd', { headers: { 'content-length': '2', 'content-encoding': 'gzip' } }), 4)).toString(), 'abcd');
  await assert.rejects(readBounded(new Response('abcde', { headers: { 'content-length': '2', 'content-encoding': 'gzip' } }), 4));
  await assert.rejects(readBounded(new Response('abcd', { headers: { 'content-length': '2' } }), 4));
});
test('malformed remote manifest and network errors remain bounded and redact service key', async () => {
  const fake = storage({ latest: { ...manifest, notes: 'x'.repeat(65537) } });
  await assert.rejects(run(fake));
  assert.equal(fake.objects.has('android/6/app.apk'), false);
  await assert.rejects(run(storage(), { fetchImpl: async () => { throw new Error('test-secret-never-log'); } }), error => !error.message.includes('test-secret-never-log'));
});
