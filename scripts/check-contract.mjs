import fs from 'node:fs';
import assert from 'node:assert/strict';
const spec = JSON.parse(fs.readFileSync(new URL('../docs/openapi.json', import.meta.url)));
assert.equal(spec.openapi, '3.1.0');
const ids = new Set();
for (const [path, methods] of Object.entries(spec.paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    assert(['get', 'post', 'patch', 'delete'].includes(method));
    assert(!ids.has(operation.operationId), `Duplicate operation: ${path}`);
    ids.add(operation.operationId);
    assert(Object.keys(operation.responses).some(code => code.startsWith('2')), path);
    for (const variable of path.matchAll(/\{(\w+)\}/g)) {
      assert(operation.parameters?.some(p => p.in === 'path' && p.name === variable[1] && p.required));
    }
  }
}
function walk(value) {
  if (!value || typeof value !== 'object') return;
  if (value.$ref) {
    assert(value.$ref.startsWith('#/'));
    assert(value.$ref.slice(2).split('/').reduce((part, key) => part?.[key], spec), value.$ref);
  }
  if (value.required && Array.isArray(value.required)) {
    for (const key of value.required) assert(value.properties?.[key], `Missing property ${key}`);
  }
  Object.values(value).forEach(walk);
}
walk(spec);
console.log(`Contract checks passed: ${ids.size} operations, all references resolve.`);
