import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, RuntimeError, RuntimeSession, ToolRegistry, type ErrorCode, type ToolDescriptor } from '../src/index.js';
import { descriptor, fixture, PNG, type Reply } from './fixtures.js';

function tool(name: string, id: string, effect: ToolDescriptor['effectClass'] = 'read', resources = ['selected-screen/snapshot']): ToolDescriptor {
  return { ...descriptor(name, [effect === 'input' ? 'screen_control' : effect === 'arbitrary-shell' ? 'adb_debug' : 'screen_perception']),
    toolId: id, effectClass: effect, resources };
}
function registry() {
  const r = new ToolRegistry();
  r.register({ packId: 'recovery-fixture', packVersion: '1', tools: [
    tool('tap', 'input.tap', 'input'), tool('launch', 'apps.launch', 'input'),
    tool('button', 'input.press_button', 'input'), tool('axes', 'input.set_axes', 'input'), tool('back', 'input.navigate', 'input'), tool('keyboard', 'input.key_press', 'input'),
    tool('capture', 'perception.capture_screen'), tool('tree', 'perception.ui_tree'),
    tool('app', 'perception.app_info'), tool('window', 'perception.window_info'),
    tool('select', 'perception.select_screen', 'read', ['this-run/target-snapshot']),
    tool('companion', 'perception.capture_companion', 'read', ['calibrated-lower/same-app-read-only']),
    tool('brightness', 'debug.thor_brightness', 'arbitrary-shell', []),
    tool('release', 'input.release_inputs', 'input', []),
    tool('background', 'agent.background', 'input', ['assistant-chat-task']),
  ] });
  return r;
}
const call = (name: string, id: string, text = id): Reply => ({ call: { name, id, args: JSON.stringify({ text }) } });
const observed = (text = 'Fresh current screen') => ({ content: [{ type: 'text' as const, text }], source: 'synthetic-native', observedAt: Date.now(), truncated: false, outcome: 'observed' as const });
const submitted = () => ({ ...observed('NEW_ACTION_SUBMITTED'), outcome: 'submitted' as const });
function ready(f: ReturnType<typeof fixture>) { f.snapshot.session.enabled.screen_control = true; f.snapshot.session.enabled.adb_debug = true; }
function recoveryEvents(f: ReturnType<typeof fixture>) { return f.events.filter(e => e.type === 'tool_result' && e.data.error === 'TARGET_CHANGED'); }

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  for (const [name, toolId, outcome] of [['button', 'input.press_button', 'observed'], ['axes', 'input.set_axes', 'submitted'], ['back', 'input.navigate', 'unknown'], ['keyboard', 'input.key_press', 'observed']] as const) {
    test(`${protocol} ${name}: checked Native focus refusal gives a fixed recovery hint, same Pi/Run observes, launches legally and decides a new input`, async () => {
      const f = fixture(protocol, [call(name, 'focus-refused'), call('window', 'current-focus'), call('launch', 'legal-launch'), call('capture', 'fresh-after-launch'), call(name, 'new-input'), { text: '已重新观察并继续。' }]); ready(f);
      const session = new RuntimeSession(f.lease.sessionId), agents: unknown[] = [];
      f.setOnRequest(() => { agents.push(session.agent); });
      let effects = 0;
      f.setTool(async e => {
        const text = (e.arguments as any).text;
        if (text === 'focus-refused') return { ...observed('PRIVATE_OLD_FOCUS_BODY'), error: 'TARGET_CHANGED', outcome, targetChangedReason: 'input_focus_mismatch' };
        if (e.toolId === 'perception.capture_screen') return { ...observed('Fresh screenshot after legitimate launch'), content: [{ type: 'attachment', attachmentId: 'tool-image' }] };
        if (e.toolId === 'apps.launch' || e.toolId === toolId) { effects++; return submitted(); }
        return observed('Fresh current-window focus observation');
      });
      const result = await new ThorRuntime(f.ports, registry(), {}, session).run(f.lease, [{ type: 'text', text: '观察当前目标，必要时通过合法操作恢复输入焦点再继续，不重放旧动作。' }]);
      assert.equal(result.state, 'completed'); assert.ok(agents.every(a => a === session.agent));
      assert.ok(f.requests.every(r => r.envelope.runId === f.lease.runId));
      assert.ok(f.runs.every(r => !['failed', 'cancelled', 'interrupted'].includes(r.state)));
      assert.deepEqual(f.executions.map(e => (e.arguments as any).text), ['focus-refused', 'current-focus', 'legal-launch', 'fresh-after-launch', 'new-input']);
      assert.equal(effects, 2, 'Only the separately chosen launch/new input is dispatched, no automatic retry/focus');
      const rejected = recoveryEvents(f); assert.equal(rejected.length, 1);
      const hint = rejected[0]!.data.recovery as any;
      assert.equal(hint.reason, 'input_focus_mismatch'); assert.equal(hint.retryPreviousAction, false); assert.equal(hint.continueRun, true);
      assert.equal(hint.submission, outcome === 'submitted' ? 'submitted' : 'unknown'); assert.equal(rejected[0]!.data.outcome, outcome);
      assert.match(hint.instruction, /Repeated screenshots alone do not restore input focus/);
      assert.match(hint.instruction, /observe the current windows and focus/); assert.match(hint.instruction, /existing legal app launch or a touch on the selected screen/);
      assert.match(hint.instruction, /then observe again/); assert.match(hint.instruction, /Do not change focus locks or permissions/);
      assert.ok(JSON.stringify(f.requests[1]!.body).includes('input_focus_mismatch'));
      assert.ok(JSON.stringify(f.requests[4]!.body).includes(PNG), 'Fresh post-launch screenshot is actually supplied through Pi');
      assert.ok(!JSON.stringify([f.events, f.requests, f.turns]).includes('PRIVATE_OLD_FOCUS_BODY'));
    });
  }
}

