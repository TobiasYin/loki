import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, RuntimeSession, ToolRegistry, type ToolDescriptor } from '../src/index.js';
import { fixture, descriptor, PNG } from './fixtures.js';

function tools(name = 'activate', effectClass: ToolDescriptor['effectClass'] = 'input', capability: 'screen_control' | 'screen_perception' = 'screen_control', toolId = `input.${name}`) {
  const registry = new ToolRegistry();
  registry.register({ packId: 'native-receipts', packVersion: '1', tools: [{ ...descriptor(name, [capability]), toolId,
    effectClass, platformPrerequisites: ['target-backend'] }] });
  return registry;
}
function setup(protocol: Parameters<typeof fixture>[0] = 'openai-responses', name = 'activate') {
  const f = fixture(protocol, [{ call: { name, id: 'native-action-once' } }, { text: 'Read the new screen before the next action.' }]);
  f.snapshot.session.enabled.screen_control = true;
  f.snapshot.availability['target-backend'] = { status: 'ready' };
  return f;
}
const receipt = (text = 'NATIVE_SUCCESS_RECEIPT', outcome: 'submitted' | 'observed' = 'submitted') => ({
  content: [{ type: 'text' as const, text }], observedAt: Date.now(), source: 'native-fixture', truncated: false,
  outcome, evidenceRef: 'native-event:confirmed-once',
});
function noPrivateContent(f: ReturnType<typeof fixture>, marker: string) {
  assert.ok(!JSON.stringify([f.events, f.requests, f.turns]).includes(marker));
}

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  test(`${protocol}: navigation receipts survive changed target/backend and same-Pi follow-ups never replay actions`, async () => {
    for (const name of ['launch', 'tap', 'activate']) {
      const f = setup(protocol, name); const session = new RuntimeSession(f.lease.sessionId);
      const toolId = name === 'launch' ? 'apps.launch' : `input.${name}`; const registry = tools(name, 'input', 'screen_control', toolId);
      let effects = 0;
      f.setTool(async () => {
        effects++;
        f.snapshot.l0.snapshotId = 'new-window-observation';
        f.snapshot.l0.upperApp = { packageName: 'new.app', label: 'Navigated app' };
        f.snapshot.availability['target-backend'] = { status: 'temporarily_unavailable' };
        return receipt();
      });
      const result = await new ThorRuntime(f.ports, registry, {}, session).run(f.lease, [{ type: 'text', text: 'Navigate once, then verify the resulting screen.' }]);
      assert.equal(result.state, 'completed'); assert.equal(f.requests.length, 2); assert.equal(effects, 1); assert.equal(f.executions.length, 1);
      const event = f.events.find(e => e.type === 'tool_result')!;
      assert.equal(event.data.error, undefined); assert.equal(event.data.outcome, 'submitted'); assert.equal(event.data.evidenceRef, 'native-event:confirmed-once');
      assert.ok(JSON.stringify(event.data.content).includes('NATIVE_SUCCESS_RECEIPT'));
      assert.ok(JSON.stringify(f.requests[1]!.body).includes('NATIVE_SUCCESS_RECEIPT'));
      assert.equal(f.contexts[1]!.l0SnapshotId, 'new-window-observation');
      const turn = f.turns.at(-1)!;
      // Responses normalizes call_id plus item_id into Pi's composite ID.
      assert.equal(event.toolCallId, f.executions[0]!.toolCallId);
      assert.deepEqual(turn.effects, [{ toolCallId: f.executions[0]!.toolCallId, toolId, effectClass: 'input', outcome: 'submitted', evidenceRef: 'native-event:confirmed-once' }]);
      const agent = session.agent; const next = fixture(protocol, [{ text: 'Continue from the confirmed progress.' }]);
      next.snapshot.session.enabled.screen_control = true;
      assert.equal((await new ThorRuntime(next.ports, registry, {}, session).run(next.lease, [{ type: 'text', text: 'Continue without repeating the confirmed action.' }], [turn])).state, 'completed');
      assert.equal(session.agent, agent); assert.equal(next.executions.length, 0); assert.equal(effects, 1);
      assert.ok(JSON.stringify(next.requests[0]!.body).includes('NATIVE_SUCCESS_RECEIPT'));
      assert.ok(next.events.some(e => e.type === 'context' && e.data.piSessionReused === true));
    }
  });
}

for (const changed of ['capability', 'epoch', 'session', 'run', 'lease', 'expired', 'stop'] as const) {
  test(`post-return ${changed} rejects private action content while retaining submitted evidence`, async () => {
    const f = setup(); const runtime = new ThorRuntime(f.ports, tools()); let returned = false; let invalidated = false;
    const read = f.ports.authority.read;
    f.ports.authority.read = async (...args) => {
      if (returned && !invalidated) {
        invalidated = true;
        if (changed === 'capability') f.snapshot.session.enabled.screen_control = false;
        if (changed === 'epoch') { f.snapshot.session.capabilityEpoch++; f.snapshot.lease.capabilityEpoch++; runtime.authorizationChanged(f.lease.sessionId, f.snapshot.session.capabilityEpoch); }
        if (changed === 'session') f.snapshot.session.sessionId = 'other-session';
        if (changed === 'run') f.snapshot.lease.runId = 'other-run';
        if (changed === 'lease') f.snapshot.lease.leaseId = 'other-lease';
        if (changed === 'expired') f.snapshot.lease.expiresAt = 0;
        if (changed === 'stop') runtime.stop();
      }
      return read(...args);
    };
    f.setTool(async () => { returned = true; return receipt('PRIVATE_ACTION_BODY'); });
    const result = await runtime.run(f.lease, [{ type: 'text', text: 'Perform one authorized action.' }]);
    assert.equal(f.executions.length, 1); assert.ok(invalidated);
    const event = f.events.find(e => e.type === 'tool_result')!;
    assert.equal(event.data.error, changed === 'capability' ? 'CAPABILITY_DISABLED' : changed === 'stop' ? 'CANCELLED' : 'STALE_AUTHORIZATION');
    assert.equal(event.data.outcome, 'submitted'); assert.equal(event.data.evidenceRef, 'native-event:confirmed-once'); assert.equal(event.data.contentDiscarded, true);
    assert.equal(f.turns.at(-1)!.effects?.[0]?.outcome, 'submitted'); assert.equal(f.turns.at(-1)!.effects?.[0]?.evidenceRef, 'native-event:confirmed-once');
    noPrivateContent(f, 'PRIVATE_ACTION_BODY');
    if (changed !== 'capability') assert.equal(f.requests.length, 1);
    if (changed === 'stop' || changed === 'epoch') assert.equal(result.state, 'cancelled');
  });
}

