import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { parseMinidump, collectCrashMetadata } from './describe-emulator-crash.mjs';

const base = 0x20000000000001n;
function fixture(name = 'C:\\private\\driver.dll') {
  const b = Buffer.alloc(512);
  b.write('MDMP'); b.writeUInt32LE(0xa793, 4);
  b.writeUInt32LE(2, 8); b.writeUInt32LE(32, 12);
  b.writeUInt32LE(6, 32); b.writeUInt32LE(168, 36); b.writeUInt32LE(56, 40);
  b.writeUInt32LE(4, 44); b.writeUInt32LE(112, 48); b.writeUInt32LE(224, 52);
  b.writeUInt32LE(17, 56); b.writeUInt32LE(0xc0000005, 64);
  b.writeBigUInt64LE(base + 3n, 80);
  b.writeUInt32LE(1, 224); b.writeBigUInt64LE(base, 228);
  b.writeUInt32LE(256, 236); b.writeUInt32LE(336, 248);
  const bytes = Buffer.from(name, 'utf16le');
  b.writeUInt32LE(bytes.length, 336); bytes.copy(b, 340);
  b.write('SECRET MEMORY COMMANDLINE TOKEN', 440);
  return b;
}
test('attributes exception using exact 64-bit address and basename only', () => {
  assert.deepEqual(parseMinidump(fixture()), { status: 'exception', thread: 17,
    code: '0xc0000005', address: '0x20000000000004', module: 'driver.dll', offset: '0x3' });
});
test('does not leak paths, arbitrary names, or unrelated memory strings', () => {
  const result = parseMinidump(fixture('/private/SECRET\nNAME.dll'));
  assert.equal(result.status, 'malformed');
  assert.doesNotMatch(JSON.stringify(result), /SECRET|private|MEMORY|TOKEN/);
});
test('supports Unix basename and does not read unknown stream payload', () => {
  assert.equal(parseMinidump(fixture('/private/libGLES.so')).module, 'libGLES.so');
  const b = fixture(); b.writeUInt32LE(9, 44);
  assert.deepEqual(parseMinidump(b), { status: 'exception', thread: 17,
    code: '0xc0000005', address: '0x20000000000004', module: null, offset: null });
});
test('explicitly reports missing exception and unsupported format', () => {
  const b = fixture(); b.writeUInt32LE(9, 32);
  assert.deepEqual(parseMinidump(b), { status: 'missing_exception' });
  assert.deepEqual(parseMinidump(Buffer.from('not a minidump')), { status: 'unsupported_format' });
  const highBit = fixture(); highBit[0] |= 128;
  assert.equal(parseMinidump(highBit).status, 'unsupported_format');
});
test('rejects every truncated structure and out-of-range RVA', () => {
  for (const length of [4, 31, 55, 223, 335, 339, 375])
    assert.equal(parseMinidump(fixture().subarray(0, length)).status, 'malformed');
  for (const pos of [12, 40, 52, 248]) {
    const b = fixture(); b.writeUInt32LE(0xffffffff, pos);
    assert.equal(parseMinidump(b).status, 'malformed');
  }
});
test('enforces stream, module, dump and UTF16 name bounds', () => {
  for (const [pos, value] of [[8, 129], [224, 1025], [336, 4098], [336, 3], [4, 0]]) {
    const b = fixture(); b.writeUInt32LE(value, pos);
    assert.equal(parseMinidump(b).status, 'malformed');
  }
  assert.equal(parseMinidump(Buffer.alloc(64 * 1024 * 1024 + 1)).status, 'malformed');
});
test('rejects duplicate relevant streams and overlapping module attribution', () => {
  const b = fixture(); b.writeUInt32LE(6, 44);
  assert.equal(parseMinidump(b).status, 'malformed');
  const c = fixture(); c.writeUInt32LE(220, 48); c.writeUInt32LE(2, 224);
  c.copy(c, 336, 228, 336); c.writeUInt32LE(448, 248); c.writeUInt32LE(448, 356);
  c.writeUInt32LE(20, 448); Buffer.from('driver.dll', 'utf16le').copy(c, 452);
  assert.equal(parseMinidump(c).status, 'malformed');
});
test('does not attribute address at exclusive end or overflowing module ranges', () => {
  const b = fixture(); b.writeBigUInt64LE(base + 256n, 80);
  assert.equal(parseMinidump(b).module, null);
  b.writeBigUInt64LE(0xffffffffffffffffn, 228);
  assert.equal(parseMinidump(b).status, 'malformed');
});
test('collector refuses unsupported hosts without filesystem access', () => {
  assert.deepEqual(collectCrashMetadata({ platform: 'win32', fs: {} }), { status: 'unsupported_platform' });
});
test('CLI rejects arbitrary paths without echoing them', () => {
  const result = spawnSync(process.execPath, ['scripts/describe-emulator-crash.mjs', '/private/SECRET'], { encoding: 'utf8' });
  assert.equal(result.status, 0);
  assert.deepEqual(JSON.parse(result.stdout), { status: 'unsupported_arguments' });
  assert.equal(result.stderr, '');
});