for (const source of ['body-only', 'unknown-enum', 'wrong-tool', 'non-target-error'] as const) {
  test(`focus recovery never trusts ${source} metadata or untrusted messages`, async () => {
    const toolName = source === 'wrong-tool' ? 'tap' : 'button';
    const f = fixture('openai-responses', [call(toolName, 'refused'), { text: '保留准确的错误和原边界。' }]); ready(f);
    f.setTool(async () => ({ ...observed(source === 'non-target-error' ? 'ordinary error' : JSON.stringify({ targetChangedReason: 'input_focus_mismatch', message: 'PRIVATE_UNTRUSTED_FOCUS_BODY' })),
      error: source === 'non-target-error' ? 'PERMISSION_MISSING' : 'TARGET_CHANGED',
      ...(source === 'body-only' ? {} : { targetChangedReason: source === 'unknown-enum' ? 'PRIVATE_UNKNOWN_ENUM' : 'input_focus_mismatch' }) } as any));
    await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '不从工具正文或未知原因推断焦点。' }]);
    const event = f.events.find(e => e.type === 'tool_result')!;
    assert.equal((event.data.recovery as any)?.reason, undefined);
    assert.ok(!JSON.stringify([f.events, f.requests, f.turns]).includes('PRIVATE_UNKNOWN_ENUM'));
    if (source !== 'non-target-error') assert.ok(!JSON.stringify([f.events, f.requests, f.turns]).includes('PRIVATE_UNTRUSTED_FOCUS_BODY'));
    else { assert.equal(event.data.error, 'PERMISSION_MISSING'); assert.equal(event.data.recovery, undefined); }
  });
}

