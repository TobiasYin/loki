import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, ToolRegistry, RuntimeError, type ToolDescriptor, type ToolPack } from '../src/index.js';
import { agentSleepTool } from '../src/agent-tools.js';
import { descriptor, fixture } from './fixtures.js';

function registry(): ToolRegistry {
  const result = new ToolRegistry();
  result.register({ packId: 'thor.agent', packVersion: '1', tools: [agentSleepTool] });
  return result;
}
function ready(f: ReturnType<typeof fixture>): void {
  for (const capability of Object.keys(f.snapshot.session.enabled)) f.snapshot.session.enabled[capability as keyof typeof f.snapshot.session.enabled] = false;
  f.snapshot.availability['agent.sleep'] = { status: 'ready' };
}
function timer(f: ReturnType<typeof fixture>, started?: () => void, onCancel?: () => void): void {
  f.setTool((envelope, signal) => new Promise((resolve, reject) => {
    const cancel = () => { clearTimeout(pending); onCancel?.(); reject(new RuntimeError('CANCELLED')); };
    const pending = setTimeout(() => {
      signal.removeEventListener('abort', cancel);
      resolve({ content: [{ type: 'text', text: JSON.stringify({ durationMs: (envelope.arguments as any).durationMs, elapsedMs: (envelope.arguments as any).durationMs }) }], observedAt: Date.now(), source: 'agent-runtime', outcome: 'observed', truncated: false });
    }, (envelope.arguments as any).durationMs);
    signal.addEventListener('abort', cancel, { once: true });
    started?.();
    if (signal.aborted) cancel();
  }));
}

test('only the exact built-in sleep contract can register without capabilities', () => {
  assert.equal(registry().get('agent_sleep')?.toolId, 'agent.sleep');
  const variations: Partial<ToolDescriptor>[] = [
    { toolId: 'agent.other' }, { modelName: 'agent_wait' }, { schemaVersion: '2' }, { executor: 'native-device' },
    { resources: ['selected-screen/snapshot'] }, { requiredCapabilities: ['screen_control'] }, { effectClass: 'write' },
    { provenance: 'foreign' }, { concurrencyKey: 'device' }, { cancellable: false }, { timeoutMs: 65001 },
    { platformPrerequisites: [] }, { modelVisibility: false },
    { outputPolicy: { ...agentSleepTool.outputPolicy, maxImages: 1 } },
    { inputSchema: { ...agentSleepTool.inputSchema, additionalProperties: true } },
    { inputSchema: { type: 'object', properties: { durationMs: { type: 'number', minimum: 0, maximum: 1e9 } }, required: ['durationMs'], additionalProperties: false } },
  ];
  for (const override of variations) assert.throws(() => new ToolRegistry().register({ packId: 'thor.agent', packVersion: '1', tools: [{ ...agentSleepTool, ...override }] }), e => e instanceof RuntimeError && e.code === 'UNSUPPORTED');
  for (const pack of [{ packId: 'foreign', packVersion: '1' }, { packId: 'thor.agent', packVersion: '2' }]) assert.throws(() => new ToolRegistry().register({ ...pack, tools: [agentSleepTool] }));
  assert.throws(() => new ToolRegistry().register({ packId: 'foreign', packVersion: '1', tools: [descriptor('unscoped', [])] }));
  // Property order is not part of the schema's meaning.
  const reordered: ToolPack = { packId: 'thor.agent', packVersion: '1', tools: [{ ...agentSleepTool, inputSchema: { additionalProperties: false, required: ['durationMs'], properties: { durationMs: { maximum: 60000, minimum: 1, type: 'integer' } }, type: 'object' } }] };
  assert.doesNotThrow(() => new ToolRegistry().register(reordered));
});

test('sleep validates integer milliseconds 1..60000 and rejects all extra arguments', () => {
  const r = registry();
  for (const durationMs of [1, 25, 1000, 60000]) assert.equal(r.validate('agent_sleep', { durationMs }).toolId, 'agent.sleep');
  for (const args of [{}, null, [], { durationMs: 0 }, { durationMs: -1 }, { durationMs: 60001 }, { durationMs: 1.5 }, { durationMs: '10' }, { durationMs: true }, { durationMs: Number.NaN }, { durationMs: Infinity }, { durationMs: 10, shell: 'sleep' }]) assert.throws(() => r.validate('agent_sleep', args));
});

