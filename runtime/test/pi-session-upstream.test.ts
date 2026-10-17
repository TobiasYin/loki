import { Type } from 'typebox';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createAssistantMessageEventStream, type AssistantMessage, type Model, type Usage, type Message, type UserMessage } from '@earendil-works/pi-ai';
import { Agent, type AgentMessage, type StreamFn } from '@earendil-works/pi-agent-core';

import { createPiSession, SessionManager, estimateContextTokens, estimateProjectedContextTokens, prepareCompaction, shouldCompact } from '@thor-agent/pi-session-browser';

const model: Model<'openai-responses'> = {
  id: 'pi-session-fixture', name: 'Pi session fixture', api: 'openai-responses', provider: 'fixture',
  baseUrl: 'https://fixture.invalid/v1', input: ['text', 'image'], reasoning: false,
  contextWindow: 8_000, maxTokens: 1_000,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
};
const zeroCost = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 };
function usage(input = 100, output = 10, cacheRead = 0): Usage {
  return { input, output, cacheRead, cacheWrite: 0, totalTokens: input + output + cacheRead, cost: { ...zeroCost } };
}
function assistant(text: string, tokens = 100, stopReason: AssistantMessage['stopReason'] = 'stop'): AssistantMessage {
  return { role: 'assistant', content: [{ type: 'text', text }], api: model.api, model: model.id,
    provider: model.provider, usage: usage(tokens), stopReason, timestamp: Date.now() - 1_000 };
}
function user(text: string): UserMessage { return { role: 'user', content: text, timestamp: Date.now() - 2_000 }; }
function finished(message: AssistantMessage) {
  const stream = createAssistantMessageEventStream();
  queueMicrotask(() => {
    stream.push({ type: 'start', partial: message });
    if (message.stopReason === 'error' || message.stopReason === 'aborted') stream.push({ type: 'error', reason: message.stopReason, error: message });
    else stream.push({ type: 'done', reason: message.stopReason as 'stop' | 'toolUse' | 'length', message });
    stream.end();
  });
  return stream;
}
function scripted(replies: AssistantMessage[]) {
  const calls: { messages: AgentMessage[]; signal?: AbortSignal }[] = [];
  const stream: StreamFn = (_model, context, options) => {
    calls.push({ messages: structuredClone(context.messages), signal: options?.signal });
    const next = replies.shift();
    assert.ok(next, 'Unexpected additional model request');
    return finished(structuredClone(next));
  };
  return { stream, calls };
}

const settings = { compaction: { enabled: true, reserveTokens: 500, keepRecentTokens: 800 }, retry: { enabled: false } };
function entries(messages: Message[], id = 'upstream-fixture') {
  const manager = SessionManager.inMemory('/thor', { id });
  for (const message of messages) manager.appendMessage(structuredClone(message));
  return [manager.getHeader()!, ...manager.getEntries()];
}
function seed(tokens = 7_600) {
  return entries([
    user('OLD TASK: preserve the requested destination and progress. ' + 'older context '.repeat(1_200)),
    assistant('Completed the first stage.', 100),
    user('RECENT REQUEST: continue to the destination. ' + 'recent context '.repeat(300)),
    assistant('Recent confirmed checkpoint.', tokens),
  ]);
}
function fixture(replies: AssistantMessage[], initialEntries = seed(), customSettings = settings) {
  const plan = scripted(replies);
  const agent = new Agent({ initialState: { model, thinkingLevel: 'off' }, streamFn: plan.stream });
  const owned = createPiSession({ agent, sessionId: 'upstream-fixture', model, entries: initialEntries,
    systemPrompt: 'You control the device using injected tools.', tools: [], settings: customSettings });
  return { ...owned, ...plan, agent };
}
const promptOptions = { expandPromptTemplates: false };