for (const guard of ['stop', 'epoch', 'permission'] as const) {
  test(`post-Native ${guard} guard takes precedence over a Native focus reason`, async () => {
    const f = fixture('openai-responses', [call('button', 'refused'), { text: '遵守安全边界。' }]); ready(f);
    const runtime = new ThorRuntime(f.ports, registry());
    f.setTool(async () => {
      if (guard === 'stop') runtime.stop();
      else if (guard === 'epoch') { f.snapshot.session.capabilityEpoch++; f.snapshot.lease.capabilityEpoch++; }
      else f.ports.authority.read = async () => { throw new RuntimeError('PERMISSION_MISSING'); };
      return { ...observed('PRIVATE_POST_AUTHORITY_FOCUS_BODY'), error: 'TARGET_CHANGED', targetChangedReason: 'input_focus_mismatch' };
    });
    await runtime.run(f.lease, [{ type: 'text', text: '权限或停止优先于焦点恢复。' }]);
    assert.ok(!f.events.some(e => (e.data.recovery as any)?.reason === 'input_focus_mismatch'));
    assert.ok(!JSON.stringify([f.events, f.requests, f.turns]).includes('PRIVATE_POST_AUTHORITY_FOCUS_BODY'));
    assert.equal(f.executions.length, 1);
    assert.ok(f.events.some(e => e.type === 'tool_result' && e.data.error === ({ stop: 'CANCELLED', epoch: 'STALE_AUTHORIZATION', permission: 'PERMISSION_MISSING' }[guard])));
  });
}

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  for (const rejection of ['before-check', 'after-intent', 'after-start', 'native'] as const) {
    test(`${protocol} ${rejection}: same real Pi/Run observes a new screenshot/tree and acts without replaying the old action`, async () => {
      const f = fixture(protocol, [call('tap', 'stale'), call('capture', 'fresh-image'), call('tree', 'fresh-tree'), call('tap', 'new-action'), { text: '已根据新画面完成。' }]); ready(f);
      const session = new RuntimeSession(f.lease.sessionId), runtime = new ThorRuntime(f.ports, registry(), {}, session);
      const agents: unknown[] = []; let committedActions = 0;
      const change = () => { f.snapshot.l0.snapshotId = 'new-page'; f.snapshot.l0.upperApp = { packageName: 'next.app', label: 'New page' }; };
      f.setOnRequest(index => { agents.push(session.agent); if (!index && rejection === 'before-check') change(); });
      runtime.subscribe(e => { if (e.data.toolId === 'input.tap' && e.toolCallId?.startsWith('stale') && e.type === (rejection === 'after-intent' ? 'tool_intent' : rejection === 'after-start' ? 'tool_started' : 'never')) change(); });
      f.setTool(async envelope => {
        const text = (envelope.arguments as { text: string }).text;
        if (text === 'stale') {
          assert.equal(rejection, 'native'); change();
          return { ...observed('PRIVATE_STALE_BODY'), error: 'TARGET_CHANGED', outcome: 'unknown' };
        }
        if (envelope.toolId === 'perception.capture_screen') return { ...observed('Fresh image with frame-new'), content: [{ type: 'text', text: 'frame-new' }, { type: 'attachment', attachmentId: 'tool-image' }] };
        if (envelope.toolId === 'input.tap') { assert.equal(text, 'new-action'); committedActions++; return submitted(); }
        return observed('Current tree node-new');
      });
      const result = await runtime.run(f.lease, [{ type: 'text', text: '如果页面变化，重新观察并完成目标，不重复旧动作。' }]);
      assert.equal(result.state, 'completed'); assert.equal(f.requests.length, 5); assert.equal(committedActions, 1);
      assert.ok(agents.every(a => a === session.agent)); assert.ok(session.agent);
      assert.ok(f.requests.every(r => r.envelope.runId === f.lease.runId));
      assert.ok(f.runs.every(r => !['failed', 'cancelled', 'interrupted'].includes(r.state)));
      assert.deepEqual(f.executions.map(e => (e.arguments as { text: string }).text), rejection === 'native' ? ['stale', 'fresh-image', 'fresh-tree', 'new-action'] : ['fresh-image', 'fresh-tree', 'new-action']);
      const rejected = recoveryEvents(f); assert.equal(rejected.length, 1);
      const hint = rejected[0]!.data.recovery as any;
      assert.equal(hint.kind, 'observe_again'); assert.equal(hint.continueRun, true); assert.equal(hint.retryPreviousAction, false);
      assert.equal(hint.submission, rejection === 'native' ? 'unknown' : 'not_dispatched');
      assert.ok(JSON.stringify(f.requests[1]!.body).includes('observe_again'));
      assert.ok(JSON.stringify(f.requests[2]!.body).includes(PNG), 'The actual Pi provider request must contain the newly read screenshot');
      assert.ok(!JSON.stringify([f.events, f.requests, f.turns]).includes('PRIVATE_STALE_BODY'));
      assert.ok(!f.events.some(e => e.type === 'diagnostic' && e.data.code === 'TOOL_FAILED'));
      assert.ok(f.turns.at(-1)!.complete);
    });
  }
}