test('sleep is independent of device capabilities but requires its own Native availability', () => {
  const f = fixture(); ready(f); const r = registry();
  assert.deepEqual(r.effective(f.snapshot).map(t => t.toolId), ['agent.sleep']);
  for (const status of ['backend_missing', 'permission_missing', 'temporarily_unavailable', 'unsupported'] as const) {
    f.snapshot.availability['agent.sleep'] = { status };
    assert.deepEqual(r.effective(f.snapshot), []);
  }
  delete f.snapshot.availability['agent.sleep'];
  assert.deepEqual(r.effective(f.snapshot), []);
});

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  test(`${protocol}: Pi calls Native sleep with all device capabilities disabled, then continues`, async () => {
    const f = fixture(protocol, [{ call: { name: 'agent_sleep', args: '{"durationMs":8}' } }, { text: '等待结束，仍需重新观察。' }]); ready(f); timer(f);
    // The app may animate between the model request and timer dispatch. Sleep
    // has no screenshot/node/coordinate authority and must still be executable.
    f.setOnRequest(i => { if (i === 0) f.snapshot.l0.snapshotId = 'animation-frame-changed'; });
    assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'Pause briefly before observing.' }])).state, 'completed');
    assert.deepEqual(f.executions.map(e => e.toolId), ['agent.sleep']);
    assert.deepEqual(f.executions[0]!.arguments, { durationMs: 8 });
    assert.deepEqual(f.executions[0]!.resources, ['agent-runtime']);
    assert.equal(f.requests.length, 2);
    assert.ok(JSON.stringify(f.requests[0]!.body).includes('A completed timer is not evidence'));
    const result = f.events.find(e => e.type === 'tool_result')!;
    assert.equal(result.data.error, undefined); assert.equal(result.data.outcome, 'observed');
    assert.deepEqual(f.turns.at(-1)!.source, []);
    assert.deepEqual(f.turns.at(-1)!.effects, []);
  });

  test(`${protocol}: Stop cancels a long sleep immediately and does not start another model round`, async () => {
    const f = fixture(protocol, [{ call: { name: 'agent_sleep', args: '{"durationMs":60000}' } }, { text: 'Must not run.' }]); ready(f);
    let entered!: () => void; const started = new Promise<void>(resolve => { entered = resolve; }); let cancelled = false;
    timer(f, entered, () => { cancelled = true; });
    const runtime = new ThorRuntime(f.ports, registry()); const running = runtime.run(f.lease, [{ type: 'text', text: 'Wait until stopped.' }]);
    await started; const before = Date.now(); runtime.stop();
    assert.equal((await running).state, 'cancelled');
    assert.ok(Date.now() - before < 1000, 'Stop must not wait for the 60-second timer');
    assert.ok(cancelled); assert.equal(f.executions.length, 1); assert.equal(f.requests.length, 1);
  });

  test(`${protocol}: invalid model sleep arguments never reach Native`, async () => {
    const f = fixture(protocol, [{ call: { name: 'agent_sleep', args: '{"durationMs":1.5,"command":"sleep"}' } }, { text: 'Use integer milliseconds only.' }]); ready(f);
    await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'Validate before waiting.' }]);
    assert.equal(f.executions.length, 0);
  });

  test(`${protocol}: Native cancellation terminates before a delayed authority publication or another model request`, async () => {
    const f = fixture(protocol, [{ call: { name: 'agent_sleep', args: '{"durationMs":60000}' } }, { text: 'Must not generate after Native Stop.' }]); ready(f);
    let nativeCancelled = false, authorityAfterCancellation = 0;
    const readAuthority = f.ports.authority.read;
    f.ports.authority.read = async (...args) => {
      if (nativeCancelled) { authorityAfterCancellation++; throw new RuntimeError('STALE_AUTHORIZATION'); }
      return readAuthority(...args);
    };
    f.setTool(async () => {
      nativeCancelled = true;
      // Do not call runtime.stop/authorizationChanged: this recreates Native's
      // cancellation receipt arriving before its later state publication.
      return { error: 'CANCELLED', content: [{ type: 'text', text: 'PRIVATE_CANCELLED_BODY' }], source: 'agent-timer', observedAt: Date.now(), outcome: 'observed', truncated: false };
    });
    const runtime = new ThorRuntime(f.ports, registry());
    const result = await runtime.run(f.lease, [{ type: 'text', text: 'Remember this synthetic interrupted wait.' }]);
    assert.equal(result.state, 'cancelled'); assert.equal(result.error, 'CANCELLED');
    assert.equal(f.executions.length, 1); assert.equal(f.requests.length, 1);
    assert.equal(authorityAfterCancellation, 0, 'Cancellation must bypass the already-revoked authority RPC');
    assert.equal(f.events.filter(e => e.type === 'model_round').length, 1);
    const toolResult = f.events.find(e => e.type === 'tool_result')!;
    assert.equal(toolResult.data.error, 'CANCELLED'); assert.equal(toolResult.data.outcome, 'observed');
    assert.ok(f.events.findIndex(e => e === toolResult) < f.events.findIndex(e => e.type === 'state' && e.data.state === 'stopping'));
    assert.equal(f.turns.at(-1)!.complete, false);
    assert.ok(JSON.stringify(f.turns.at(-1)).includes('Remember this synthetic interrupted wait.'));
    assert.ok(!JSON.stringify([f.events, f.turns, f.requests]).includes('PRIVATE_CANCELLED_BODY'));
    assert.equal(runtime.diagnostics().reserved, false);
  });

  test(`${protocol}: a stale tool-check lease commits its result before abort and never requests another model round`, async () => {
    const f = fixture(protocol, [{ call: { name: 'agent_sleep', args: '{"durationMs":10}' } }, { text: 'Must not generate after revocation.' }]); ready(f);
    f.setOnRequest(i => { if (i === 0) f.snapshot.lease.capabilityEpoch++; });
    let resultEntered!: () => void, releaseResult!: () => void;
    const entered = new Promise<void>(resolve => { resultEntered = resolve; });
    const held = new Promise<void>(resolve => { releaseResult = resolve; });
    const persist = f.ports.persistence.appendEvent;
    f.ports.persistence.appendEvent = async event => {
      if (event.type === 'tool_result') { resultEntered(); await held; }
      await persist(event);
    };
    const runtime = new ThorRuntime(f.ports, registry());
    const running = runtime.run(f.lease, [{ type: 'text', text: 'Do not continue a revoked task.' }]);
    await entered;
    assert.equal(runtime.diagnostics().stopRequested, false, 'The terminal tool result must finish committing before automatic abort');
    assert.equal(f.requests.length, 1); releaseResult();
    const result = await running;
    assert.equal(result.state, 'cancelled'); assert.equal(result.error, 'STALE_AUTHORIZATION');
    assert.equal(f.executions.length, 0); assert.equal(f.requests.length, 1);
    assert.equal(f.events.find(e => e.type === 'tool_result')!.data.error, 'STALE_AUTHORIZATION');
    assert.ok(f.events.some(e => e.type === 'authorization_changed'));
    assert.equal(f.turns.at(-1)!.complete, false);
  });

  test(`${protocol}: a tool-local timeout remains recoverable and can inform the next model round`, async () => {
    const f = fixture(protocol, [{ call: { name: 'agent_sleep', args: '{"durationMs":10}' } }, { text: 'The timer timed out; no success is claimed.' }]); ready(f);
    f.setTool(async () => ({ error: 'TIMEOUT', content: [{ type: 'text', text: 'Timer deadline expired.' }], source: 'agent-timer', observedAt: Date.now(), outcome: 'observed', truncated: false }));
    const result = await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'Handle a timer-local failure.' }]);
    assert.equal(result.state, 'completed'); assert.equal(f.requests.length, 2);
    assert.equal(f.events.find(e => e.type === 'tool_result')!.data.error, 'TIMEOUT');
  });
}

