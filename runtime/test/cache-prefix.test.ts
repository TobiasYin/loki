import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, RuntimeSession } from '../src/index.js';
import { fixture, registry } from './fixtures.js';
import { nativePiState, piAssistant } from './pi-test-state.js';

const strings = (value: unknown): string[] => typeof value === 'string' ? [value] : value && typeof value === 'object' ? Object.values(value).flatMap(strings) : [];
const contexts = (body: unknown) => strings(body).flatMap(text => text.match(/<runtime-context[\s\S]*?<\/runtime-context>/g)??[]);

test('new frame observations append after the existing wire prefix instead of rewriting the system prompt', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'done' }]);
  const logical=new RuntimeSession('session-test');
  f.setTool(async () => {
    f.snapshot.l0.snapshotId = 'frame-new';
    f.snapshot.l0.upperApp = { packageName: 'new.observed.app' };
    return { content: [{ type: 'text', text: 'observed' }], source: 'fixture', observedAt: 1, truncated: false };
  });
  assert.equal((await new ThorRuntime(f.ports, registry(),{},logical).run(f.lease, [{ type: 'text', text: 'inspect' }])).state, 'completed');
  const before = f.requests[0]!.body, after = f.requests[1]!.body;
  assert.deepEqual(after.input.slice(0, before.input.length), before.input);
  assert.equal(contexts(before).length, 1); assert.equal(contexts(after).length, 2);
  assert.ok(contexts(after).at(-1)!.includes('new.observed.app'));
  assert.ok(!after.input[0].content.includes('<L0>'));
  assert.equal(logical.piSnapshot!.entries.filter(e=>e.type==='custom_message' && e.customType==='thor-runtime-context').length,2);
});

test('unchanged runtime metadata is injected once per run', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'done' }]);
  const logical=new RuntimeSession('session-test');
  await new ThorRuntime(f.ports, registry(),{},logical).run(f.lease, [{ type: 'text', text: 'inspect' }]);
  assert.equal(contexts(f.requests[1]!.body).length, 1);
  assert.equal(logical.piSnapshot!.entries.filter(e=>e.type==='custom_message' && e.customType==='thor-runtime-context').length,1);
});

test('a newly reconstructed Pi session replays observation positions and the exact previous wire prefix', async () => {
  const first = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'first completed' }]);
  const logical=new RuntimeSession('session-test');
  await new ThorRuntime(first.ports, registry(),{},logical).run(first.lease, [{ type: 'text', text: 'first question' }]);
  const next = fixture('openai-responses', [{ text: 'continued' }]); next.snapshot.l0.snapshotId = 'follow-up-frame';
  await new ThorRuntime(next.ports, registry(), {}, new RuntimeSession('session-test')).run(next.lease, [{ type: 'text', text: 'continue' }],structuredClone(logical.piSnapshot));
  const before = first.requests.at(-1)!.body.input;
  assert.deepEqual(next.requests[0]!.body.input.slice(0, before.length), before);
  assert.equal(contexts(next.requests[0]!.body).length, 2);
  assert.ok(contexts(next.requests[0]!.body).at(-1)!.includes('follow-up-frame'));
});

test('revocation removes persisted runtime observations while preserving real user messages', async () => {
  const first = fixture('openai-responses', [{ text: 'derived observation' }]); const logical=new RuntimeSession(first.lease.sessionId);first.snapshot.l0.upperApp = { packageName: 'private.previous.app' };
  await new ThorRuntime(first.ports, registry(),{},logical).run(first.lease, [{ type: 'text', text: 'real user request' }]);
  const next = fixture('openai-responses', [{ text: 'continued' }]); next.snapshot.session.enabled.screen_perception = false;
  await new ThorRuntime(next.ports, registry()).run(next.lease, [{ type: 'text', text: 'continue' }],structuredClone(logical.piSnapshot));
  const body = JSON.stringify(next.requests[0]!.body);
  assert.ok(body.includes('real user request')); assert.ok(!body.includes('private.previous.app')); assert.ok(!body.includes('derived observation'));
});

test('native Pi tool evidence stays at its original position without repeating when screen observations or user turns are appended', async () => {
  const f=fixture('openai-responses',[{call:{name:'echo'}},{text:'done'}]);const logical=new RuntimeSession('session-test');
  f.setTool(async()=>{
    return {content:[{type:'text',text:'CONFIRMED_EVIDENCE_MARKER'}],source:'fixture',observedAt:1,truncated:false};
  });
  const runtime=new ThorRuntime(f.ports,registry(),{},logical);
  runtime.subscribe(event=>{if(event.type==='tool_result')f.snapshot.l0.snapshotId='new-frame-with-same-evidence';});
  assert.equal((await runtime.run(f.lease,[{type:'text',text:'new question'}])).state,'completed');
  assert.equal(JSON.stringify(f.requests[0]!.body).includes('CONFIRMED_EVIDENCE_MARKER'),false);
  assert.equal(JSON.stringify(f.requests[1]!.body).split('CONFIRMED_EVIDENCE_MARKER').length-1,1);
  assert.ok(!JSON.stringify(f.requests[1]!.body.input[0]).includes('CONFIRMED_EVIDENCE_MARKER'));
  assert.deepEqual(f.requests[1]!.body.input.slice(0,f.requests[0]!.body.input.length),f.requests[0]!.body.input);
  assert.equal(contexts(f.requests[1]!.body).length,2);
  const original=structuredClone(logical.piSnapshot);
  const next=fixture('openai-responses',[{text:'continued'}]);
  await new ThorRuntime(next.ports,registry()).run(next.lease,[{type:'text',text:'continue'}],structuredClone(original));
  assert.equal(JSON.stringify(next.requests[0]!.body).split('CONFIRMED_EVIDENCE_MARKER').length-1,1);
  assert.deepEqual(logical.piSnapshot,original,'restoration cannot mutate persisted native entries');
});

test('revocation withholds native tool evidence and later derived answers while preserving genuine user messages', async () => {
  const seed=fixture();
  const old=nativePiState(seed,[{role:'user',content:'original user remains',timestamp:1},
    piAssistant(seed,'',[{type:'toolCall',id:'old-command',name:'shell',arguments:{text:'read'}}]),
    {role:'toolResult',toolCallId:'old-command',toolName:'shell',content:[{type:'text',text:'SYNTHETIC_ADB_EVIDENCE'}],isError:false,timestamp:1},
    piAssistant(seed,'old debug-derived answer')],['adb_debug']);
  const middle=fixture('openai-responses',[{text:'middle derived answer'}]);middle.snapshot.session.enabled.adb_debug=true;
  const logical=new RuntimeSession('session-test');
  await new ThorRuntime(middle.ports,registry(),{},logical).run(middle.lease,[{type:'text',text:'middle user remains'}],old);
  const saved=middle.turns.at(-1)!;
  assert.ok(saved.source.includes('adb_debug'),'indirectly read source is recorded even without a new debug call');
  assert.ok(JSON.stringify(logical.piSnapshot).includes('SYNTHETIC_ADB_EVIDENCE'));
  const next=fixture('openai-responses',[{text:'answer after revocation'}]);next.snapshot.session.enabled.adb_debug=false;
  await new ThorRuntime(next.ports,registry()).run(next.lease,[{type:'text',text:'third user'}],structuredClone(logical.piSnapshot));
  const wire=JSON.stringify(next.requests[0]!.body);
  assert.ok(!wire.includes('SYNTHETIC_ADB_EVIDENCE'));assert.ok(!wire.includes('middle derived answer'));
  assert.ok(wire.includes('original user remains'));assert.ok(wire.includes('middle user remains'));
});
