import fsDefault from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = '/tmp/android-runner';
const MAX_DUMP = 64 * 1024 * 1024;
const hex = value => `0x${value.toString(16)}`;

// MINIDUMP uses 4-byte packing: header 32, directory 12, exception 168,
// module 108 bytes. Only exception and module-name RVAs are dereferenced.
// https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_exception_stream
// https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_module
export function parseMinidump(b) {
  if (!Buffer.isBuffer(b) || b.length > MAX_DUMP) return { status: 'malformed' };
  if (b.length < 4 || b.readUInt32LE(0) !== 0x504d444d) return { status: 'unsupported_format' };
  try {
    const bounds = (offset, size) => {
      if (offset < 0 || size < 0 || offset > b.length || size > b.length - offset) throw new Error();
    };
    bounds(0, 32);
    if ((b.readUInt32LE(4) & 0xffff) !== 0xa793) throw new Error();
    const count = b.readUInt32LE(8), directory = b.readUInt32LE(12);
    if (count > 128 || directory < 32) throw new Error();
    bounds(directory, count * 12);
    const streams = new Map();
    for (let i = 0; i < count; i++) {
      const entry = directory + i * 12;
      const type = b.readUInt32LE(entry), size = b.readUInt32LE(entry + 4), rva = b.readUInt32LE(entry + 8);
      bounds(rva, size);
      if (type === 4 || type === 6) {
        if (streams.has(type) || rva < directory + count * 12) throw new Error();
        streams.set(type, { rva, size });
      }
    }
    const exception = streams.get(6);
    if (!exception) return { status: 'missing_exception' };
    if (exception.size !== 168) throw new Error();
    const e = exception.rva;
    if (b.readUInt32LE(e + 32) > 15) throw new Error();
    const address = b.readBigUInt64LE(e + 24);
    const result = { status: 'exception', thread: b.readUInt32LE(e),
      code: hex(b.readUInt32LE(e + 8)), address: hex(address), module: null, offset: null };
    const modules = streams.get(4);
    if (!modules) return result;
    if (modules.size < 4) throw new Error();
    if (modules.rva < e + 168 && e < modules.rva + modules.size) throw new Error();
    const moduleCount = b.readUInt32LE(modules.rva);
    if (moduleCount > 1024 || modules.size !== 4 + moduleCount * 108) throw new Error();
    for (let i = 0; i < moduleCount; i++) {
      const m = modules.rva + 4 + i * 108;
      const base = b.readBigUInt64LE(m), size = BigInt(b.readUInt32LE(m + 8));
      if (base + size > 0x10000000000000000n) throw new Error();
      const nameRva = b.readUInt32LE(m + 20);
      if (nameRva < directory + count * 12) throw new Error();
      bounds(nameRva, 4);
      const length = b.readUInt32LE(nameRva);
      if (length === 0 || length > 4096 || length % 2) throw new Error();
      bounds(nameRva + 4, length);
      if ((nameRva < e + 168 && e < nameRva + 4 + length) ||
          (nameRva < modules.rva + modules.size && modules.rva < nameRva + 4 + length)) throw new Error();
      // Validate all names, but emit only an allowlisted matching basename.
      const name = b.toString('utf16le', nameRva + 4, nameRva + 4 + length);
      if (/[\x00-\x1f\x7f-\x9f\uD800-\uDFFF]/u.test(name)) throw new Error();
      if (address >= base && address < base + size) {
        if (result.module !== null) throw new Error();
        const basename = name.split(/[\\/]/).at(-1);
        if (!/^[A-Za-z0-9_][A-Za-z0-9_.+-]{0,127}$/.test(basename)) throw new Error();
        result.module = basename;
        result.offset = hex(address - base);
      }
    }
    return result;
  } catch {
    // Never serialize parser exceptions or untrusted strings.
    return { status: 'malformed' };
  }
}

