import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AssistantMessage, Model } from '@earendil-works/pi-ai';
import { SessionManager } from '@thor-agent/pi-session-browser';
import { preparePiHistory, type PiHistoryState } from '../src/pi-history.js';
import { fixture } from './fixtures.js';
import type { Capability } from '../src/contracts.js';

const model: Model<'openai-responses'> = { id: 'fixture-model', name: 'Fixture', api: 'openai-responses', provider: 'fixture',
  baseUrl: 'https://fixture.invalid/v1', input: ['text', 'image'], reasoning: true, contextWindow: 32_000, maxTokens: 1024,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
const user = (text: string) => ({ role: 'user' as const, content: text, timestamp: 1 });
const assistant = (text: string): AssistantMessage => ({ role: 'assistant', content: [{ type: 'text', text }], api: model.api, provider: model.provider, model: model.id,
  timestamp: 2, stopReason: 'stop', usage: { input: 123, output: 45, cacheRead: 600, cacheWrite: 10, totalTokens: 778,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } });
function state(manager: SessionManager, provenance: Record<string, Capability[]> = {}): PiHistoryState {
  return { header: structuredClone(manager.getHeader()!), entries: structuredClone(manager.getEntries()), leafId: manager.getLeafId(), provenance: structuredClone(provenance) };
}
function restore(value: PiHistoryState) {
  const manager = SessionManager.inMemory('/thor', undefined, [value.header, ...value.entries]);
  if (value.leafId === null) manager.resetLeaf(); else manager.branch(value.leafId);
  return manager;
}
const manager = () => SessionManager.inMemory('/thor', { id: 'session-test' });

test('Pi entry restoration preserves exact original entries, usage and signatures with no permission change', async () => {
  const f = fixture(); const m = manager();
  const uid = m.appendMessage(user('Keep this task.'));
  const signed = assistant('Recorded response'); signed.responseId = 'opaque-response';
  signed.content.unshift({ type: 'thinking', thinking: 'Recorded reasoning fixture', thinkingSignature: 'opaque-signature' });
  const aid = m.appendMessage(signed);
  const original = state(m, { [uid]: [], [aid]: ['web_search'] });
  const text = JSON.stringify(original);
  const prepared = await preparePiHistory(original, f.snapshot, model, f.ports.attachments);
  assert.equal(JSON.stringify(original), text);
  assert.deepEqual({ header: prepared.header, entries: prepared.entries, leafId: prepared.leafId, provenance: prepared.provenance }, original);
  assert.deepEqual(prepared.source, ['web_search']);
  assert.deepEqual(restore(prepared).buildSessionContext().messages[1], signed);
});

test('revoking a result appends official context edits for the whole assistant tool batch without rewriting raw entries', async () => {
  const f = fixture(); f.snapshot.session.enabled.screen_perception = false; const m = manager();
  const uid = m.appendMessage(user('Inspect and search.'));
  const calls: AssistantMessage = { ...assistant(''), stopReason: 'toolUse', content: [
    { type: 'toolCall', id: 'screen-call', name: 'capture', arguments: {} },
    { type: 'toolCall', id: 'search-call', name: 'search', arguments: {} },
  ] };
  const aid = m.appendMessage(calls);
  const rid = m.appendMessage({ role: 'toolResult', toolCallId: 'screen-call', toolName: 'capture', content: [{ type: 'text', text: 'SCREEN_SECRET' }], isError: false, timestamp: 3 });
  const other = m.appendMessage({ role: 'toolResult', toolCallId: 'search-call', toolName: 'search', content: [{ type: 'text', text: 'Confirmed search result' }], isError: false, timestamp: 4 });
  const original = state(m, { [uid]: [], [aid]: [], [rid]: ['screen_perception'], [other]: ['web_search'] });
  const result = await preparePiHistory(original, f.snapshot, model, f.ports.attachments);
  assert.deepEqual(result.entries.slice(0, original.entries.length), original.entries);
  assert.deepEqual(result.entries.slice(original.entries.length).map(entry => entry.type), ['context_edit', 'context_edit', 'context_edit']);
  assert.deepEqual(restore(result).buildSessionContext().messages, [user('Inspect and search.')]);
  const repeat = await preparePiHistory(result, f.snapshot, model, f.ports.attachments);
  assert.deepEqual(repeat.entries, result.entries, 'Restoration must not append duplicate removal edits');
});

test('revoked compaction summary branches through official Pi manager and retains the old tree only as inactive storage', async () => {
  const f = fixture(); f.snapshot.session.enabled.screen_perception = false; const m = manager();
  const uid = m.appendMessage(user('Keep original user target'));
  const safe = m.appendMessage(assistant('SAFE_WEB_RESULT'));
  const unsafe = m.appendMessage(assistant('SCREEN_SECRET_ANSWER'));
  const recent = m.appendMessage(user('Continue the task'));
  const summary = m.appendCompaction('SCREEN_SECRET_SUMMARY', recent, 12_000, {});
  const original = state(m, { [uid]: [], [safe]: ['web_search'], [unsafe]: ['screen_perception'], [recent]: [], [summary]: ['web_search', 'screen_perception'] });
  const result = await preparePiHistory(original, f.snapshot, model, f.ports.attachments);
  assert.deepEqual(result.entries.slice(0, original.entries.length), original.entries);
  const rebuilt = restore(result); const text = JSON.stringify(rebuilt.buildSessionContext());
  assert.match(text, /Keep original user target/); assert.match(text, /SAFE_WEB_RESULT/); assert.match(text, /Continue the task/);
  assert.doesNotMatch(text, /SCREEN_SECRET/);
  assert.equal(rebuilt.getBranch()[0]!.parentId, null);
  assert.equal(rebuilt.getBranch().some(entry => entry.type === 'compaction'), false);
  assert.deepEqual(result.source, ['web_search']);
  assert.equal(result.entries.filter(entry => entry.type === 'compaction').length, 1, 'Original summary remains available only on its inactive branch');
});

test('revoked screenshot attached to a user message is edited through Pi without changing user text or a manual image', async () => {
  const f = fixture(); f.snapshot.session.enabled.screen_perception = false; const m = manager();
  const screenshot = m.appendMessage({ role: 'user', timestamp: 1, content: [{ type: 'text', text: 'USER_TEXT_MUST_SURVIVE' }, { type: 'image', data: 'PRIVATE_SCREEN_PIXELS', mimeType: 'image/png' }] });
  const manual = m.appendMessage({ role: 'user', timestamp: 2, content: [{ type: 'text', text: 'MANUAL_IMAGE_REQUEST' }, { type: 'image', data: 'MANUAL_PIXELS', mimeType: 'image/png' }] });
  const original = state(m, { [screenshot]: ['screen_perception'], [manual]: [] });
  const result = await preparePiHistory(original, f.snapshot, model, f.ports.attachments);
  assert.deepEqual(result.entries.slice(0, original.entries.length), original.entries);
  assert.equal(result.entries.length, original.entries.length + 1);
  const edit = result.entries.at(-1); assert.equal(edit?.type, 'context_edit');
  if (edit?.type === 'context_edit') { assert.equal(edit.targetId, screenshot); assert.notEqual(edit.replacement, null); }
  const projected = JSON.stringify(restore(result).buildSessionContext());
  assert.match(projected, /USER_TEXT_MUST_SURVIVE/); assert.match(projected, /Image unavailable/);
  assert.doesNotMatch(projected, /PRIVATE_SCREEN_PIXELS/); assert.match(projected, /MANUAL_PIXELS/);
  const repeat = await preparePiHistory(result, f.snapshot, model, f.ports.attachments);
  assert.deepEqual(repeat.entries, result.entries, 'Already redacted images do not generate repeated edits');
});

test('user screenshot provenance is inherited while permitted and its redaction survives an unsafe summary branch rebuild', async () => {
  const f = fixture(); const m = manager();
  const screenshot = m.appendMessage({ role: 'user', timestamp: 1, content: [{ type: 'text', text: 'ORIGINAL_USER_TARGET' }, { type: 'image', data: 'PRIVATE_SCREEN_PIXELS', mimeType: 'image/png' }] });
  const recent = m.appendMessage(user('Continue'));
  const summary = m.appendCompaction('PRIVATE_SCREEN_DERIVED_SUMMARY', recent, 10_000, {});
  const original = state(m, { [screenshot]: ['screen_perception'], [recent]: [], [summary]: ['screen_perception'] });
  const permitted = await preparePiHistory(original, f.snapshot, model, f.ports.attachments);
  assert.deepEqual(permitted.entries, original.entries); assert.deepEqual(permitted.source, ['screen_perception']);
  f.snapshot.session.enabled.screen_perception = false;
  const revoked = await preparePiHistory(permitted, f.snapshot, model, f.ports.attachments);
  const projected = JSON.stringify(restore(revoked).buildSessionContext());
  assert.match(projected, /ORIGINAL_USER_TARGET/); assert.match(projected, /Image unavailable/);
  assert.doesNotMatch(projected, /PRIVATE_SCREEN/); assert.deepEqual(revoked.source, []);
  assert.deepEqual(revoked.entries.slice(0, original.entries.length), original.entries);
});

test('pending Native user intent restores once into Pi without replaying a tool and maps its original run ID', async () => {
  const f = fixture(); const m = manager(); m.appendMessage(user('Already committed prior input'));
  const initial = state(m);
  const pending = { runId: 'interrupted-before-pi-user-entry', content: [{ type: 'text' as const, text: 'RESTORE_PENDING_USER_INTENT' }] };
  const recovered = await preparePiHistory(initial, f.snapshot, model, f.ports.attachments, [pending, pending]);
  assert.deepEqual(recovered.entries.slice(0, initial.entries.length), initial.entries);
  const restoredUser = recovered.entries.filter(entry => entry.type === 'message' && entry.message.role === 'user' && JSON.stringify(entry.message.content).includes('RESTORE_PENDING_USER_INTENT'));
  assert.equal(restoredUser.length, 1);
  assert.equal(recovered.inputRunIds[restoredUser[0]!.id], pending.runId);
  assert.equal(recovered.entries.filter(entry => entry.type === 'custom' && entry.customType === 'thor-committed-input').length, 1);
  assert.match(JSON.stringify(restore(recovered).buildSessionContext()), /RESTORE_PENDING_USER_INTENT/);
  assert.equal(f.executions.length, 0);
  const persisted = state(restore(recovered), recovered.provenance);
  const repeated = await preparePiHistory(persisted, f.snapshot, model, f.ports.attachments, [pending]);
  assert.deepEqual(repeated.entries, recovered.entries, 'Persisted Pi marker prevents duplicate compensation even if Native repeats a pending record');
  assert.equal(f.executions.length, 0);
});