test('text-only models without tool support receive no sleep tool or wait instructions', async () => {
  const f = fixture(); ready(f); f.snapshot.session.model.supportsTools = false;
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'Reply directly.' }])).state, 'completed');
  assert.deepEqual(f.contexts[0]!.effectiveTools, []);
  assert.ok(!JSON.stringify(f.requests[0]!.body).includes('A completed timer is not evidence'));
  assert.equal(f.executions.length, 0);
});

test('a sleeping task does not block a different task from completing', async () => {
  const first = fixture('openai-responses', [{ call: { name: 'agent_sleep', args: '{"durationMs":60000}' } }]); ready(first);
  let entered!: () => void; const started = new Promise<void>(resolve => { entered = resolve; }); timer(first, entered);
  const runtime = new ThorRuntime(first.ports, registry()); const held = runtime.run(first.lease, [{ type: 'text', text: 'Wait.' }]);
  await started;
  const other = fixture(); ready(other); other.snapshot.session.sessionId = 'independent-session'; other.lease.sessionId = 'independent-session';
  assert.equal((await new ThorRuntime(other.ports, registry()).run(other.lease, [{ type: 'text', text: 'Independent reply.' }])).state, 'completed');
  runtime.stop(); assert.equal((await held).state, 'cancelled');
});
