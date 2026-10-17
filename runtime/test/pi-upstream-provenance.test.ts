import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createPiSession, SessionManager } from '@thor-agent/pi-session-browser';
import { Agent } from '@earendil-works/pi-agent-core';
import type { Model } from '@earendil-works/pi-ai';

test('the executed Pi session/compaction modules match the pinned official import-only port', async () => {
  const base = new URL('../../vendor/pi-session-browser/', import.meta.url);
  const manifest = JSON.parse(await readFile(new URL('UPSTREAM.json', base), 'utf8')) as {
    package: string; version: string; npmIntegrity: string;
    modules: Record<string, { browserSha256: string }>;
  };
  assert.equal(manifest.package, '@earendil-works/pi-coding-agent');
  assert.equal(manifest.version, '1.2.0');
  assert.equal(manifest.npmIntegrity, 'sha512-h/4WaVWv1VlCaY8x8qEN5U+7jbF/e7+Ck6moPeEOFn5juMTWwNAXjwgYM7gRgbBYh8Zppwt6qWvJDzNTf4GNEQ==');
  assert.ok(manifest.modules['core/agent-session.js']);
  assert.ok(manifest.modules['core/session-manager.js']);
  assert.ok(manifest.modules['core/compaction/compaction.js']);
  for (const [name, metadata] of Object.entries(manifest.modules)) {
    const actual = createHash('sha256').update(await readFile(new URL(name, base))).digest('hex');
    assert.equal(actual, metadata.browserSha256, `${name} changed outside the pinned import-only port`);
  }
});

test('the browser platform retains external session identity and rejects a different restored header', () => {
  const model: Model<'openai-responses'> = {
    id: 'identity-fixture', name: 'fixture', api: 'openai-responses', provider: 'fixture',
    baseUrl: 'https://fixture.invalid', input: ['text'], reasoning: false,
    contextWindow: 100000, maxTokens: 1000,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  };
  const agent = new Agent({ initialState: { model }, streamFn: () => { throw new Error('Unexpected request'); } });
  const manager = SessionManager.inMemory('/thor', { id: 'first' });
  assert.throws(() => createPiSession({ agent, model, sessionId: 'other', systemPrompt: 'test', tools: [], entries: [manager.getHeader()!] }), /identity mismatch/);
  const handle = createPiSession({ agent, model, sessionId: 'first', systemPrompt: 'test', tools: [], entries: [manager.getHeader()!] });
  assert.equal(handle.session.sessionId, 'first');
  assert.equal(agent.sessionId, 'first');
  assert.equal(handle.snapshot().entries[0]?.type, 'session');
  handle.dispose();
});