// In-memory filesystem only: tests never create a crash database or read a host dump.
function syntheticFs(tree) {
  const reads = [], enumerated = [], handles = new Map();
  const resolveHandle = location => {
    const match = /^\/proc\/self\/fd\/(\d+)(\/.*)?$/.exec(location);
    return match ? handles.get(Number(match[1])).location + (match[2] ?? '') : location;
  };
  const get = location => {
    const node = tree[location];
    if (!node) throw Object.assign(new Error(), { code: 'ENOENT' });
    return node;
  };
  const stat = node => ({ size: node.bytes?.length ?? node.size ?? 0, dev: 1, ino: node,
    isDirectory: () => node.type === 'dir', isFile: () => node.type === 'file',
    isSymbolicLink: () => node.type === 'link' });
  const fs = {
    constants: { O_RDONLY: 0, O_NOFOLLOW: 1, O_NONBLOCK: 2, O_DIRECTORY: 4 },
    lstatSync: location => stat(get(resolveHandle(location))),
    realpathSync: location => {
      const resolved = resolveHandle(location);
      return get(resolved).target ?? resolved;
    },
    opendirSync: location => {
      const descriptor = /^\/proc\/self\/fd\/(\d+)$/.exec(location);
      location = descriptor ? handles.get(Number(descriptor[1])).location : get(location).target ?? location;
      enumerated.push(location);
      const children = Object.entries(tree).filter(([key]) => key.startsWith(`${location}/`) &&
        !key.slice(location.length + 1).includes('/'));
      let index = 0;
      return { closeSync() {}, readSync() {
        const child = children[index++];
        return child ? { name: child[0].slice(location.length + 1), ...stat(child[1]) } : null;
      } };
    },
    openSync: (location, flags) => {
      assert.ok(flags === 3 || flags === 5);
      location = resolveHandle(location);
      const node = get(location);
      if (node.type === 'link') throw Object.assign(new Error(), { code: 'ELOOP' });
      if ((flags & 4) && node.type !== 'dir') throw Object.assign(new Error(), { code: 'ENOTDIR' });
      const fd = handles.size + 1;
      handles.set(fd, { location, node }); return fd;
    },
    fstatSync: fd => stat(handles.get(fd).node),
    readSync: (fd, bytes, offset, size, position) => {
      const handle = handles.get(fd); reads.push(handle.location);
      return handle.node.bytes.copy(bytes, offset, position, position + size);
    },
    closeSync() {},
  };
  return { fs, reads, enumerated };
}
const rootTree = () => ({ '/tmp': { type: 'dir' }, '/tmp/android-runner': { type: 'dir' },
  '/tmp/android-runner/emu-crash-test.db': { type: 'dir' } });
