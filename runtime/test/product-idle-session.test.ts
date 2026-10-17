import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createContext, runInContext } from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import type { HistoryTurn } from '../src/index.js';
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
  function start(f: F, text: string, history: HistoryTurn[] = []) {
    runs.set(f.lease.runId, f);
    const model = f.snapshot.session.model;
    const begun = { lease: f.lease, input: [{ type: 'text', text }], history, runTimeoutMs: 0,
      network: { purpose: 'model', binding: { runId: f.lease.runId }, profileRef: model.profileId, credentialRef: model.credentialRef, timeoutMs: 0 } };
    return nativeWindow.runPreparedNative(begun) as Promise<void>;
  }
  return { ready, start, diagnostics: () => nativeWindow.__thorRuntimeDiagnostics(), stop: () => nativeWindow.stopAllNativeRuns(),
    get bridgeCloses() { return bridgeCloses; }, get endCalls() { return endCalls; },
    holdEnd: () => { endGate = deferred(); return endGate; }, rejectEnd: () => { rejectEnd = true; } };
}
function assertIdle(h: ReturnType<typeof harness>) {
  const diagnostic = h.diagnostics();
  assert.equal(diagnostic.executionCount, 0);
  assert.equal(diagnostic.residentSessionCount, 0);
  assert.equal(diagnostic.pendingRpcCount, 0);
}

test('product drops idle Pi Agents and restores completed conversation context from Native history', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const first = fixture('openai-responses', [{ text: 'saved-first-answer' }]);
  await h.start(first, 'remember disk goal'); assertIdle(h);
  const second = fixture('openai-completions', [{ text: 'continued with another provider' }]);
  await h.start(second, 'continue the same conversation', [first.turns.at(-1)!]); assertIdle(h);
  const body = JSON.stringify(second.requests[0]!.body);
  for (const text of ['remember disk goal', 'saved-first-answer', 'continue the same conversation']) assert.ok(body.includes(text));
  assert.ok(!body.includes('idle native view must not become runtime context'));
  assert.ok(second.events.some(e => e.type === 'context' && e.data.historyRestored === true && e.data.piSessionReused === false));
  assert.equal(h.bridgeCloses, 4); assert.equal(h.endCalls, 2);
});

test('product unloads after a failed tool round and restores confirmed results without replay', { timeout: 5000 }, async () => {
  const h = harness(); await h.ready;
  const failed = fixture('openai-responses', [{ call: { name: 'skills_list', id: 'confirmed-read', args: '{}' } }, { status: 500 }]);
  failed.snapshot.availability['skills.list'] = { status: 'ready' };
  await h.start(failed, 'retain this research goal'); assertIdle(h);
  assert.equal(failed.runs.at(-1)!.state, 'failed'); assert.equal(failed.executions.length, 1);
  const saved = failed.turns.at(-1)!;
  assert.equal(saved.complete, false); assert.ok(JSON.stringify(saved.messages).includes('echo-result'));
  const followup = fixture('openai-responses', [{ text: 'continued from the stored result' }]);
  await h.start(followup, 'continue without repeating the completed read', [saved]); assertIdle(h);
  const body = JSON.stringify(followup.requests[0]!.body);
  assert.ok(body.includes('retain this research goal')); assert.ok(body.includes('echo-result')); assert.ok(body.includes('interrupted'));
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
  await h.start(resumed, 'continue cancelled chat', [first.turns.at(-1)!]); assertIdle(h);
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