test('Pi usage calibration includes cached tokens and only estimates messages after the latest valid response', () => {
  const valid = assistant('latest response'); valid.usage = usage(500, 100, 6_000);
  const aborted = assistant('aborted attempt', 90_000, 'aborted');
  const estimate = estimateContextTokens([user('old input'), valid, user('x'.repeat(400)), aborted]);
  assert.equal(estimate.usageTokens, 6_600);
  assert.equal(estimate.lastUsageIndex, 1);
  assert.ok(estimate.trailingTokens >= 100 && estimate.trailingTokens < 200);
  assert.equal(estimate.tokens, estimate.usageTokens + estimate.trailingTokens);
  assert.equal(shouldCompact(7_500, 8_000, settings.compaction), false);
  assert.equal(shouldCompact(7_501, 8_000, settings.compaction), true);
});

test('official AgentSession automatically compacts before a new prompt using provider usage, then continues', async () => {
  const f = fixture([assistant('SUMMARY: keep the goal and completed stage.', 120), assistant('Continued from checkpoint.', 200)]);
  const events: any[] = []; f.session.subscribe(event => events.push(event));
  try {
    await f.session.prompt('Continue.', promptOptions);
    assert.equal(f.calls.length, 2, 'One summary request followed by the requested conversational response');
    assert.match(JSON.stringify(f.calls[0]!.messages), /OLD TASK/);
    const compacted = f.manager.getEntries().find((entry: any) => entry.type === 'compaction') as any;
    assert.ok(compacted, 'Pi must persist a genuine compaction entry');
    assert.equal(compacted.summary, 'SUMMARY: keep the goal and completed stage.');
    assert.equal(compacted.tokensBefore, 7_610);
    assert.equal(compacted.usage.totalTokens, 130);
    assert.ok(compacted.firstKeptEntryId);
    assert.match(JSON.stringify(f.calls[1]!.messages), /SUMMARY: keep the goal/);
    assert.doesNotMatch(JSON.stringify(f.calls[1]!.messages), /older context older context/);
    assert.ok(events.some(event => event.type === 'compaction_start' && event.reason === 'threshold'));
    assert.ok(events.some(event => event.type === 'compaction_end' && event.result && !event.aborted));
  } finally { f.dispose(); }
});

test('Pi compaction cut keeps a tool call and its oversized result together and restores that checkpoint from disk entries', () => {
  const call: AssistantMessage = { ...assistant(''), stopReason: 'toolUse', content: [{ type: 'toolCall', id: 'call-screenshot', name: 'screen_observe', arguments: {} }] };
  const result: Message = { role: 'toolResult', toolCallId: 'call-screenshot', toolName: 'screen_observe',
    content: [{ type: 'text', text: 'RECENT RESULT ' + 'visible detail '.repeat(300) }], isError: false, timestamp: Date.now() - 500 };
  const source = entries([user('OLD USER ' + 'old '.repeat(1_000)), assistant('old answer'), user('Inspect the screen'), call, result]);
  const manager = SessionManager.inMemory('/thor', undefined, structuredClone(source));
  const prepared = prepareCompaction(manager.getBranch(), { ...settings.compaction, keepRecentTokens: 80 })!;
  assert.ok(prepared);
  const kept = manager.getEntry(prepared.firstKeptEntryId) as any;
  assert.equal(kept.message.role, 'assistant');
  assert.equal(kept.message.content[0].id, 'call-screenshot');
  manager.appendCompaction('SUMMARY: inspect screen', prepared.firstKeptEntryId, prepared.tokensBefore, {}, false, usage(10));
  const projected = manager.buildSessionProjection();
  const projectedCall = projected.messages.findIndex((message: any) => message.role === 'assistant' && message.content.some((block: any) => block.type === 'toolCall' && block.id === 'call-screenshot'));
  assert.ok(projectedCall >= 0);
  assert.equal((projected.messages[projectedCall + 1] as any).toolCallId, 'call-screenshot');
  const reloaded = SessionManager.inMemory('/thor', undefined, structuredClone([manager.getHeader()!, ...manager.getEntries()]));
  assert.deepEqual(reloaded.buildSessionProjection(), projected);
  const calibrated = estimateProjectedContextTokens(projected, manager.getBranch());
  assert.equal(calibrated.lastUsageIndex, null, 'Pre-compaction provider usage must not retrigger compaction');
});