test('collects only known database dumps without filenames in output', () => {
  const tree = rootTree();
  tree['/tmp/android-runner/emu-crash-test.db/pending'] = { type: 'dir' };
  tree['/tmp/android-runner/emu-crash-test.db/pending/PRIVATE.dmp'] = { type: 'file', bytes: fixture() };
  tree['/tmp/android-runner/unrelated'] = { type: 'dir' };
  tree['/tmp/android-runner/unrelated/PRIVATE.dmp'] = { type: 'file', bytes: fixture() };
  const fake = syntheticFs(tree);
  const result = collectCrashMetadata({ platform: 'linux', fs: fake.fs });
  assert.equal(result.status, 'collected'); assert.equal(result.dumps.length, 1);
  assert.equal(fake.reads.length, 1); assert.doesNotMatch(JSON.stringify(result), /PRIVATE|pending|test\.db/);
});
test('missing dumps and unsafe or symlink roots are explicit', () => {
  let fake = syntheticFs({ '/tmp': { type: 'dir' } });
  assert.equal(collectCrashMetadata({ platform: 'linux', fs: fake.fs }).status, 'missing_dump');
  const tree = rootTree(); fake = syntheticFs(tree);
  assert.equal(collectCrashMetadata({ platform: 'linux', fs: fake.fs }).status, 'missing_dump');
  tree['/tmp/android-runner'] = { type: 'link', target: '/private' };
  assert.equal(collectCrashMetadata({ platform: 'linux', fs: fake.fs }).status, 'unsafe_root');
  assert.equal(fake.reads.length, 0);
});
test('refuses symlinks and oversized files without reading contents', () => {
  const tree = rootTree();
  tree['/tmp/android-runner/emu-crash-test.db/link.dmp'] = { type: 'link', target: '/private' };
  tree['/tmp/android-runner/emu-crash-test.db/large.dmp'] = { type: 'file', size: 64 * 1024 * 1024 + 1 };
  const fake = syntheticFs(tree), result = collectCrashMetadata({ platform: 'linux', fs: fake.fs });
  assert.equal(result.status, 'collection_rejected');
  assert.deepEqual(result.dumps, [{ status: 'malformed' }]); assert.equal(fake.reads.length, 0);
});
test('caps dump files at sixteen and directory nesting at three', () => {
  const tree = rootTree();
  for (let i = 0; i < 17; i++) tree[`/tmp/android-runner/emu-crash-test.db/${i}.dmp`] = { type: 'file', bytes: fixture() };
  let fake = syntheticFs(tree), result = collectCrashMetadata({ platform: 'linux', fs: fake.fs });
  assert.equal(result.status, 'collection_limit'); assert.equal(result.dumps.length, 16); assert.equal(fake.reads.length, 16);
  const deep = rootTree(); let location = '/tmp/android-runner/emu-crash-test.db';
  for (let i = 0; i < 3; i++) { location += '/nested'; deep[location] = { type: 'dir' }; }
  deep[`${location}/deep.dmp`] = { type: 'file', bytes: fixture() };
  fake = syntheticFs(deep); result = collectCrashMetadata({ platform: 'linux', fs: fake.fs });
  assert.equal(result.status, 'collection_limit'); assert.equal(fake.reads.length, 0);
});
test('refuses an open handle redirected by directory replacement', () => {
  const tree = rootTree();
  tree['/tmp/android-runner/emu-crash-test.db/test.dmp'] = { type: 'file', bytes: fixture() };
  const fake = syntheticFs(tree), original = fake.fs.realpathSync;
  fake.fs.realpathSync = location => {
    const resolved = original(location);
    return location.startsWith('/proc/self/fd/') && resolved.endsWith('.dmp') ? '/private/test.dmp' : resolved;
  };
  assert.equal(collectCrashMetadata({ platform: 'linux', fs: fake.fs }).status, 'collection_rejected');
  assert.equal(fake.reads.length, 0);
});
test('directory replacement after validation cannot enumerate or read outside the root', () => {
  const targets = ['/tmp/android-runner', '/tmp/android-runner/emu-crash-test.db',
    '/tmp/android-runner/emu-crash-test.db/pending'];
  const cases = ['before open', 'before enumeration'].flatMap(timing => targets.map(target => ({ timing, target })));
  for (const { timing, target } of cases) {
    const tree = rootTree(); tree['/private'] = { type: 'dir' };
    tree['/tmp/android-runner/emu-crash-test.db/pending'] = { type: 'dir' };
    tree['/private/SECRET.dmp'] = { type: 'file', bytes: fixture() };
    const fake = syntheticFs(tree), original = fake.fs.realpathSync;
    let replaced = false;
    fake.fs.realpathSync = location => {
      const resolved = original(location);
      const descriptor = /^\/proc\/self\/fd\/\d+$/.test(location);
      if (resolved === target && !replaced && descriptor === (timing === 'before enumeration')) {
        tree[target] = { type: 'link', target: '/private' }; replaced = true;
      }
      return resolved;
    };
    const result = collectCrashMetadata({ platform: 'linux', fs: fake.fs });
    assert.equal(replaced, true);
    assert.ok(!fake.enumerated.includes('/private'), 'must not enumerate the replacement symlink target');
    assert.equal(fake.reads.length, 0);
    assert.doesNotMatch(JSON.stringify(result), /private|SECRET/);
  }
});