test('observation gate follows actual screen resources: selection/companion cannot bypass it; cleanup/background/brightness remain usable', async () => {
  const f = fixture('openai-responses', [call('tap', 'stale'), call('brightness', 'brightness'), call('release', 'release'), call('background', 'background'), call('companion', 'companion'), call('launch', 'blocked-launch'), call('select', 'select-lower'), call('tap', 'blocked-lower'), call('tree', 'fresh-lower-tree'), call('tap', 'new-lower-action'), { text: '完成。' }]); ready(f);
  const runtime = new ThorRuntime(f.ports, registry()); let actions = 0;
  f.setTool(async e => {
    const text = (e.arguments as { text: string }).text;
    if (text === 'stale') return { ...observed(), error: 'TARGET_CHANGED', outcome: 'unknown' };
    if (e.toolId === 'input.tap') { assert.equal(text, 'new-lower-action'); actions++; return submitted(); }
    if (e.toolId === 'perception.select_screen') f.snapshot.l0.targetScreen = 'lower';
    return observed();
  });
  assert.equal((await runtime.run(f.lease, [{ type: 'text', text: '观察目标屏幕后继续。' }])).state, 'completed');
  assert.equal(actions, 1); assert.equal(recoveryEvents(f).length, 3);
  assert.deepEqual(f.executions.map(e => (e.arguments as any).text), ['stale', 'brightness', 'release', 'background', 'companion', 'select-lower', 'fresh-lower-tree', 'new-lower-action']);
});

test('a discarded old-screen read never unlocks input, while a fresh checked window read does', async () => {
  const f = fixture('openai-responses', [call('tap', 'stale'), call('capture', 'discarded-image'), call('tap', 'blocked'), call('window', 'fresh-window'), call('tap', 'new-action'), { text: '完成。' }]); ready(f);
  f.setTool(async e => {
    const text = (e.arguments as any).text;
    if (text === 'stale') return { ...observed(), error: 'TARGET_CHANGED', outcome: 'unknown' };
    if (text === 'discarded-image') { f.snapshot.l0.snapshotId = 'changed-during-read'; return observed('PRIVATE_DISCARDED_SCREEN'); }
    if (e.toolId === 'input.tap') { assert.equal(text, 'new-action'); return submitted(); }
    return observed();
  });
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '页面变化后重新观察再操作。' }])).state, 'completed');
  assert.equal(recoveryEvents(f).length, 3); assert.ok(!f.executions.some(e => (e.arguments as any).text === 'blocked'));
  assert.ok(!JSON.stringify([f.requests, f.events, f.turns]).includes('PRIVATE_DISCARDED_SCREEN'));
});

test('a submitted receipt stays submitted through TARGET_CHANGED recovery, never described as not dispatched', async () => {
  const f = fixture('openai-responses', [call('tap', 'submitted-then-changed'), call('app', 'inspect-result'), { text: '已检查操作后的页面。' }]); ready(f);
  f.setTool(async e => e.toolId === 'input.tap' ? { ...submitted(), error: 'TARGET_CHANGED', evidenceRef: 'submitted-once' } : observed());
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '操作后检查，不要重复。' }])).state, 'completed');
  const result = recoveryEvents(f)[0]!;
  assert.equal(result.data.outcome, 'submitted'); assert.equal((result.data.recovery as any).submission, 'submitted');
  assert.equal(f.turns.at(-1)!.effects?.[0]?.outcome, 'submitted'); assert.equal(f.executions.filter(e => e.toolId === 'input.tap').length, 1);
});

test('a temporarily unavailable screen read does not clear recovery; selecting a legal screen and observing it continues the same Run', async () => {
  const f = fixture('openai-responses', [call('tap', 'stale'), call('capture', 'temporarily-unavailable'), call('tap', 'blocked'), call('select', 'select-legal-screen'), call('app', 'fresh-app'), call('tap', 'new-action'), { text: '完成。' }]); ready(f);
  f.setTool(async e => {
    const text = (e.arguments as any).text;
    if (text === 'stale') return { ...observed(), error: 'TARGET_CHANGED' };
    if (text === 'temporarily-unavailable') return { ...observed(), error: 'BACKEND_UNAVAILABLE' };
    if (e.toolId === 'input.tap') { assert.equal(text, 'new-action'); return submitted(); }
    return observed();
  });
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '读屏暂不可用时选择合法屏幕后继续观察。' }])).state, 'completed');
  assert.ok(!f.executions.some(e => (e.arguments as any).text === 'blocked'));
  const unavailable = f.events.find(e => e.type === 'tool_result' && e.data.error === 'BACKEND_UNAVAILABLE')!;
  assert.ok(unavailable); assert.equal(unavailable.data.recovery, undefined);
});

