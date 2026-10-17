import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createContext, runInContext } from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import type { PendingPiInput, PiHistoryState } from '../src/pi-history.js';
import { SessionManager } from '@thor-agent/pi-session-browser';
import { fixture } from './fixtures.js';

// Run the actual browser product host and Pi SDK. Only the Native wire is
// replaced: Native persistence, authority and SSE remain explicit fixture ports.
const bundle = await build({
  entryPoints: [fileURLToPath(new URL('../../../integration/runtime-host.ts', import.meta.url))],
  bundle: true, write: false, format: 'iife', platform: 'browser', target: 'es2022',
  define: { process: 'undefined', __THOR_DEBUG__: 'true' },
  plugins: [{ name: 'native-test-wire', setup(b) {
    b.onResolve({ filter: /native-bridge\/js\/fetch-adapter\.js$/ }, () => ({ path: 'native-wire', namespace: 'native-test-wire' }));
    b.onLoad({ filter: /.*/, namespace: 'native-test-wire' }, () => ({ contents: `
      export const decodeFrame = raw => JSON.parse(raw);
      export const createNativeFetch = options => globalThis.__fixtureBridge(options);
    `, loader: 'js' }));
  } }],
});
const code = bundle.outputFiles[0]!.text;
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; };
type F = ReturnType<typeof fixture>;
function harness() {
  const runs = new Map<string, F>();
  const sessions = new Map<string, PiHistoryState>();
  const pendingInputs = new Map<string, PendingPiInput[]>();
  const inputAcks: { inputRunId: string; savingRunId: string; entryId: string; content: unknown }[] = [];
  const committedInputs = new Set<string>();
  let piWrites = 0;
  let bridgeCloses = 0, endCalls = 0, rejectEnd = false;
  let endGate: ReturnType<typeof deferred> | undefined;
  const nativeWindow: any = {};
  const context = createContext({
    window: nativeWindow, console, Request, Response, Headers, ReadableStream, TextEncoder, TextDecoder,
    AbortController, AbortSignal, DOMException, URL, URLSearchParams, Blob, FormData,
    crypto: globalThis.crypto, performance, structuredClone, setTimeout, clearTimeout, setInterval, clearInterval,
    queueMicrotask, btoa, atob,
    __fixtureBridge: (options: { context: () => any }) => ({
      fetch: (input: RequestInfo | URL, init?: RequestInit) => {
        const envelope = options.context();
        return runs.get(envelope.binding.runId)!.ports.network.fetch(input, init, envelope);
      },
      receive: () => {}, close: () => { bridgeCloses++; },
    }),
  });
  function emit(value: unknown) { nativeWindow.ThorNative.onmessage({ data: JSON.stringify(value) }); }
  async function request(message: any) {
    const data = message.dataInBody ? JSON.parse(Buffer.from(message.bytes, 'base64').toString()) : message.data;
    if (message.op === 'runtime.ui.event') return;
    try {
      let value: unknown = {};
      if (message.op === 'view') value = { sessions: [...runs.values()].map(f => f.snapshot.session), activeRuns: [], messages: [{ text: 'idle native view must not become runtime context' }] };
      else if (message.op === 'authority') value = structuredClone(runs.get(data.runId)!.snapshot);
      else if (message.op === 'persist') {
        const f = runs.get(data.runId)!;
        if (data.kind === 'event') await f.ports.persistence.appendEvent(data.value);
        else if (data.kind === 'commit') await f.ports.persistence.commitRun(data.value, data.event);
        else if (data.kind === 'run') await f.ports.persistence.saveRun(data.value);
        else if (data.kind === 'turn') await f.ports.persistence.saveTurn(data.sessionId, data.value);
        else if (data.kind === 'context') await f.ports.persistence.saveContext(data.value);
        else if (data.kind === 'pi_session') {
          assert.equal(data.sessionId, f.lease.sessionId); assert.equal(data.runId, f.lease.runId);
          const update = data.value;
          let stored = sessions.get(data.sessionId);
          if (!stored) {
            stored = { header: structuredClone(update.header), entries: [], leafId: null, provenance: {} };
            sessions.set(data.sessionId, stored);
          } else assert.deepEqual(update.header, stored.header);
          for (const entry of update.entries) {
            const existing: PiHistoryState['entries'][number] | undefined = stored.entries.find(candidate => candidate.id === entry.id);
            if (existing) assert.deepEqual(entry, existing, 'Native never rewrites an original Pi entry');
            else {
              assert.ok(entry.parentId === null || stored.entries.some(candidate => candidate.id === entry.parentId));
              stored.entries.push(structuredClone(entry));
            }
            stored.provenance[entry.id] = [...new Set([...(stored.provenance[entry.id] ?? []), ...(update.entrySources?.[entry.id]??update.source)])] as any;
          }
          assert.ok(update.leafId === null || stored.entries.some(entry => entry.id === update.leafId));
          const inputRunIds=update.inputRunIds??(update.inputRunId?{[update.entries.find((entry:any)=>entry.type==='message' && entry.message.role==='user').id]:update.inputRunId}:{});
          for (const [entryId,inputRunId] of Object.entries(inputRunIds) as [string,string][]) {
            const inputRun = runs.get(inputRunId);
            assert.equal(inputRun?.lease.sessionId, data.sessionId, 'Native acknowledges only an input in the same conversation');
            const userEntry = update.entries.find((entry: any) => entry.id===entryId && entry.type === 'message' && entry.message.role === 'user');
            assert.ok(userEntry, 'the acknowledgement is atomic with an original Pi user entry');
            inputAcks.push({ inputRunId, savingRunId: data.runId, entryId: userEntry.id, content: structuredClone(userEntry.message.content) });
            committedInputs.add(inputRunId);
          }
          stored.leafId = update.leafId; piWrites++;
        } else throw new Error(`Unexpected persistence kind ${data.kind}`);
      } else if (message.op === 'end') {
        endCalls++;
        await endGate?.promise;
        if (rejectEnd) throw new Error('native-end-rejected');
      } else if (message.op === 'tool.prepare') value = { awaiting: false };
      else if (message.op === 'tool.execute') value = await runs.get(data.runId)!.ports.tools.execute(data, new AbortController().signal);
      else if (message.op !== 'tool.cancel') throw new Error(`Unexpected fixture RPC ${message.op}`);
      emit({ type: 'host.result', requestId: message.requestId, value });
    } catch {
      emit({ type: 'host.result', requestId: message.requestId, value: { error: 'IO_ERROR' } });
    }
  }
  nativeWindow.ThorNative = { postMessage(raw: string) {
    const message = JSON.parse(raw);
    if (message.type === 'bridge.hello') queueMicrotask(() => emit({ type: 'bridge.ready', connectionId: 'fixture-connection' }));
    else void request(message);
  } };
  runInContext(code, context);
  const ready = new Promise<void>(resolve => queueMicrotask(resolve));
  function begin(f: F, text: string) {
    const model = f.snapshot.session.model;
    return { lease: f.lease, input: [{ type: 'text', text }], piSession: structuredClone(sessions.get(f.lease.sessionId) ?? null),
      pendingInputs: structuredClone((pendingInputs.get(f.lease.sessionId) ?? []).filter(input => !committedInputs.has(input.runId))), legacyHistory: [], runTimeoutMs: 0,
      network: { purpose: 'model', binding: { runId: f.lease.runId }, profileRef: model.profileId, credentialRef: model.credentialRef, timeoutMs: 0 } };
  }
  function start(f: F, text: string) {
    runs.set(f.lease.runId, f);
    return nativeWindow.runPreparedNative(begin(f, text)) as Promise<void>;
  }
  return { ready, start, diagnostics: () => nativeWindow.__thorRuntimeDiagnostics(), stop: () => nativeWindow.stopAllNativeRuns(),
    get bridgeCloses() { return bridgeCloses; }, get endCalls() { return endCalls; }, get piWrites() { return piWrites; },
    stored: (sid: string) => structuredClone(sessions.get(sid)),
    storeSnapshot: (sid: string, snapshot: PiHistoryState) => sessions.set(sid, structuredClone(snapshot)),
    seedPending: (f: F, text: string) => {
      runs.set(f.lease.runId, f);
      pendingInputs.set(f.lease.sessionId, [...(pendingInputs.get(f.lease.sessionId) ?? []), { runId: f.lease.runId, content: [{ type: 'text', text }] }]);
    },
    acknowledgements: () => structuredClone(inputAcks),
    holdEnd: () => { endGate = deferred(); return endGate; }, rejectEnd: () => { rejectEnd = true; } };
}
function assertIdle(h: ReturnType<typeof harness>) {
  const diagnostic = h.diagnostics();
  assert.equal(diagnostic.executionCount, 0);
  assert.equal(diagnostic.residentSessionCount, 0);
  assert.equal(diagnostic.pendingRpcCount, 0);
}