test('official AgentSession resumes a saved compaction entry without summarizing it again or dropping recent context', async () => {
  const first = fixture([assistant('SUMMARY: destination is the smithy.', 120), assistant('Reached the square.', 200)]);
  let snapshot: ReturnType<typeof first.snapshot>;
  try { await first.session.prompt('Continue.', promptOptions); snapshot = structuredClone(first.snapshot()); }
  finally { first.dispose(); }
  const second = fixture([assistant('Continuing toward the smithy.', 200)], snapshot!.entries);
  try {
    await second.session.prompt('Where should we go next?', promptOptions);
    assert.equal(second.calls.length, 1, 'Restored low-use checkpoint must not compact again');
    assert.match(JSON.stringify(second.calls[0]!.messages), /SUMMARY: destination is the smithy/);
    assert.match(JSON.stringify(second.calls[0]!.messages), /Reached the square/);
    assert.match(JSON.stringify(second.calls[0]!.messages), /Where should we go next/);
    assert.equal(second.manager.getEntries().filter((entry: any) => entry.type === 'compaction').length, 1);
  } finally { second.dispose(); }
});

test('official AgentSession recovers context overflow with a saved omission, compaction, and one continuation', async () => {
  const overflow = assistant('', 0, 'error'); overflow.usage = usage(0, 0); overflow.errorMessage = 'maximum context length exceeded';
  const f = fixture([overflow, assistant('SUMMARY: goal and progress preserved.', 120), assistant('Recovered and continued.', 200)], seed(200));
  const events: any[] = []; f.session.subscribe(event => events.push(event));
  try {
    await f.session.prompt('Continue after an overflowing request.', promptOptions);
    assert.equal(f.calls.length, 3);
    assert.ok(f.manager.getEntries().some((entry: any) => entry.type === 'context_edit' && entry.replacement === null), 'Failed attempt remains in raw history but is omitted from model context');
    assert.ok(f.manager.getEntries().some((entry: any) => entry.type === 'compaction'));
    assert.match(JSON.stringify(f.calls[2]!.messages), /SUMMARY: goal and progress preserved/);
    assert.ok(events.some(event => event.type === 'compaction_end' && event.reason === 'overflow' && event.willRetry));
    assert.equal(f.agent.state.messages.at(-1)?.role, 'assistant');
    assert.match(JSON.stringify(f.agent.state.messages.at(-1)), /Recovered and continued/);
  } finally { f.dispose(); }
});

test('a truncated Pi summary never becomes a durable checkpoint or discards the original history', async () => {
  const f = fixture([assistant('Partial summary must not persist.', 120, 'length'), assistant('Normal response retains the original history.', 200)]);
  const events: any[] = []; f.session.subscribe(event => events.push(event));
  try {
    await f.session.prompt('Continue.', promptOptions);
    assert.equal(f.manager.getEntries().filter((entry: any) => entry.type === 'compaction').length, 0);
    assert.match(JSON.stringify(f.calls[1]!.messages), /OLD TASK/);
    assert.ok(events.some(event => event.type === 'compaction_end' && event.errorMessage));
  } finally { f.dispose(); }
});

test('stopping during Pi auto-compaction cancels its model request and preserves the old session without starting a response', async () => {
  let started!: () => void; const began = new Promise<void>(resolve => { started = resolve; });
  let calls = 0;
  const stream: StreamFn = (_model, _context, options) => {
    // The official lifecycle may enter Agent.prompt after aborting preflight
    // compaction. A cancelled stream entry must never begin another request.
    if (options?.signal?.aborted) return finished(assistant('', 0, 'aborted'));
    calls++; const output = createAssistantMessageEventStream();
    const abort = () => {
      const message = assistant('', 0, 'aborted'); message.errorMessage = 'Cancelled';
      output.push({ type: 'error', reason: 'aborted', error: message }); output.end();
    };
    options?.signal?.addEventListener('abort', abort, { once: true });
    if (options?.signal?.aborted) abort();
    started(); return output;
  };
  const agent = new Agent({ initialState: { model, thinkingLevel: 'off' }, streamFn: stream });
  const f = createPiSession({ agent, sessionId: 'upstream-fixture', model, entries: seed(), systemPrompt: 'Fixture', tools: [], settings });
  try {
    const running = f.session.prompt('Continue.', promptOptions);
    await began; await f.session.abort(); await running;
    assert.equal(calls, 1);
    assert.equal(f.manager.getEntries().filter((entry: any) => entry.type === 'compaction').length, 0);
    assert.match(JSON.stringify(f.manager.getEntries()), /OLD TASK/);
    assert.equal(f.session.isIdle, true);
  } finally { f.dispose(); }
});

