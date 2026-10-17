import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AssistantMessage } from '@earendil-works/pi-ai';
import { SessionManager } from '@thor-agent/pi-session-browser';
import { ThorRuntime, RuntimeError, RuntimeSession, type PersistencePort, type Capability } from '../src/index.js';
import type { PiHistoryState, PendingPiInput } from '../src/pi-history.js';
import { fixture, registry } from './fixtures.js';

type PiBatch = Parameters<NonNullable<PersistencePort['savePiSession']>>[1];
const assistant = (text: string): AssistantMessage => ({ role: 'assistant', content: [{ type: 'text', text, textSignature: '{"v":1,"id":"old-answer-item"}' }],
  provider: 'openai', model: 'fixture-model', api: 'openai-responses', stopReason: 'stop', timestamp: 2,
  usage: { input: 100, output: 10, cacheRead: 0, cacheWrite: 0, totalTokens: 110, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } });
function nativeState(): PiHistoryState {
  const manager = SessionManager.inMemory('/thor', { id: 'session-test' });
  const provenance: Record<string, Capability[]> = {};
  const first = manager.appendMessage({ role: 'user', content: 'FIRST_ORIGINAL_USER', timestamp: 1 }); provenance[first] = [];
  const call = manager.appendMessage({ ...assistant(''), stopReason: 'toolUse', content: [{ type: 'toolCall', id: 'confirmed-once', name: 'echo', arguments: { text: 'historical call only' } }] }); provenance[call] = ['web_search'];
  const result = manager.appendMessage({ role: 'toolResult', toolCallId: 'confirmed-once', toolName: 'echo', content: [{ type: 'text', text: 'CONFIRMED_OLD_RESULT' }], isError: false, timestamp: 3 }); provenance[result] = ['web_search'];
  const second = manager.appendMessage({ role: 'user', content: 'SECOND_ORIGINAL_USER', timestamp: 4 }); provenance[second] = [];
  const answer = manager.appendMessage(assistant('SECOND_OLD_ANSWER')); provenance[answer] = ['web_search'];
  const observation = manager.appendCustomMessageEntry('thor-runtime-context', 'OLD_CHRONOLOGICAL_OBSERVATION', false); provenance[observation] = ['web_search'];
  const unsafe = manager.appendMessage(assistant('REVOKED_SCREEN_SECRET')); provenance[unsafe] = ['screen_perception'];
  const summary = manager.appendCompaction('REVOKED_SUMMARY_SECRET', second, 10_000, {}); provenance[summary] = ['screen_perception', 'web_search'];
  return { header: manager.getHeader()!, entries: manager.getEntries(), leafId: manager.getLeafId(), provenance };
}
const pending: PendingPiInput[] = [
  { runId: 'pending-first-run', content: [{ type: 'text', text: 'FIRST_PENDING_USER' }] },
  { runId: 'pending-second-run', content: [{ type: 'text', text: 'SECOND_PENDING_USER' }] },
];
function assertCompleteInitialBatch(batch: PiBatch, currentRunId: string): void {
  const entries = batch.entries as any[];
  assert.deepEqual(entries.filter(entry => entry.type === 'message').map(entry => entry.message.role), ['user', 'assistant', 'toolResult', 'user', 'assistant', 'user', 'user']);
  const text = JSON.stringify(entries);
  for (const marker of ['FIRST_ORIGINAL_USER', 'CONFIRMED_OLD_RESULT', 'SECOND_ORIGINAL_USER', 'SECOND_OLD_ANSWER', 'old-answer-item', 'OLD_CHRONOLOGICAL_OBSERVATION', 'FIRST_PENDING_USER', 'SECOND_PENDING_USER']) assert.ok(text.includes(marker), `First atomic batch must contain ${marker}`);
  assert.ok(!text.includes('REVOKED_'));
  assert.equal(batch.leafId, entries.at(-1)!.id, 'Commit the final authorized branch and all restored input intents as one suffix');
  const firstUser = entries.find(entry => entry.type === 'message' && entry.message.role === 'user' && JSON.stringify(entry.message.content).includes('FIRST_ORIGINAL_USER'));
  const firstPending = entries.find(entry => entry.type === 'message' && entry.message.role === 'user' && JSON.stringify(entry.message.content).includes('FIRST_PENDING_USER'));
  const secondPending = entries.find(entry => entry.type === 'message' && entry.message.role === 'user' && JSON.stringify(entry.message.content).includes('SECOND_PENDING_USER'));
  assert.equal(batch.inputRunIds?.[firstPending.id], 'pending-first-run');
  assert.equal(batch.inputRunIds?.[secondPending.id], 'pending-second-run');
  assert.equal(batch.inputRunIds?.[firstUser.id], undefined, 'A copied old user must not acknowledge this Run');
  assert.ok(!Object.values(batch.inputRunIds ?? {}).includes(currentRunId));
  assert.deepEqual(batch.entrySources?.[firstUser.id], []);
  assert.ok(entries.every(entry => Object.hasOwn(batch.entrySources ?? {}, entry.id)));
}

test('first Pi persistence atomically submits the complete authorized native branch and pending inputs before HTTP', async () => {
  const f = fixture(); f.snapshot.session.enabled.screen_perception = false;
  const session = new RuntimeSession(f.lease.sessionId); const original = nativeState(); const unchanged = structuredClone(original); const batches: PiBatch[] = [];
  f.ports.persistence.savePiSession = async (sessionId, batch) => {
    assert.equal(sessionId, f.lease.sessionId);
    if (!batches.length) { assert.equal(f.requests.length, 0); assertCompleteInitialBatch(batch, f.lease.runId); }
    batches.push(structuredClone(batch));
  };
  assert.equal((await new ThorRuntime(f.ports, registry(), {}, session).run(f.lease, [{ type: 'text', text: 'CURRENT_NEW_PROMPT' }], original, pending)).state, 'completed');
  assert.deepEqual(original, unchanged); assert.equal(f.executions.length, 0); assert.equal(f.requests.length, 1);
  const acknowledged = batches.flatMap(batch => Object.entries(batch.inputRunIds ?? {})).filter(([, runId]) => runId === f.lease.runId);
  assert.equal(acknowledged.length, 1, 'Only the actual new prompt entry acknowledges this Run');
  const [userId] = acknowledged[0]!;
  const current = batches.flatMap(batch => batch.entries as any[]).find(entry => entry.id === userId);
  assert.equal(current.message.role, 'user'); assert.match(JSON.stringify(current.message.content), /CURRENT_NEW_PROMPT/);
});

test('Native rejection of the complete branch batch fails persistence without HTTP, side effects or partial follow-up commits', async () => {
  const f = fixture(); f.snapshot.session.enabled.screen_perception = false;
  const session = new RuntimeSession(f.lease.sessionId); const attempts: PiBatch[] = [];
  f.ports.persistence.savePiSession = async (_sessionId, batch) => {
    attempts.push(structuredClone(batch)); assertCompleteInitialBatch(batch, f.lease.runId);
    throw new Error('Synthetic atomic Native transaction rejection');
  };
  await assert.rejects(new ThorRuntime(f.ports, registry(), {}, session).run(f.lease, [{ type: 'text', text: 'CURRENT_NEW_PROMPT' }], nativeState(), pending),
    error => error instanceof RuntimeError && error.code === 'PERSISTENCE_ERROR');
  assert.equal(attempts.length, 1, 'A rejected atomic recovery must not be retried as smaller prefix batches');
  assert.equal(f.requests.length, 0); assert.equal(f.executions.length, 0);
  assert.equal(session.piSnapshot, undefined);
});