test('product drops idle Pi Agents and restores the original Pi journal from Native storage', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const first = fixture('openai-responses', [{ text: 'saved-first-answer' }]);
  await h.start(first, 'remember disk goal'); assertIdle(h);
  const saved = h.stored(first.lease.sessionId)!; assert.ok(saved.entries.length > 0); assert.ok(h.piWrites > 0);
  first.turns.splice(0); first.events.splice(0); // Display data is not the restored context source.
  const second = fixture('openai-completions', [{ text: 'continued with another provider' }]);
  await h.start(second, 'continue the same conversation'); assertIdle(h);
  const body = JSON.stringify(second.requests[0]!.body);
  for (const text of ['remember disk goal', 'saved-first-answer', 'continue the same conversation']) assert.ok(body.includes(text));
  assert.ok(!body.includes('idle native view must not become runtime context'));
  assert.ok(second.events.some(e => e.type === 'context' && e.data.historyRestored === true && e.data.contextManager === 'pi'));
  const restored = h.stored(first.lease.sessionId)!;
  for (const entry of saved.entries) assert.deepEqual(restored.entries.find(candidate => candidate.id === entry.id), entry);
  assert.equal(h.bridgeCloses, 4); assert.equal(h.endCalls, 2);
});

test('product unloads after a failed tool round and restores confirmed results without replay', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const failed = fixture('openai-responses', [{ call: { name: 'skills_list', id: 'confirmed-read', args: '{}' } }, { status: 500 }]);
  failed.snapshot.availability['skills.list'] = { status: 'ready' };
  failed.snapshot.session.model.piSettings = { retry: { enabled: false } };
  await h.start(failed, 'retain this research goal'); assertIdle(h);
  assert.equal(failed.runs.at(-1)!.state, 'failed'); assert.equal(failed.executions.length, 1);
  const saved = h.stored(failed.lease.sessionId)!;
  assert.ok(JSON.stringify(saved.entries).includes('echo-result'));
  const confirmed = saved.entries.find(entry => entry.type === 'message' && entry.message.role === 'toolResult')!;
  assert.ok(confirmed, 'the confirmed native result is stored as an original Pi message');
  assert.ok(saved.entries.some(entry => entry.type === 'message' && entry.message.role === 'assistant' && entry.message.stopReason === 'error'), 'the failed terminal assistant entry is retained');
  failed.turns.splice(0); failed.events.splice(0);
  const followup = fixture('openai-responses', [{ text: 'continued from the stored result' }]);
  await h.start(followup, 'continue without repeating the completed read'); assertIdle(h);
  const body = JSON.stringify(followup.requests[0]!.body);
  assert.ok(body.includes('retain this research goal')); assert.ok(body.includes('echo-result'));
  const restored = h.stored(failed.lease.sessionId)!;
  for (const entry of saved.entries) assert.deepEqual(restored.entries.find(candidate => candidate.id === entry.id), entry, 'failed terminal entries retain their original IDs, content and metadata');
  assert.equal(followup.executions.length, 0);
  assert.ok(followup.events.some(e => e.type === 'context' && e.data.historyRestored === true));
});

