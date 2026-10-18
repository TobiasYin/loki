import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Value } from 'typebox/value';

const pack = JSON.parse(readFileSync(new URL('../../../tools/contracts/toolpack-v1.json', import.meta.url), 'utf8'));
const tool = (id: string) => {
  const found = pack.tools.find((entry: { toolId: string }) => entry.toolId === id);
  assert.ok(found, `missing ${id}`);
  return found;
};
const accepts = (id: string, input: unknown) => Value.Check(tool(id).inputSchema, input);

test('shared file reads and app logs remain capability-bound read tools without free shell fields', () => {
  for (const id of ['files.list', 'files.read', 'files.stat', 'files.find', 'debug.app_logs']) {
    assert.equal(tool(id).effectClass, 'read');
    assert.deepEqual(tool(id).requiredCapabilities, ['adb_debug']);
    assert.equal(tool(id).inputSchema.additionalProperties, false);
    assert.equal(accepts(id, { command: 'cat /data/private' }), false);
  }
  for (const id of ['files.stat', 'files.find', 'debug.app_logs']) {
    assert.equal(Object.hasOwn(tool(id).inputSchema.properties, 'backend'), false);
  }
  assert.equal(tool('files.write').effectClass, 'write');
  assert.equal(Object.hasOwn(tool('files.write').inputSchema.properties, 'backend'), false);
  assert.deepEqual(tool('files.write').resources, ['explicit-debug-root']);
  assert.deepEqual(tool('files.restore').resources, ['explicit-debug-root']);
});

test('file pagination and bounded tails are exposed without removing synthetic compatibility', () => {
  for (const id of ['files.list', 'files.read']) {
    const schema = tool(id).inputSchema.properties;
    assert.deepEqual(schema.backend.enum, ['auto', 'synthetic', 'shizuku']);
    assert.equal(schema.backend.default, 'auto');
  }
  assert.equal(accepts('files.list', { path: '/sdcard', offset: 10000, limit: 100 }), true);
  for (const input of [{ offset: -1 }, { offset: 10001 }, { offset: 0.5 }, { limit: 101 }]) {
    assert.equal(accepts('files.list', input), false);
  }
  assert.equal(accepts('files.read', { path: '/sdcard/日志.txt', tail: true, maxBytes: 65536 }), true);
  assert.equal(accepts('files.read', { path: '/sdcard/log', offset: 2147483647, maxBytes: 1 }), true);
  for (const input of [{ path: '/sdcard/log', maxBytes: 65537 }, { path: '/sdcard/log', tail: 'true' }, { path: '/sdcard/log', offset: -1 }]) {
    assert.equal(accepts('files.read', input), false);
  }
  // The tail/offset interaction is validated by the backend, independently of schema shape.
});

test('shared metadata/search and app PID logs reject missing identities and unbounded requests', () => {
  assert.equal(accepts('files.stat', { path: '/sdcard/Android/data/example.app/files/log.txt' }), true);
  assert.equal(accepts('files.stat', {}), false);
  assert.equal(accepts('files.stat', { path: '' }), false);
  assert.equal(accepts('files.find', { name: 'SMAPI' }), true);
  assert.equal(accepts('files.find', { name: '' }), false);
  for (const field of ['maxDepth', 'maxEntries', 'limit']) {
    const schema = tool('files.find').inputSchema.properties[field];
    assert.equal(accepts('files.find', { name: 'SMAPI', [field]: schema.maximum }), true);
    assert.equal(accepts('files.find', { name: 'SMAPI', [field]: schema.maximum + 1 }), false);
    assert.equal(accepts('files.find', { name: 'SMAPI', [field]: schema.minimum - 1 }), false);
  }
  assert.equal(accepts('debug.app_logs', { packageName: 'abc.smapi.gameloader', maxLines: 2000, maxBytes: 65536 }), true);
  for (const input of [{}, { packageName: '' }, { packageName: 'a.b;rm' }, { packageName: 'a.b:service' }, { packageName: 'a.b', maxLines: 2001 }, { packageName: 'a.b', maxBytes: 65537 }, { packageName: 'a.b', flags: '-c' }]) {
    assert.equal(accepts('debug.app_logs', input), false);
  }
});

test('file diagnostic guide is linked for on-demand reading and distinguishes observed evidence from diagnoses', () => {
  const skill = readFileSync(new URL('../../../app/src/main/assets/skills/adb-operations/SKILL.md', import.meta.url), 'utf8');
  const guide = readFileSync(new URL('../../../app/src/main/assets/skills/adb-operations/references/files-and-logs.md', import.meta.url), 'utf8');
  assert.ok(skill.includes('[references/files-and-logs.md](references/files-and-logs.md)'));
  assert.ok(!skill.includes('Loading mod metadata...'), 'full diagnostic guide should not be in the skill catalog instructions');
  for (const name of ['files_list', 'files_stat', 'files_find', 'files_read', 'debug_app_logs']) assert.ok(guide.includes(name));
  assert.ok(guide.includes('不得断言 OOM、死锁、确定性死循环'));
  assert.ok(guide.includes('不是 glob 或正则'));
  assert.ok(guide.includes('不把本地日志发送到网络'));
});