// The injection seam supports synthetic filesystem tests; CLI has no path option.
export function collectCrashMetadata({ platform = process.platform, fs = fsDefault } = {}) {
  if (platform !== 'linux') return { status: 'unsupported_platform' };
  const dumps = [];
  let entries = 0, directories = 0, files = 0, limited = false, rejected = false;
  const openDirectory = (location, anchoredPath) => {
    const before = fs.lstatSync(anchoredPath);
    if (!before.isDirectory() || before.isSymbolicLink() || fs.realpathSync(anchoredPath) !== location) return undefined;
    let fd;
    try {
      fd = fs.openSync(anchoredPath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_DIRECTORY);
      const stat = fs.fstatSync(fd);
      if (!stat.isDirectory() || stat.dev !== before.dev || stat.ino !== before.ino ||
          fs.realpathSync(`/proc/self/fd/${fd}`) !== location) {
        fs.closeSync(fd); return undefined;
      }
      return fd;
    } catch (error) {
      if (fd !== undefined) fs.closeSync(fd);
      throw error;
    }
  };
  const walk = (location, directoryFd, depth, inDatabase) => {
    if (++directories > 128) { limited = true; return; }
    // Enumeration and child opens use the validated handle, never a pathname
    // that can be redirected after validation. Only the trusted proc fd link
    // is followed; child components are opened with O_NOFOLLOW.
    const handlePath = `/proc/self/fd/${directoryFd}`;
    const dir = fs.opendirSync(handlePath);
    try {
      let entry;
      while ((entry = dir.readSync()) !== null) {
        if (++entries > 4096) { limited = true; break; }
        if (entry.name === '.' || entry.name === '..' || /[\\/]/.test(entry.name)) { rejected = true; continue; }
        const child = `${location}/${entry.name}`;
        const anchoredChild = `${handlePath}/${entry.name}`;
        if (entry.isSymbolicLink()) { rejected = true; continue; }
        if (entry.isDirectory()) {
          if (!inDatabase && !/^emu-crash-[A-Za-z0-9_.-]+\.db$/.test(entry.name)) continue;
          if (depth >= 3) { limited = true; continue; }
          let childFd;
          try {
            childFd = openDirectory(child, anchoredChild);
            if (childFd === undefined) { rejected = true; continue; }
            walk(child, childFd, depth + 1, true);
          } catch { rejected = true; }
          finally { if (childFd !== undefined) fs.closeSync(childFd); }
        } else if (inDatabase && entry.isFile() && entry.name.endsWith('.dmp')) {
          if (files >= 16) { limited = true; break; }
          files++;
          let fd;
          try {
            const before = fs.lstatSync(anchoredChild);
            if (!before.isFile() || before.isSymbolicLink()) { rejected = true; continue; }
            fd = fs.openSync(anchoredChild, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
            const stat = fs.fstatSync(fd);
            // Verify the open handle too, so directory replacement cannot redirect reads.
            if (fs.realpathSync(`/proc/self/fd/${fd}`) !== child || !stat.isFile() ||
                stat.dev !== before.dev || stat.ino !== before.ino) { rejected = true; continue; }
            if (stat.size > MAX_DUMP) { dumps.push({ status: 'malformed' }); continue; }
            const bytes = Buffer.alloc(stat.size);
            let read = 0;
            while (read < bytes.length) {
              const amount = fs.readSync(fd, bytes, read, bytes.length - read, read);
              if (amount === 0) break;
              read += amount;
            }
            dumps.push(read === bytes.length ? parseMinidump(bytes) : { status: 'malformed' });
          } catch { rejected = true; }
          finally { if (fd !== undefined) fs.closeSync(fd); }
        }
        if (limited) break;
      }
    } finally { dir.closeSync(); }
  };
  let tmpFd, rootFd;
  try {
    tmpFd = openDirectory('/tmp', '/tmp');
    if (tmpFd === undefined) return { status: 'unsafe_root' };
    rootFd = openDirectory(ROOT, `/proc/self/fd/${tmpFd}/android-runner`);
    if (rootFd === undefined) return { status: 'unsafe_root' };
    walk(ROOT, rootFd, 0, false);
  } catch (error) {
    return { status: error.code === 'ENOENT' ? 'missing_dump' : 'collection_failed' };
  } finally {
    if (rootFd !== undefined) fs.closeSync(rootFd);
    if (tmpFd !== undefined) fs.closeSync(tmpFd);
  }
  return { status: limited ? 'collection_limit' : rejected ? 'collection_rejected' : dumps.length ? 'collected' : 'missing_dump', dumps };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(process.argv.length > 2 ? { status: 'unsupported_arguments' } : collectCrashMetadata()));
}