test('product retains only running siblings and closes cancellation bridges before becoming idle', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const first = fixture('openai-responses', [{ wait: true }]);
  const arrived = deferred(); first.setOnRequest(() => arrived.resolve());
  const running = h.start(first, 'keep running until explicitly stopped'); await arrived.promise;
  assert.equal(h.diagnostics().residentSessionCount, 1);
  const second = fixture('openai-completions', [{ text: 'independent chat finished' }]);
  second.lease.sessionId = second.snapshot.session.sessionId = 'other-chat';
  await h.start(second, 'do another independent task');
  assert.equal(h.diagnostics().executionCount, 1); assert.equal(h.diagnostics().residentSessionCount, 1);
  assert.equal(h.bridgeCloses, 2);
  h.stop(); await running; assertIdle(h);
  assert.equal(first.runs.at(-1)!.state, 'cancelled'); assert.equal(h.bridgeCloses, 4);
  const resumed = fixture('openai-responses', [{ text: 'resumed after stop' }]);
  await h.start(resumed, 'continue cancelled chat'); assertIdle(h);
  assert.ok(JSON.stringify(resumed.requests[0]!.body).includes('keep running until explicitly stopped'));
  assert.equal(resumed.executions.length, 0);
});

test('product waits for Native end acknowledgement, then releases the session even if end is rejected', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const gate = h.holdEnd(); h.rejectEnd();
  const f = fixture('openai-responses', [{ text: 'completed before end acknowledgement' }]);
  const running = h.start(f, 'finish and await native end');
  while (h.endCalls === 0) await new Promise(resolve => setTimeout(resolve, 1));
  assert.equal(h.diagnostics().executionCount, 1); assert.equal(h.diagnostics().residentSessionCount, 1);
  assert.equal(h.bridgeCloses, 0);
  gate.resolve(); await running; assertIdle(h); assert.equal(h.bridgeCloses, 2);
});


