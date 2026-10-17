import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, RuntimeSession } from '../src/index.js';
import { fixture, registry } from './fixtures.js';

function l0(body: unknown): any {
  const strings = (value: unknown): string[] => typeof value === 'string' ? [value] : value && typeof value === 'object' ? Object.values(value).flatMap(strings) : [];
  const content = strings(body).map(text => text.match(/<L0>(.*?)<\/L0>/s)?.[1]).find(Boolean);
  assert.ok(content, 'exactly one current L0 must be sent');
  assert.equal(new TextEncoder().encode(content).byteLength <= 1024, true);
  return JSON.parse(content);
}
const app = (packageName: string) => ({ packageName, label: '上屏游戏', versionName: '1.4.2', versionCode: '9223372036854775806', metadataStatus: 'available' as const });

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  test(`${protocol}: automatic upper metadata refreshes each request and follows the real upper app while lower is selected`, async () => {
    const f = fixture(protocol, [{ call: { name: 'echo' } }, { text: 'ok' }]);
    f.snapshot.l0.targetScreen = 'lower';
    f.snapshot.l0.upperApp = app('upper.initial');
    f.snapshot.l0.upperAppObservation = { status: 'observed', displayId: 0 };
    f.setTool(async () => {
      f.snapshot.l0.upperApp = app('upper.changed');
      return { content: [{ type: 'text', text: 'observed' }], source: 'fixture', observedAt: Date.now(), truncated: false, outcome: 'observed' };
    });
    assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: '上屏是哪个程序' }])).state, 'completed');
    const first = l0(f.requests[0]!.body); const second = l0(f.requests[1]!.body);
    assert.deepEqual(first.upperApp, app('upper.initial'));
    assert.equal(first.targetScreen, 'lower');
    assert.deepEqual(first.upperAppObservation, { status: 'observed', displayId: 0 });
    assert.deepEqual(second.upperApp, app('upper.changed'));
    assert.ok(!JSON.stringify(f.requests[1]!.body).includes('upper.initial'));
  });
}

test('a reused Pi session gets fresh app metadata on follow-up and does not carry a previous app into unknown observations', async () => {
  const session = new RuntimeSession('session-test');
  const first = fixture(); first.snapshot.l0.upperApp = app('upper.old');
  assert.equal((await new ThorRuntime(first.ports, registry(), {}, session).run(first.lease, [{ type: 'text', text: 'first' }])).state, 'completed');
  const previousAgent = session.agent;
  const next = fixture(); next.snapshot.l0.upperApp = app('upper.stale.must.not.leak');
  next.snapshot.l0.upperAppObservation = { status: 'ambiguous', displayId: 0 };
  assert.equal((await new ThorRuntime(next.ports, registry(), {}, session).run(next.lease, [{ type: 'text', text: 'follow up' }], [first.turns.at(-1)!])).state, 'completed');
  assert.equal(session.agent, previousAgent);
  assert.equal(l0(next.requests[0]!.body).upperApp, undefined);
  assert.deepEqual(l0(next.requests[0]!.body).upperAppObservation, { status: 'ambiguous', displayId: 0 });
  assert.ok(!JSON.stringify(next.requests).includes('upper.old'));
  assert.ok(!JSON.stringify(next.requests).includes('upper.stale.must.not.leak'));
});

for (const mode of ['disabled', 'configuration-probe'] as const) {
  test(`${mode}: Native metadata is not sent to the model and has no screen source`, async () => {
    const f = fixture();
    f.snapshot.l0.upperApp = app('private.upper'); f.snapshot.l0.upperAppObservation = { status: 'observed', displayId: 0 };
    f.snapshot.l0.targetScreen = 'lower';
    if (mode === 'disabled') f.snapshot.session.enabled.screen_perception = false;
    else f.snapshot.session.model.configurationProbe = true;
    assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'hello' }])).state, 'completed');
    const metadata = l0(f.requests[0]!.body);
    for (const field of ['upperApp', 'upperAppObservation', 'targetScreen', 'device']) assert.equal(metadata[field], undefined);
    assert.ok(!JSON.stringify(f.requests).includes('private.upper'));
    assert.ok(f.turns.every(turn => !turn.source.includes('screen_perception')));
  });
}

test('an observation-only L0 taints derived output for future perception revocation without any tool use', async () => {
  const first = fixture('openai-responses', [{ text: 'PRIVATE_DERIVED_OBSERVATION' }]);
  delete first.snapshot.l0.device; delete first.snapshot.l0.upperApp;
  first.snapshot.l0.upperAppObservation = { status: 'unknown', displayId: 0 };
  assert.equal((await new ThorRuntime(first.ports, registry()).run(first.lease, [{ type: 'text', text: 'USER_AUTHORED_REQUEST' }])).state, 'completed');
  const turn = first.turns.at(-1)!;
  assert.ok(turn.source.includes('screen_perception'));
  const next = fixture(); next.snapshot.session.enabled.screen_perception = false;
  assert.equal((await new ThorRuntime(next.ports, registry()).run(next.lease, [{ type: 'text', text: 'next' }], [turn])).state, 'completed');
  const body = JSON.stringify(next.requests);
  assert.ok(body.includes('USER_AUTHORED_REQUEST')); assert.ok(!body.includes('PRIVATE_DERIVED_OBSERVATION'));
});

test('long Unicode labels, versions and unavailable inventories fit L0 without losing package identity or capability facts', async () => {
  const f = fixture();
  f.snapshot.l0.upperApp = { ...app('upper.bounded'), label: '游戏😀'.repeat(2000), versionName: '版本😀'.repeat(2000), versionCode: '9'.repeat(2000) };
  f.snapshot.l0.upperAppObservation = { status: 'observed', displayId: 0 };
  f.snapshot.l0.device = '掌机😀'.repeat(2000);
  for (let i = 0; i < 100; i++) f.snapshot.availability[`extra_long_backend_${i}`] = { status: 'backend_missing' };
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'hello' }])).state, 'completed');
  const metadata = l0(f.requests[0]!.body);
  assert.equal(metadata.upperApp.packageName, 'upper.bounded');
  assert.equal(metadata.upperApp.metadataStatus, 'available');
  assert.equal(metadata.upperApp.versionCode, undefined, 'never fabricate a partial numeric version');
  assert.deepEqual(metadata.enabled, f.snapshot.session.enabled);
  assert.ok(metadata.unavailableOmitted > 0);
  assert.ok(!JSON.stringify(metadata).includes('�'));
});

test('automatic app metadata never enables screen control or grants an executable input target', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'tap' } }, { text: 'cannot tap' }]);
  f.snapshot.l0.upperApp = app('upper.metadata.only');
  f.snapshot.l0.upperAppObservation = { status: 'observed', displayId: 0 };
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'tap' }])).state, 'completed');
  assert.deepEqual(f.executions, []);
  assert.ok(f.contexts.every(context => !context.effectiveTools.includes('test.tap')));
  assert.equal(l0(f.requests[0]!.body).enabled.screen_control, false);
});