test('selecting a screen after a successful observation requires another observation before input', async () => {
  const f = fixture('openai-responses', [call('tree', 'fresh-upper'), call('select', 'select-lower'), call('tap', 'blocked-old-ref'), call('capture', 'fresh-lower'), call('tap', 'new-lower-action'), { text: '完成。' }]); ready(f);
  f.setTool(async e => {
    if (e.toolId === 'input.tap') { assert.equal((e.arguments as any).text, 'new-lower-action'); return submitted(); }
    return observed();
  });
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '切换角色后不能复用旧屏引用。' }])).state, 'completed');
  assert.equal(recoveryEvents(f).length, 1); assert.ok(!f.executions.some(e => (e.arguments as any).text === 'blocked-old-ref'));
});

test('a Run awaiting fresh observation does not gate another session or Run', async () => {
  const first = fixture('openai-responses', [call('tap', 'stale'), call('tree', 'held-observation'), call('tap', 'new-action'), { text: '完成第一项。' }]); ready(first);
  const second = fixture('openai-responses', [call('tap', 'independent-action'), { text: '完成另一项。' }]); ready(second);
  second.lease.sessionId = second.snapshot.session.sessionId = 'second-session';
  let entered!: () => void, release!: () => void;
  const reached = new Promise<void>(r => { entered = r; }), held = new Promise<void>(r => { release = r; });
  first.setTool(async e => {
    if ((e.arguments as any).text === 'stale') return { ...observed(), error: 'TARGET_CHANGED' };
    if (e.toolId === 'perception.ui_tree') { entered(); await held; return observed(); }
    return submitted();
  });
  second.setTool(async () => submitted());
  const running = new ThorRuntime(first.ports, registry()).run(first.lease, [{ type: 'text', text: '等待新的观察。' }]);
  await reached;
  try {
    assert.equal((await new ThorRuntime(second.ports, registry()).run(second.lease, [{ type: 'text', text: '独立授权任务。' }])).state, 'completed');
    assert.equal(second.executions.length, 1); assert.equal(recoveryEvents(second).length, 0);
  } finally { release(); }
  assert.equal((await running).state, 'completed');
});

for (const code of ['PERMISSION_MISSING', 'CAPABILITY_DISABLED', 'BACKEND_UNAVAILABLE', 'SECURE_CONTENT', 'COMMAND_BLOCKED', 'TIMEOUT'] as ErrorCode[]) {
  test(`${code} is a genuine error and is never relabelled as page recovery`, async () => {
    const f = fixture('openai-responses', [call('tap', 'denied'), { text: '保留准确错误。' }]); ready(f);
    f.setTool(async () => ({ ...observed(), error: code }));
    await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '不要绕过权限或安全拒绝。' }]);
    const result = f.events.find(e => e.type === 'tool_result')!;
    assert.equal(result.data.error, code); assert.equal(result.data.recovery, undefined); assert.equal(recoveryEvents(f).length, 0);
    assert.ok(f.events.some(e => e.type === 'diagnostic' && e.data.code === 'TOOL_FAILED'));
  });
}

for (const guard of ['stop', 'epoch'] as const) {
  test(`${guard} during page recovery still cancels the Run and never dispatches the next screen read`, async () => {
    const f = fixture('openai-responses', [call('tap', 'stale'), call('capture', 'must-not-run')]); ready(f);
    const runtime = new ThorRuntime(f.ports, registry());
    f.setTool(async () => ({ ...observed(), error: 'TARGET_CHANGED', outcome: 'unknown' }));
    f.setOnRequest(i => { if (i === 1) { if (guard === 'stop') runtime.stop(); else { f.snapshot.session.capabilityEpoch++; f.snapshot.lease.capabilityEpoch++; runtime.authorizationChanged(f.lease.sessionId, f.snapshot.session.capabilityEpoch); } } });
    assert.equal((await runtime.run(f.lease, [{ type: 'text', text: '已停止或撤权后不能继续。' }])).state, 'cancelled');
    assert.equal(f.executions.length, 1); assert.equal(f.turns.at(-1)!.complete, false);
  });
}

test('a persistence failure while recording a preflight rejection remains fatal and prevents all Native work', async () => {
  const f = fixture('openai-responses', [call('tap', 'stale')]); ready(f);
  f.setOnRequest(() => { f.snapshot.l0.snapshotId = 'changed'; });
  const append = f.ports.persistence.appendEvent;
  f.ports.persistence.appendEvent = async e => { if (e.type === 'tool_result') throw Error('store unavailable'); await append(e); };
  await assert.rejects(new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '存储失效时停止。' }]), (e: unknown) => e instanceof RuntimeError && e.code === 'PERSISTENCE_ERROR');
  assert.equal(f.executions.length, 0); assert.equal(f.requests.length, 1);
});
