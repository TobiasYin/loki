import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertPaired, buildProductPrompt, buildRuntimeObservation, ZERO_USAGE } from '../src/context.js';
import type { AgentMessage } from '@earendil-works/pi-agent-core';
import { agentSleepTool } from '../src/agent-tools.js';
import { descriptor, fixture } from './fixtures.js';

const facts = (text: string) => JSON.parse(text.match(/<L0>(.*?)<\/L0>/s)![1]!);

test('product instructions stay fixed when Native observation or lease changes', () => {
  const { snapshot } = fixture();
  const first = buildProductPrompt(snapshot, [descriptor()]);
  snapshot.l0.snapshotId = 'new-frame'; snapshot.l0.upperApp = { packageName: 'new.game', label: 'External app label' };
  snapshot.lease.runId = 'different-run'; snapshot.session.capabilityEpoch++;
  const next = buildProductPrompt(snapshot, [descriptor()]);
  assert.equal(first, next);
  assert.ok(first.includes('Be concise and clear'));
  assert.ok(!first.includes('new-frame')); assert.ok(!first.includes('External app label')); assert.ok(!first.includes('different-run'));
  assert.ok(!first.includes('<L0>')); assert.ok(!first.includes('<history-memory'));
});

test('skills injection exposes bounded metadata without loading bodies, hashes or arbitrary fields', () => {
  const { snapshot } = fixture();
  snapshot.skills = [{ name: 'game-guide', description: 'Read when asked about this game.', hash: 'hidden-version', body: 'BODY_MUST_NOT_LOAD', extra: 'DO_NOT_COPY' } as any];
  const prompt = buildProductPrompt(snapshot, []);
  assert.ok(prompt.includes('game-guide')); assert.ok(prompt.includes('Read when asked about this game.'));
  assert.ok(prompt.includes('skills_read')); assert.ok(!prompt.includes('hidden-version')); assert.ok(!prompt.includes('BODY_MUST_NOT_LOAD')); assert.ok(!prompt.includes('DO_NOT_COPY'));
  snapshot.skills = Array.from({ length: 500 }, (_, i) => ({ name: `guide-${i}`, description: 'description'.repeat(20), hash: 'private' }));
  const bounded = buildProductPrompt(snapshot, []);
  assert.ok(bounded.includes('skills_list(query, offset)')); assert.ok(bounded.includes('500 enabled in total'));
  assert.ok(!bounded.includes('guide-499'));
});

test('sleep instructions are conditional on the tool actually being injected', () => {
  const { snapshot } = fixture();
  assert.ok(!buildProductPrompt(snapshot, []).includes('Use agent_sleep(durationMs)'));
  assert.ok(buildProductPrompt(snapshot, [agentSleepTool]).includes('Use agent_sleep(durationMs)'));
});

test('runtime observation contains only bounded authorized L0 facts, never historical state', () => {
  const { snapshot } = fixture();
  snapshot.l0.upperApp = { packageName: 'upper.game', label: '游戏😀'.repeat(2000), versionName: '版本😀'.repeat(2000), versionCode: '9'.repeat(2000) };
  snapshot.l0.upperAppObservation = { status: 'observed', displayId: 0 };
  snapshot.l0.targetScreen = 'lower';
  (snapshot as any).history = [{ text: 'HISTORICAL_MESSAGE_MUST_NOT_COPY' }];
  (snapshot.l0 as any).styles = 'EXTRA_METADATA_MUST_NOT_COPY';
  for (let i = 0; i < 100; i++) snapshot.availability[`extra_long_backend_${i}`] = { status: 'backend_missing' };
  const text = buildRuntimeObservation(snapshot); const l0 = facts(text);
  assert.equal(l0.upperApp.packageName, 'upper.game'); assert.equal(l0.targetScreen, 'lower');
  assert.ok(l0.unavailableOmitted > 0); assert.ok(new TextEncoder().encode(JSON.stringify(l0)).byteLength <= 1024);
  assert.ok(!text.includes('HISTORICAL_MESSAGE_MUST_NOT_COPY')); assert.ok(!text.includes('EXTRA_METADATA_MUST_NOT_COPY')); assert.ok(!text.includes('�'));
  assert.ok(!text.includes('history-memory')); assert.ok(!text.includes('effects'));
});

for (const mode of ['disabled', 'configuration-probe', 'ambiguous'] as const) test(`${mode} observations withhold inaccessible app metadata`, () => {
  const { snapshot } = fixture();
  snapshot.l0.upperApp = { packageName: 'private.game' };
  snapshot.l0.upperAppObservation = { status: mode === 'ambiguous' ? 'ambiguous' : 'observed', displayId: 0 };
  if (mode === 'disabled') snapshot.session.enabled.screen_perception = false;
  if (mode === 'configuration-probe') snapshot.session.model.configurationProbe = true;
  const text = buildRuntimeObservation(snapshot);
  assert.ok(!text.includes('private.game'));
  assert.deepEqual(facts(text).enabled, snapshot.session.enabled);
});

for (const role of ['custom', 'compactionSummary', 'branchSummary', 'bashExecution']) test(`${role} Pi messages may sit between complete turns but cannot split a tool round`, () => {
  const extension = { role, content: [], timestamp: 0 } as unknown as AgentMessage;
  const call: AgentMessage = { role: 'assistant', content: [{ type: 'toolCall', id: 'call-1', name: 'echo', arguments: {} }], api: 'openai-responses', provider: 'fixture', model: 'fixture', usage: structuredClone(ZERO_USAGE), stopReason: 'toolUse', timestamp: 0 };
  const result: AgentMessage = { role: 'toolResult', toolCallId: 'call-1', toolName: 'echo', content: [{ type: 'text', text: 'done' }], isError: false, timestamp: 0 };
  assert.doesNotThrow(() => assertPaired([extension, call, result, extension]));
  assert.throws(() => assertPaired([call, extension, result]), (error: any) => error.code === 'PROTOCOL_ERROR');
});