test('ordinary Pi follow-up after releasing memory preserves the exact prior message prefix and provider usage/signatures', async () => {
  const signed = assistant('confirmed output', 400);
  signed.responseId = 'provider-response-fixture';
  signed.usage = usage(100, 20, 800);
  signed.content = [
    { type: 'thinking', thinking: 'Recorded private reasoning fixture.', thinkingSignature: 'opaque-signature-fixture' },
    { type: 'text', text: 'confirmed output', textSignature: 'opaque-text-fixture' },
  ];
  const initialEntries = entries([user('Remember the selected destination.'), signed]);
  const first = fixture([assistant('First follow-up.', 400)], initialEntries);
  let snapshot: ReturnType<typeof first.snapshot>;
  let previous: AgentMessage[];
  try {
    await first.session.prompt('Continue once.', promptOptions);
    previous = structuredClone(first.agent.state.messages);
    snapshot = structuredClone(first.snapshot());
  } finally { first.dispose(); }
  const second = fixture([assistant('Second follow-up.', 400)], snapshot!.entries);
  try {
    await second.session.prompt('Continue again.', promptOptions);
    assert.equal(second.calls.length, 1);
    assert.deepEqual(second.calls[0]!.messages.slice(0, previous!.length), previous!);
    const restored = second.calls[0]!.messages.find(message => message.role === 'assistant' && message.responseId === signed.responseId);
    assert.deepEqual(restored, signed);
    assert.equal(second.manager.getEntries().filter((entry: any) => entry.type === 'compaction').length, 0);
  } finally { second.dispose(); }
});

test('official Pi checks threshold between tool turns and continues with the confirmed tool pair after compaction', async () => {
  const call: AssistantMessage = { ...assistant('', 7_600), stopReason: 'toolUse',
    content: [{ type: 'toolCall', id: 'live-probe', name: 'probe', arguments: {} }] };
  const plan = scripted([call, assistant('SUMMARY: prior progress preserved.', 120), assistant('Read the probe result and continued.', 200)]);
  const agent = new Agent({ initialState: { model, thinkingLevel: 'off' }, streamFn: plan.stream });
  let executions = 0;
  const tool = { name: 'probe', label: 'Probe', description: 'Return a confirmed value.', parameters: Type.Object({}),
    execute: async () => { executions++; return { content: [{ type: 'text' as const, text: 'CONFIRMED_PROBE_VALUE' }], details: {} }; } };
  const f = createPiSession({ agent, sessionId: 'upstream-fixture', model, entries: seed(200),
    systemPrompt: 'Use the injected probe.', tools: [tool], settings });
  try {
    await f.session.prompt('Inspect once and report.', promptOptions);
    assert.equal(executions, 1);
    assert.equal(plan.calls.length, 3);
    assert.ok(f.manager.getEntries().some((entry: any) => entry.type === 'compaction'));
    const last = plan.calls[2]!.messages;
    const callIndex = last.findIndex(message => message.role === 'assistant' && message.content.some(block => block.type === 'toolCall' && block.id === 'live-probe'));
    assert.ok(callIndex >= 0);
    assert.equal((last[callIndex + 1] as any).toolCallId, 'live-probe');
    assert.match(JSON.stringify(last[callIndex + 1]), /CONFIRMED_PROBE_VALUE/);
    assert.match(JSON.stringify(last), /SUMMARY: prior progress preserved/);
  } finally { f.dispose(); }
});