test('product restores an uncommitted Native user input into Pi and acknowledges its original Run separately from the current prompt', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const first = fixture('openai-responses', [{ text: 'first saved response' }]);
  await h.start(first, 'original completed task'); assertIdle(h);
  const interrupted = fixture();
  h.seedPending(interrupted, 'PENDING_NATIVE_USER_INPUT_BEFORE_PROCESS_EXIT');
  const resumed = fixture('openai-responses', [{ text: 'continued using both inputs' }]);
  await h.start(resumed, 'CURRENT_FOLLOWUP_USER_INPUT'); assertIdle(h);
  assert.equal(resumed.runs.at(-1)!.state, 'completed'); assert.equal(resumed.requests.length, 1);
  const body = JSON.stringify(resumed.requests[0]!.body);
  for (const text of ['original completed task', 'first saved response', 'PENDING_NATIVE_USER_INPUT_BEFORE_PROCESS_EXIT', 'CURRENT_FOLLOWUP_USER_INPUT']) assert.ok(body.includes(text));
  assert.ok(body.indexOf('PENDING_NATIVE_USER_INPUT_BEFORE_PROCESS_EXIT') < body.indexOf('CURRENT_FOLLOWUP_USER_INPUT'));
  const acks = h.acknowledgements().filter(ack => ack.savingRunId === resumed.lease.runId);
  assert.deepEqual(acks.map(ack => ack.inputRunId), [interrupted.lease.runId, resumed.lease.runId]);
  assert.ok(JSON.stringify(acks[0]!.content).includes('PENDING_NATIVE_USER_INPUT_BEFORE_PROCESS_EXIT'));
  assert.ok(JSON.stringify(acks[1]!.content).includes('CURRENT_FOLLOWUP_USER_INPUT'));
  assert.notEqual(acks[0]!.entryId, acks[1]!.entryId);
  assert.equal(resumed.executions.length, 0, 'restoring the input cannot replay a device or tool operation');

  const later = fixture('openai-responses', [{ text: 'next response' }]);
  await h.start(later, 'later followup'); assertIdle(h);
  assert.equal(h.acknowledgements().filter(ack => ack.inputRunId === interrupted.lease.runId).length, 1, 'a committed pending input is never acknowledged or inserted again');
  const saved = h.stored(first.lease.sessionId)!;
  assert.equal(saved.entries.filter(entry => entry.type === 'message' && entry.message.role === 'user' && JSON.stringify(entry.message.content).includes('PENDING_NATIVE_USER_INPUT_BEFORE_PROCESS_EXIT')).length, 1);
});

test('product permission branch copies never acknowledge old user messages as the new Native input', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const first = fixture('openai-responses', [{ text: 'PRIVATE_SCREEN_DERIVED_ANSWER' }]);
  await h.start(first, 'ORIGINAL_USER_TARGET_FOR_BRANCH'); assertIdle(h);
  const original = h.stored(first.lease.sessionId)!;
  const manager = SessionManager.inMemory('/thor', { id: first.lease.sessionId }, [original.header, ...original.entries]);
  const originalUser = original.entries.find(entry => entry.type === 'message' && entry.message.role === 'user')!;
  const summary = manager.appendCompaction('PRIVATE_SCREEN_DERIVED_SUMMARY', originalUser.id, 10000, {});
  h.storeSnapshot(first.lease.sessionId, { header: manager.getHeader()!, entries: manager.getEntries(), leafId: manager.getLeafId(),
    provenance: { ...original.provenance, [summary]: ['screen_perception'] } });
  const resumed = fixture('openai-responses', [{ text: 'respond using allowed user input' }]);
  resumed.snapshot.session.enabled.screen_perception = false;
  await h.start(resumed, 'CURRENT_USER_AFTER_PERMISSION_REVOKED'); assertIdle(h);
  assert.equal(resumed.runs.at(-1)!.state, 'completed');
  const body = JSON.stringify(resumed.requests[0]!.body);
  assert.ok(body.includes('ORIGINAL_USER_TARGET_FOR_BRANCH')); assert.ok(body.includes('CURRENT_USER_AFTER_PERMISSION_REVOKED'));
  assert.ok(!body.includes('PRIVATE_SCREEN_DERIVED_ANSWER')); assert.ok(!body.includes('PRIVATE_SCREEN_DERIVED_SUMMARY'));
  const saved = h.stored(first.lease.sessionId)!;
  const copied = saved.entries.filter(entry => entry.type === 'message' && entry.message.role === 'user' && JSON.stringify(entry.message.content).includes('ORIGINAL_USER_TARGET_FOR_BRANCH'));
  assert.equal(copied.length, 2, 'the original inactive entry and an official authorized branch copy both remain durable');
  const acks = h.acknowledgements().filter(ack => ack.savingRunId === resumed.lease.runId);
  assert.equal(acks.length, 1); assert.equal(acks[0]!.inputRunId, resumed.lease.runId);
  assert.ok(JSON.stringify(acks[0]!.content).includes('CURRENT_USER_AFTER_PERMISSION_REVOKED'));
  assert.ok(copied.every(entry => !acks.some(ack => ack.entryId === entry.id)), 'a restored or copied user entry never commits the current prompt');
  assert.equal(h.acknowledgements().filter(ack => ack.inputRunId === first.lease.runId).length, 1);
});