for (const changed of ['epoch', 'stop'] as const) {
  test(`${changed} during asynchronous attachment unpacking withholds the entire body and preserves the receipt`, async () => {
    const f = setup(); const runtime = new ThorRuntime(f.ports, tools());
    f.attachments.get('tool-image')!.source.push('screen_control');
    let reached!: () => void; let release!: () => void;
    const unpacking = new Promise<void>(resolve => { reached = resolve; }); const resume = new Promise<void>(resolve => { release = resolve; });
    const get = f.ports.attachments.get; let held = false;
    f.ports.attachments.get = async id => {
      if (id === 'tool-image' && !held) { held = true; reached(); await resume; }
      return get(id);
    };
    f.setTool(async () => ({ ...receipt('PRIVATE_UNPACKED_BODY'), content: [{ type: 'text', text: 'PRIVATE_UNPACKED_BODY' }, { type: 'attachment', attachmentId: 'tool-image' }] }));
    const running = runtime.run(f.lease, [{ type: 'text', text: 'Perform one action with a private attachment receipt.' }]);
    await unpacking;
    if (changed === 'stop') runtime.stop();
    else { f.snapshot.session.capabilityEpoch++; f.snapshot.lease.capabilityEpoch++; }
    release(); await running;
    const event = f.events.find(e => e.type === 'tool_result')!;
    assert.equal(event.data.error, changed === 'stop' ? 'CANCELLED' : 'STALE_AUTHORIZATION');
    assert.equal(event.data.outcome, 'submitted'); assert.equal(event.data.evidenceRef, 'native-event:confirmed-once');
    assert.equal(f.turns.at(-1)!.effects?.[0]?.outcome, 'submitted'); assert.equal(f.requests.length, 1);
    noPrivateContent(f, 'PRIVATE_UNPACKED_BODY'); noPrivateContent(f, PNG);
  });
}

test('pure screen reads still reject content after the request observation changes', async () => {
  const f = setup('openai-responses', 'picture');
  f.setTool(async () => { f.snapshot.l0.snapshotId = 'unrelated-new-target'; return receipt('PRIVATE_OLD_SCREEN', 'observed'); });
  await new ThorRuntime(f.ports, tools('picture', 'read', 'screen_perception')).run(f.lease, [{ type: 'text', text: 'Read the current screen.' }]);
  const event = f.events.find(e => e.type === 'tool_result')!;
  assert.equal(event.data.error, 'TARGET_CHANGED'); assert.equal(event.data.contentDiscarded, true); noPrivateContent(f, 'PRIVATE_OLD_SCREEN');
});

test('fresh select_screen read preserves Native completion identity without requiring its old availability', async () => {
  const f = setup('openai-responses', 'select_screen'); let selected = false; const read = f.ports.authority.read;
  f.ports.authority.read = async (...args) => {
    // ProductHost publishes pendingPerceptionSnapshot on refresh for the next
    // model round; refresh=false retains this request's original source.
    if (selected && args[2]) f.snapshot.l0.snapshotId = 'native-completion-lower';
    return read(...args);
  };
  f.setTool(async () => {
    selected = true; f.snapshot.l0.targetScreen = 'lower'; f.snapshot.availability['target-backend'] = { status: 'temporarily_unavailable' };
    return receipt(JSON.stringify({ targetSnapshotId: 'native-completion-lower', displayRole: 'lower', readOnlySelection: true }), 'observed');
  });
  assert.equal((await new ThorRuntime(f.ports, tools('select_screen', 'read', 'screen_perception', 'perception.select_screen')).run(f.lease, [{ type: 'text', text: 'Select the lower screen, then observe it.' }])).state, 'completed');
  const event = f.events.find(e => e.type === 'tool_result')!;
  assert.equal(event.data.error, undefined); assert.ok(JSON.stringify(event.data.content).includes('native-completion-lower'));
  assert.equal(f.contexts[1]!.l0SnapshotId, 'native-completion-lower'); assert.equal(f.executions.length, 1);
});

test('backend preconditions are still rechecked after durable intent before Native execution', async () => {
  const f = setup(); const runtime = new ThorRuntime(f.ports, tools());
  runtime.subscribe(event => { if (event.type === 'tool_intent') f.snapshot.availability['target-backend'] = { status: 'temporarily_unavailable' }; });
  await runtime.run(f.lease, [{ type: 'text', text: 'Do not execute after backend preconditions change.' }]);
  assert.equal(f.executions.length, 0);
});
