import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compileContext, ThorRuntime, RuntimeSession, type HistoryTurn } from '../src/index.js';
import { fixture, registry } from './fixtures.js';

const strings = (value: unknown): string[] => typeof value === 'string' ? [value] : value && typeof value === 'object' ? Object.values(value).flatMap(strings) : [];
const contexts = (body: unknown) => strings(body).filter(text => text.startsWith('<runtime-context'));

test('new frame observations append after the existing wire prefix instead of rewriting the system prompt', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'done' }]);
  f.setTool(async () => {
    f.snapshot.l0.snapshotId = 'frame-new';
    f.snapshot.l0.upperApp = { packageName: 'new.observed.app' };
    return { content: [{ type: 'text', text: 'observed' }], source: 'fixture', observedAt: 1, truncated: false };
  });
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'inspect' }])).state, 'completed');
  const before = f.requests[0]!.body, after = f.requests[1]!.body;
  assert.deepEqual(after.input.slice(0, before.input.length), before.input);
  assert.equal(contexts(before).length, 1); assert.equal(contexts(after).length, 2);
  assert.ok(contexts(after).at(-1)!.includes('new.observed.app'));
  assert.ok(!after.input[0].content.includes('<L0>'));
  assert.equal(f.turns.at(-1)!.contextUpdates?.length, 2);
});

test('unchanged runtime metadata is injected once per run', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'done' }]);
  await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'inspect' }]);
  assert.equal(contexts(f.requests[1]!.body).length, 1);
  assert.equal(f.turns.at(-1)!.contextUpdates?.length, 1);
});

test('a newly reconstructed Pi session replays observation positions and the exact previous wire prefix', async () => {
  const first = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'first completed' }]);
  await new ThorRuntime(first.ports, registry()).run(first.lease, [{ type: 'text', text: 'first question' }]);
  const next = fixture('openai-responses', [{ text: 'continued' }]); next.snapshot.l0.snapshotId = 'follow-up-frame';
  await new ThorRuntime(next.ports, registry(), {}, new RuntimeSession('session-test')).run(next.lease, [{ type: 'text', text: 'continue' }], [first.turns.at(-1)!]);
  const before = first.requests.at(-1)!.body.input;
  assert.deepEqual(next.requests[0]!.body.input.slice(0, before.length), before);
  assert.equal(contexts(next.requests[0]!.body).length, 2);
  assert.ok(contexts(next.requests[0]!.body).at(-1)!.includes('follow-up-frame'));
});

test('revocation removes persisted runtime observations while preserving real user messages', async () => {
  const first = fixture('openai-responses', [{ text: 'derived observation' }]); first.snapshot.l0.upperApp = { packageName: 'private.previous.app' };
  await new ThorRuntime(first.ports, registry()).run(first.lease, [{ type: 'text', text: 'real user request' }]);
  const next = fixture('openai-responses', [{ text: 'continued' }]); next.snapshot.session.enabled.screen_perception = false;
  await new ThorRuntime(next.ports, registry()).run(next.lease, [{ type: 'text', text: 'continue' }], [first.turns.at(-1)!]);
  const body = JSON.stringify(next.requests[0]!.body);
  assert.ok(body.includes('real user request')); assert.ok(!body.includes('private.previous.app')); assert.ok(!body.includes('derived observation'));
});

test('historical effect evidence stays in a suffix and unchanged ledgers are injected only once per run', async () => {
  const f = fixture(); const p = f.snapshot.session.model;
  const history: HistoryTurn[] = [{ turnId: 'old', runId: 'old', source: [], observedAt: 1, complete: true,
    messages: [{ kind: 'user', content: [{ type: 'text', text: 'old request' }] }],
    effects: [{ toolCallId: 'old-effect', toolId: 'files.write', effectClass: 'write', outcome: 'observed', evidenceRef: 'ledger-only-marker' }] }];
  const options = { snapshot: f.snapshot, tools: [], registryVersion: '1', model: {
    id: p.modelId, name: 'test', api: p.protocol, provider: 'openai', baseUrl: p.baseUrl, reasoning: false,
    input: ['text'] as ('text' | 'image')[], contextWindow: 32_000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  }, history, current: [{ role: 'user' as const, content: 'new question', timestamp: 1 }], attachments: f.ports.attachments, imageTokens: 100, maxHistoryTurns: 50 };
  const compiled = await compileContext(options);
  assert.ok(!JSON.stringify(compiled.messages[0]).includes('ledger-only-marker'));
  assert.ok(compiled.contextUpdates[1]!.text.includes('ledger-only-marker'));
  assert.ok(compiled.contextUpdates[0]!.text.includes('<L0>'));
  const again = await compileContext({ ...options, currentContextUpdates: compiled.contextUpdates });
  assert.equal(again.contextUpdates.length, 2);
  assert.equal(JSON.stringify(again.messages).split('ledger-only-marker').length - 1, 1);
  f.snapshot.l0.snapshotId='new-frame-with-same-ledger';
  const changed=await compileContext({...options,currentContextUpdates:again.contextUpdates});
  assert.equal(changed.contextUpdates.length,3);
  assert.equal(JSON.stringify(changed.messages).split('ledger-only-marker').length-1,1,'frame updates never repeat the full ledger');
});

test('revocation removes indirect history ledger copies saved by a later answer-only run', async () => {
  const old: HistoryTurn = {turnId:'old-debug',runId:'old-debug',source:['adb_debug'],complete:true,observedAt:1,
    messages:[{kind:'user',content:[{type:'text',text:'original user remains'}]},{kind:'assistant',text:'old debug-derived answer',calls:[]}],
    effects:[{toolCallId:'old-command',toolId:'debug.shell',effectClass:'read',outcome:'observed',evidenceRef:'SYNTHETIC_ADB_EVIDENCE'}]};
  const middle=fixture('openai-responses',[{text:'middle derived answer'}]);middle.snapshot.session.enabled.adb_debug=true;
  await new ThorRuntime(middle.ports,registry()).run(middle.lease,[{type:'text',text:'middle user remains'}],[old]);
  const saved=middle.turns.at(-1)!;
  assert.ok(saved.source.includes('adb_debug'),'indirectly read source is recorded even without a new debug call');
  assert.ok(JSON.stringify(saved.contextUpdates).includes('SYNTHETIC_ADB_EVIDENCE'));
  const next=fixture('openai-responses',[{text:'answer after revocation'}]);next.snapshot.session.enabled.adb_debug=false;
  await new ThorRuntime(next.ports,registry()).run(next.lease,[{type:'text',text:'third user'}],[old,saved]);
  const wire=JSON.stringify(next.requests[0]!.body);
  assert.ok(!wire.includes('SYNTHETIC_ADB_EVIDENCE'));assert.ok(!wire.includes('middle derived answer'));
  assert.ok(wire.includes('original user remains'));assert.ok(wire.includes('middle user remains'));
});
