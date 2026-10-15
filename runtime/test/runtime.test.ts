import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Model } from '@earendil-works/pi-ai';
import { ThorRuntime, RuntimeSession, defaultCapabilities, RuntimeError, ToolRegistry, compileContext, assertPaired, type HistoryTurn, type ToolOutcome } from '../src/index.js';
import { fixture, registry, descriptor, PNG } from './fixtures.js';
import { ProtocolAudit } from '../src/protocol-audit.js';

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
  test(`${protocol}: unbounded model waits for delayed headers and still stops explicitly`,async()=>{
    const f=fixture(protocol,[{text:'late headers'}]);
    f.setOnRequest(()=>new Promise(resolve=>setTimeout(resolve,35)));
    assert.equal((await new ThorRuntime(f.ports,registry(),{runTimeoutMs:0}).run(f.lease,[{type:'text',text:'wait'}])).state,'completed');
    const stopped=fixture(protocol,[{wait:true}]);const runtime=new ThorRuntime(stopped.ports,registry(),{runTimeoutMs:0});
    stopped.setOnRequest(()=>{setTimeout(()=>runtime.stop(),20);});
    assert.equal((await runtime.run(stopped.lease,[{type:'text',text:'wait'}])).state,'cancelled');
  });
  test(`${protocol}: failed research survives a follow-up with the same Pi Agent`,async()=>{
    const session=new RuntimeSession('session-test');
    const first=fixture(protocol,[{call:{name:'echo',id:'research_unique'}},{status:500}]);
    const before=new ThorRuntime(first.ports,registry(),{},session);
    assert.equal((await before.run(first.lease,[{type:'text',text:'remember research goal'}])).state,'failed');
    const recorded=first.turns.at(-1)!;assert.equal(recorded.complete,false);
    assert.ok(JSON.stringify(recorded.messages).includes('echo-result'));
    assert.ok(first.turns.some(t=>!t.complete && t.messages.some(m=>m.kind==='tool_result')),'research must checkpoint before the later HTTP failure');
    const agent=session.agent;const previousMessages=agent!.state.messages.length;
    const second=fixture(protocol,[{text:'continued with research'}]);
    assert.equal((await new ThorRuntime(second.ports,registry(),{},session).run(second.lease,[{type:'text',text:'continue from there'}],[recorded])).state,'completed');
    assert.equal(session.agent,agent);assert.ok(agent!.state.messages.length>previousMessages);
    const body=JSON.stringify(second.requests[0]!.body);
    assert.ok(body.includes('remember research goal'));assert.ok(body.includes('echo-result'));assert.ok(body.includes('interrupted'));
    assert.equal(body.split('continue from there').length-1,1,'new user input must not be sliced away by Pi system records');
    assert.equal(second.turns.at(-1)!.messages.filter(m=>m.kind==='user').length,1);
    assert.ok(JSON.stringify(second.turns.at(-1)!.messages[0]).includes('continue from there'));
    assert.equal(second.executions.length,0,'history must not replay an observed tool');
    const third=fixture(protocol,[{text:'after restart'}]);
    assert.equal((await new ThorRuntime(third.ports,registry(),{},new RuntimeSession('session-test')).run(third.lease,[{type:'text',text:'continue after restart'}],[recorded,second.turns.at(-1)!])).state,'completed');
    assert.ok(JSON.stringify(third.requests[0]!.body).includes('echo-result'));
  });
}

for (const protocol of ['openai-responses', 'anthropic-messages'] as const) {
  test(`${protocol}: streamed text + user image + image tool round trip through injected fetch`, async () => {
    const f = fixture(protocol, [{ call: { name: 'picture' } }, { text: '图片回传完成中文' }]);
    const r = new ThorRuntime(f.ports, registry());
    const run = await r.run(f.lease, [{ type: 'text', text: 'Describe synthetic image' }, { type: 'attachment', attachmentId: 'user-image' }]);
    assert.equal(run.state, 'completed'); assert.equal(f.executions.length, 1); assert.equal(f.requests.length, 2);
    assert.equal(f.events.filter(e => e.type === 'text_delta').map(e => e.data.text).join(''), '图片回传完成中文');
    assert.equal(f.requests[0]!.url, protocol === 'openai-responses' ? 'https://fixture.invalid/v1/responses' : 'https://fixture.invalid/v1/messages?beta=true');
    for (const request of f.requests) {
      assert.equal(request.envelope.purpose, 'model'); assert.equal(request.envelope.credentialRef, 'credential-local-test');
      const auth = protocol === 'openai-responses' ? request.headers.authorization : request.headers['x-api-key'];
      assert.equal(auth, protocol === 'openai-responses' ? 'Bearer sk-thor-native-placeholder' : 'sk-thor-native-placeholder');
      assert.deepEqual(request.body.tools.map((t: any) => t.name).sort(), ['echo', 'picture']);
      assert.equal(JSON.stringify(request.body).split('<L0>').length - 1, 1);
    }
    const first = JSON.stringify(f.requests[0]!.body); const second = JSON.stringify(f.requests[1]!.body);
    assert.ok(first.includes(PNG)); assert.ok(second.includes(PNG));
    if (protocol === 'openai-responses') {
      const output = f.requests[1]!.body.input.find((i: any) => i.type === 'function_call_output');
      assert.equal(output.call_id, 'call_0'); assert.ok(output.output.some((i: any) => i.type === 'input_image' && i.image_url.endsWith(PNG)));
    } else {
      const result = f.requests[1]!.body.messages.flatMap((m: any) => m.content).find((c: any) => c.type === 'tool_result');
      assert.equal(result.tool_use_id, 'call_0'); assert.ok(result.content.some((c: any) => c.type === 'image' && c.source.data === PNG));
    }
    assert.ok(!JSON.stringify(f.turns).includes(PNG)); // Durable domain records contain only attachment references.
    assert.equal(f.events.find(e => e.type === 'tool_intent')!.sequence < f.events.find(e => e.type === 'tool_started')!.sequence, true);
    assert.deepEqual(f.events.map(e => e.sequence), f.events.map((_, i) => i + 1));
  });
  test(`${protocol}: one-byte SSE chunks preserve UTF-8 and deliver multiple deltas`, async () => {
    const f = fixture(protocol, [{ text: '你好中文', chunkSize: 1 }]);
    const r = new ThorRuntime(f.ports, registry()); let streamedBeforeClose = false;
    r.subscribe(e => { if (e.type === 'text_delta' && !f.closedResponses.includes(0)) streamedBeforeClose = true; });
    assert.equal((await r.run(f.lease, [{ type: 'text', text: 'hello' }])).state, 'completed'); assert.equal(streamedBeforeClose, true);
    assert.equal(f.events.filter(e => e.type === 'text_delta').map(e => e.data.text).join(''), '你好中文');
  });
  test(`${protocol}: malformed complete tool JSON never executes`, async () => {
    const f = fixture(protocol, [{ call: { name: 'echo', args: '{"text":"partial"' } }]);
    await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'echo' }]);
    assert.equal(f.executions.length, 0); assert.ok(f.events.some(e => e.type === 'diagnostic' && e.data.code === 'TOOL_FAILED'));
  });
  test(`${protocol}: disconnect with partial tool arguments never executes`, async () => {
    const f = fixture(protocol, [{ call: { name: 'echo', args: '{"text":' }, disconnect: true }]);
    assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'echo' }])).state, 'failed');
    assert.equal(f.executions.length, 0);
  });
  test(`${protocol}: HTTP 429/500 fails without retry or leaking provider errors`, async () => {
    for (const status of [401, 403, 429, 500]) {
      const f = fixture(protocol, [{ status }]); const r = new ThorRuntime(f.ports, registry());
      assert.equal((await r.run(f.lease, [{ type: 'text', text: 'hello' }])).state, 'failed');
      assert.equal(f.requests.length, 1); assert.ok(!JSON.stringify(f.events).includes('synthetic-http-failure'));
    }
  });
  test(`${protocol}: stop cancels an open stream and terminal state persists once`, async () => {
    const f = fixture(protocol, [{ wait: true }]); const r = new ThorRuntime(f.ports, registry());
    f.setOnRequest(() => r.stop());
    const run = await r.run(f.lease, [{ type: 'text', text: 'hello' }]);
    assert.equal(run.state, 'cancelled'); assert.equal(f.executions.length, 0);
    assert.equal(f.runs.filter(x => x.state === 'cancelled').length, 1);
  });
}
test('default capabilities and unavailable backend produce exact effective set', () => {
  assert.deepEqual(Object.values(defaultCapabilities()), [true, true, false, false]);
  const f = fixture(); const r = registry(); assert.deepEqual(r.effective(f.snapshot).map(t => t.modelName), ['echo', 'picture']);
  f.snapshot.session.enabled.screen_perception = false; f.snapshot.availability['search-backend'] = { status: 'ready' };
  assert.deepEqual(r.effective(f.snapshot).map(t => t.modelName), ['search']);
  assert.throws(() => r.register({ packId: 'bad', packVersion: '1', tools: [descriptor('echo')] }));
  assert.throws(() => new ToolRegistry().register({ packId: 'bad', packVersion: '1', tools: [descriptor('bad', ['unknown' as any])] }));
});
test('Anthropic completed zero-argument tool block is valid without JSON deltas', () => {
  const audit = new ProtocolAudit();
  audit.observe('anthropic-messages', { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'zero', name: 'app_info', input: {} } });
  assert.equal(audit.permits('zero', {}), false);
  audit.observe('anthropic-messages', { type: 'content_block_stop', index: 0 });
  assert.equal(audit.permits('zero', {}), true);
});
test('off/on epoch change after model response rejects old call; a fresh lease can execute', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }]); const r = new ThorRuntime(f.ports, registry());
  r.subscribe(e => {
    if (e.type === 'state' && e.data.state === 'executing_tools') {
      f.snapshot.session.enabled.screen_perception = false; f.snapshot.session.capabilityEpoch++;
      r.authorizationChanged(f.lease.sessionId, f.snapshot.session.capabilityEpoch);
      f.snapshot.session.enabled.screen_perception = true; f.snapshot.session.capabilityEpoch++; f.snapshot.lease.capabilityEpoch = f.snapshot.session.capabilityEpoch;
    }
  });
  assert.equal((await r.run(f.lease, [{ type: 'text', text: 'echo' }])).state, 'cancelled');
  assert.equal(f.executions.length, 0);
  const fresh = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'new call done' }]); fresh.snapshot.session.capabilityEpoch = fresh.snapshot.lease.capabilityEpoch = 3;
  assert.equal((await new ThorRuntime(fresh.ports, registry()).run(fresh.snapshot.lease, [{ type: 'text', text: 'echo' }])).state, 'completed');
  assert.equal(fresh.executions.length, 1);
});
test('epoch rechecked after durable intent before executor; Native remains final gate', async () => {
  const f = fixture('anthropic-messages', [{ call: { name: 'echo' } }]); const r = new ThorRuntime(f.ports, registry());
  r.subscribe(e => { if (e.type === 'tool_intent') { f.snapshot.session.capabilityEpoch += 2; f.snapshot.lease.capabilityEpoch += 2; } });
  await r.run(f.lease, [{ type: 'text', text: 'echo' }]); assert.equal(f.executions.length, 0);
});
test('revoked late image result is never delivered to model', async () => {
  const f = fixture('anthropic-messages', [{ call: { name: 'picture' } }]); const r = new ThorRuntime(f.ports, registry());
  f.setTool(async () => { f.snapshot.session.capabilityEpoch++; r.authorizationChanged(f.lease.sessionId, f.snapshot.session.capabilityEpoch); return { content: [{ type: 'attachment', attachmentId: 'tool-image' }], observedAt: Date.now(), source: 'fixture', truncated: false }; });
  assert.equal((await r.run(f.lease, [{ type: 'text', text: 'image' }])).state, 'cancelled'); assert.equal(f.requests.length, 1);
  assert.ok(!JSON.stringify(f.events).includes(PNG));
});
test('unknown/uninjected tools, invalid schema and duplicate call IDs do not execute', async () => {
  for (const name of ['missing', 'tap', 'shell', 'search']) {
    const f = fixture('openai-responses', [{ call: { name } }, { text: 'blocked' }]);
    await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: name }]); assert.equal(f.executions.length, 0);
  }
  const bad = fixture('openai-responses', [{ call: { name: 'echo', args: '{"text":17}' } }, { text: 'invalid' }]);
  await new ThorRuntime(bad.ports, registry()).run(bad.lease, [{ type: 'text', text: 'invalid' }]); assert.equal(bad.executions.length, 0);
  const dup = fixture('openai-responses', [{ call: { id: 'same', name: 'echo' } }, { call: { id: 'same', name: 'echo', args: '{"text":"different"}' } }, { text: 'done' }]);
  await new ThorRuntime(dup.ports, registry()).run(dup.lease, [{ type: 'text', text: 'loop' }]); assert.equal(dup.executions.length, 1);
});
test('tool failures are error tool results, text truncates at valid UTF-8 boundary', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'failure handled' }]);
  f.setTool(async () => { throw new Error('secret-from-port'); });
  assert.equal((await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'error' }])).state, 'completed');
  assert.ok(JSON.stringify(f.requests[1]!.body).includes('IO_ERROR')); assert.ok(!JSON.stringify(f.events).includes('secret-from-port'));
  const t = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'done' }]);
  t.setTool(async (): Promise<ToolOutcome> => ({ content: [{ type: 'text', text: '中'.repeat(800) }], observedAt: Date.now(), source: 'fixture', truncated: false }));
  await new ThorRuntime(t.ports, registry()).run(t.lease, [{ type: 'text', text: 'long' }]);
  const result = t.events.find(e => e.type === 'tool_result')!; assert.equal(result.data.truncated, true); assert.ok(!JSON.stringify(result).includes('�'));
});
test('bounded deterministic compression drops whole pairs; revoked derived history is filtered', async () => {
  const f = fixture(); const m: Model<any> = { id: 'fixture-model', name: 'Test', provider: 'openai', api: 'openai-responses', baseUrl: f.snapshot.session.model.baseUrl, reasoning: false, input: ['text', 'image'], contextWindow: 8000, maxTokens: 100, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const history: HistoryTurn[] = Array.from({ length: 10 }, (_, i) => ({ turnId: `t${i}`, runId: `r${i}`, source: ['screen_perception'], observedAt: Date.now(), complete: true,
    messages: [{ kind: 'user', content: [{ type: 'text', text: `goal-${i}` }] }, { kind: 'assistant', text: '', calls: [{ id: `c${i}`, name: 'echo', arguments: { text: 'test' } }] }, { kind: 'tool_result', toolCallId: `c${i}`, toolName: 'echo', content: [{ type: 'text', text: `PRIVATE-${i}` + 'x'.repeat(800) }], isError: false }, { kind: 'assistant', text: `Derived PRIVATE-${i}`, calls: [] }] }));
  const compile = () => compileContext({ snapshot: f.snapshot, tools: registry().effective(f.snapshot), registryVersion: '1', model: m, history, current: [{ role: 'user', content: 'CURRENT GOAL', timestamp: 1 }], attachments: f.ports.attachments, imageTokens: 100, maxHistoryTurns: 50 });
  const before = await compile(); assert.ok(before.manifest.droppedTurnIds.length > 0); assert.ok(before.manifest.estimatedTokens <= m.contextWindow-m.maxTokens); assertPaired(before.messages); assert.ok(JSON.stringify(before.messages).includes('CURRENT GOAL')); assert.equal(history.length, 10);
  f.snapshot.session.enabled.screen_perception = false;
  const after = await compile(); assert.ok(!JSON.stringify(after.messages).includes('PRIVATE')); assert.ok(JSON.stringify(after.messages).includes('goal-0')); assert.equal(after.messages.filter(m => m.role === 'system').length, 1); assert.ok(!JSON.stringify(after.messages).includes('local.test'));
});
test('required current content cannot fit: stops without a request', async () => {
  const f = fixture(); f.snapshot.session.model.contextWindow = 2000; f.snapshot.session.model.maxOutput = 1000;
  const result = await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'x'.repeat(8000) }]); assert.equal(result.state, 'failed'); assert.equal(result.error, 'LIMIT_EXCEEDED'); assert.equal(f.requests.length, 0);
});
test('consumed leases do not run twice and a second Session cannot run concurrently', async () => {
  const f = fixture(); const r = new ThorRuntime(f.ports, registry());
  await r.run(f.lease, [{ type: 'text', text: 'one' }]);
  await assert.rejects(r.run(f.lease, [{ type: 'text', text: 'again' }]), (e: any) => e.code === 'STALE_AUTHORIZATION');
  const held = fixture('openai-responses', [{ wait: true }]); const coordinator = new ThorRuntime(held.ports, registry());
  let resolveRequested!: () => void; const requested = new Promise<void>(resolve => { resolveRequested = resolve; }); held.setOnRequest(resolveRequested);
  const first = coordinator.run(held.lease, [{ type: 'text', text: 'held' }]); await requested;
  await assert.rejects(coordinator.run({ ...held.lease, sessionId: 'second', runId: 'second' }, [{ type: 'text', text: 'two' }]), (e: any) => e.code === 'BACKEND_UNAVAILABLE');
  coordinator.stop(); assert.equal((await first).state, 'cancelled');
});
test('expired images are reloaded before every request and L0 is replaced', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'picture' } }, { text: 'expired image handled' }]); const r = new ThorRuntime(f.ports, registry());
  r.subscribe(e => { if (e.type === 'tool_result') { f.attachments.get('tool-image')!.expiresAt = 0; f.snapshot.l0.snapshotId = 'l0-2'; f.snapshot.l0.upperApp = { packageName: 'new.app', label: 'New app' }; } });
  assert.equal((await r.run(f.lease, [{ type: 'text', text: 'image' }])).state, 'completed');
  const second = JSON.stringify(f.requests[1]!.body); assert.ok(!second.includes(PNG)); assert.ok(second.includes('Image unavailable')); assert.ok(!second.includes('local.test')); assert.ok(second.includes('new.app'));
  assert.equal(f.contexts[1]!.l0SnapshotId, 'l0-2'); assert.ok(f.contexts[1]!.attachmentRefs.includes('tool-image'));
});
test('tool deadline stops waiting for an uncooperative executor; late data cannot re-enter', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'timeout handled' }]); const reg = new ToolRegistry(); reg.register({ packId: 'short', packVersion: '1', tools: [{ ...descriptor(), timeoutMs: 10 }] });
  let deliver!: (result: ToolOutcome) => void;
  f.setTool(() => new Promise(resolve => { deliver = resolve; }));
  assert.equal((await new ThorRuntime(f.ports, reg).run(f.lease, [{ type: 'text', text: 'deadline' }])).state, 'completed');
  assert.ok(JSON.stringify(f.requests[1]!.body).includes('TIMEOUT'));
  const count = f.events.length; deliver({ content: [{ type: 'text', text: 'LATE PRIVATE RESULT' }], source: 'fake', observedAt: Date.now(), truncated: false }); await Promise.resolve();
  assert.equal(f.events.length, count); assert.ok(!JSON.stringify(f.requests).includes('LATE PRIVATE RESULT'));
});
test('compression retains explicit constraints and side-effect ledger; oversized required memory stops', async () => {
  const f = fixture(); f.snapshot.session.enabled.adb_debug = true;
  const p = f.snapshot.session.model; const model: Model<any> = { id: p.modelId, name: 'T', api: p.protocol, provider: 'openai', baseUrl: p.baseUrl, reasoning: false, input: ['text'], contextWindow: 8000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const old: HistoryTurn = { turnId: 'old', runId: 'old', source: ['adb_debug'], observedAt: 1, complete: true, messages: [{ kind: 'user', content: [{ type: 'text', text: 'old request'.repeat(1000) }] }], constraints: ['Do not overwrite existing saves'], effects: [{ toolCallId: 'c', toolId: 'files.write', effectClass: 'write', outcome: 'observed', evidenceRef: 'backup-123' }] };
  const options = { snapshot: f.snapshot, tools: [], registryVersion: '1', model, history: [old], current: [{ role: 'user' as const, content: 'current', timestamp: 1 }], attachments: f.ports.attachments, imageTokens: 100, maxHistoryTurns: 1 };
  const compiled = await compileContext(options); assert.deepEqual(compiled.manifest.droppedTurnIds, ['old']); const text = JSON.stringify(compiled.messages); assert.ok(text.includes('backup-123')); assert.ok(text.includes('Do not overwrite existing saves'));
  f.snapshot.session.enabled.adb_debug = false; assert.ok(!JSON.stringify((await compileContext(options)).messages).includes('backup-123'));
  old.constraints = ['x'.repeat(10_000)]; await assert.rejects(compileContext(options), (e: any) => e.code === 'LIMIT_EXCEEDED');
});
test('authority bootstrap can be cancelled and configuration changes affect only new Runs', async () => {
  const f = fixture(); f.ports.authority.read = async () => new Promise(() => {}); const r = new ThorRuntime(f.ports, registry());
  const pending = r.run(f.lease, [{ type: 'text', text: 'pending' }]); r.stop(); await assert.rejects(pending, (e: any) => e.code === 'CANCELLED'); assert.equal(f.requests.length, 0);
  const pinned = fixture('openai-responses', [{ call: { name: 'echo' } }, { text: 'done' }]); pinned.setOnRequest(index => { if (!index) { pinned.snapshot.session.model.revision = 2; pinned.snapshot.session.model.modelId = 'changed-model'; } });
  await new ThorRuntime(pinned.ports, registry()).run(pinned.lease, [{ type: 'text', text: 'pin' }]); assert.ok(pinned.requests.every(q => q.body.model === 'fixture-model' && q.envelope.profileRevision === 1));
});
test('timeout, request/response budgets and bounded tool loop fail', async () => {
  const f = fixture('anthropic-messages', [{ wait: true }]);
  assert.equal((await new ThorRuntime(f.ports, registry(), { runTimeoutMs: 30 }).run(f.lease, [{ type: 'text', text: 'wait' }])).state, 'failed');
  for (const limits of [{ maxRequestBytes: 20 }, { maxResponseBytes: 20 }]) {
    const f = fixture(); assert.equal((await new ThorRuntime(f.ports, registry(), limits).run(f.lease, [{ type: 'text', text: 'budget' }])).state, 'failed');
  }
  const loop = fixture('openai-responses', [{ call: { name: 'echo' } }, { call: { name: 'echo' } }]);
  assert.equal((await new ThorRuntime(loop.ports, registry(), { maxRequests: 1 }).run(loop.lease, [{ type: 'text', text: 'loop' }])).state, 'failed'); assert.equal(loop.requests.length, 1);
});
test('session/run mismatch, expired lease, incomplete probes reject before network', async () => {
  for (const mutate of [(f: ReturnType<typeof fixture>) => { f.snapshot.lease.sessionId = 'other'; }, (f: ReturnType<typeof fixture>) => { f.snapshot.lease.expiresAt = 0; }, (f: ReturnType<typeof fixture>) => { f.snapshot.session.model.lastProbe.imageToolRoundTrip = false; }]) {
    const f = fixture(); const lease = structuredClone(f.lease); mutate(f);
    try { await new ThorRuntime(f.ports, registry()).run(lease, [{ type: 'text', text: 'bad lease' }]); } catch (e) { assert.ok(e instanceof RuntimeError); }
    assert.equal(f.requests.length, 0);
  }
});
test('failed intent persistence prevents execution; recovery never replays', async () => {
  const f = fixture('openai-responses', [{ call: { name: 'echo' } }]); const original = f.ports.persistence.appendEvent;
  f.ports.persistence.appendEvent = async e => { if (e.type === 'tool_intent') throw new Error('disk full'); return original(e); };
  await assert.rejects(new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'persist' }]), (e: any) => e.code === 'PERSISTENCE_ERROR'); assert.equal(f.executions.length, 0); assert.equal(f.requests.length, 1);
  const restored = await new ThorRuntime(f.ports, registry()).recover({ sessionId: 's', runId: 'r', state: 'executing_tools', startedAt: 1, modelProfileRevision: 1, capabilityEpoch: 1 });
  assert.equal(restored.state, 'interrupted'); assert.equal(f.executions.length, 0);
});

test('only the audited internal configuration fixture can omit public capabilities', () => {
  const probe = {...descriptor('probe'),toolId:'probe.picture',executor:'native-probe',provenance:'native-configuration-probe',requiredCapabilities:[],platformPrerequisites:['probe.picture']};
  const r=new ToolRegistry();r.register({packId:'configuration-probe',packVersion:'1',tools:[probe]});
  const f=fixture();f.snapshot.session.enabled.screen_perception=false;f.snapshot.availability['probe.picture']={status:'ready'};
  assert.equal(r.effective(f.snapshot).length,1);
  assert.throws(()=>new ToolRegistry().register({packId:'untrusted',packVersion:'1',tools:[probe]}));
  assert.throws(()=>new ToolRegistry().register({packId:'configuration-probe',packVersion:'1',tools:[{...probe,effectClass:'arbitrary-shell'}]}));
});

test('Skill metadata is discoverable, bodies and referenced files enter context only after explicit read',async()=>{
 const f=fixture('openai-responses',[{call:{name:'skills_read',args:'{"name":"game-guide"}'}},{text:'已按攻略完成'}]);
 f.snapshot.skills=[{name:'game-guide',description:'通关游戏时使用',hash:'v1'}];f.snapshot.availability['skills.read']={status:'ready'};
 const reg=new ToolRegistry();reg.register({packId:'thor.skills',packVersion:'1',tools:[{...descriptor('skills_read',[]),toolId:'skills.read',executor:'native-skills',provenance:'builtin',resources:['local-skills'],platformPrerequisites:['skills.read'],inputSchema:{type:'object',properties:{name:{type:'string'}},required:['name'],additionalProperties:false}}]});
 f.setTool(async()=>({content:[{type:'text',text:'BODY_SENTINEL. Read references/boss.md only when needed.'}],observedAt:Date.now(),source:'native-skills',truncated:false,outcome:'observed'}));
 assert.equal((await new ThorRuntime(f.ports,reg).run(f.lease,[{type:'text',text:'使用攻略'}])).state,'completed');
 assert.match(JSON.stringify(f.requests[0]!.body),/game-guide/);assert.doesNotMatch(JSON.stringify(f.requests[0]!.body),/BODY_SENTINEL/);assert.match(JSON.stringify(f.requests[1]!.body),/BODY_SENTINEL/);
 assert.equal(f.executions.length,1);assert.equal(f.executions[0]!.toolId,'skills.read');
});

test('provider thinking deltas are decoded separately from answer text and never stored as raw events',async()=>{
 for(const protocol of ['anthropic-messages','openai-responses'] as const) {
  const f=fixture(protocol);const original=f.ports.network.fetch;
  f.ports.network.fetch=async(...args)=>{
   const response=await original(...args);await response.body?.cancel();
   const raw=protocol==='anthropic-messages'?[
    {type:'message_start',message:{id:'m',type:'message',role:'assistant',model:'fixture-model',content:[],stop_reason:null,usage:{input_tokens:10,output_tokens:0}}},
    {type:'content_block_start',index:0,content_block:{type:'thinking',thinking:''}},
    {type:'content_block_delta',index:0,delta:{type:'thinking_delta',thinking:'先看'}},
    {type:'content_block_delta',index:0,delta:{type:'thinking_delta',thinking:'攻略'}},
    {type:'content_block_stop',index:0},
    {type:'content_block_start',index:1,content_block:{type:'text',text:''}},
    {type:'content_block_delta',index:1,delta:{type:'text_delta',text:'完成'}},
    {type:'content_block_stop',index:1},{type:'message_delta',delta:{stop_reason:'end_turn'},usage:{output_tokens:4}},{type:'message_stop'}
   ]:[
    {type:'response.created',response:{id:'r',status:'in_progress'}},
    {type:'response.output_item.added',output_index:0,item:{type:'reasoning',id:'reason',summary:[]}},
    {type:'response.reasoning_summary_part.added',output_index:0,summary_index:0,part:{type:'summary_text',text:''}},
    {type:'response.reasoning_summary_text.delta',output_index:0,summary_index:0,delta:'先看'},
    {type:'response.reasoning_summary_text.delta',output_index:0,summary_index:0,delta:'攻略'},
    {type:'response.output_item.done',output_index:0,item:{type:'reasoning',id:'reason',summary:[{type:'summary_text',text:'先看攻略'}]}},
    {type:'response.output_item.added',output_index:1,item:{type:'message',id:'m',role:'assistant',content:[],status:'in_progress'}},
    {type:'response.content_part.added',output_index:1,content_index:0,part:{type:'output_text',text:'',annotations:[]}},
    {type:'response.output_text.delta',output_index:1,content_index:0,delta:'完成'},
    {type:'response.output_item.done',output_index:1,item:{type:'message',id:'m',role:'assistant',content:[{type:'output_text',text:'完成',annotations:[]}],status:'completed'}},
    {type:'response.completed',response:{id:'r',status:'completed',output:[],usage:{input_tokens:10,output_tokens:4,total_tokens:14}}}
   ];
   return new Response(raw.map(e=>`event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
  };
  assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'hello'}])).state,'completed');
  assert.equal(f.events.filter(e=>e.type==='thinking_delta').map(e=>e.data.text).join(''),'先看攻略');
  assert.equal(f.events.filter(e=>e.type==='text_delta').map(e=>e.data.text).join(''),'完成');
  assert.ok(f.events.filter(e=>e.type==='thinking_delta').every(e=>Object.keys(e.data).sort().join() === 'blockId,text'));
 }
});

test('large Skill catalogs stay bounded and advertise explicit paginated discovery',async()=>{
 const f=fixture();f.snapshot.skills=Array.from({length:64},(_,i)=>({name:`guide-${i}`,description:'介绍'.repeat(500),hash:'v1'}));
 assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'hello'}])).state,'completed');
 const request=JSON.stringify(f.requests[0]!.body);assert.match(request,/skills_list/);assert.doesNotMatch(request,/guide-63/);assert.ok(request.length<20000);
});
test('large unavailable tool inventories keep L0 bounded without changing effective authorization',async()=>{
 const f=fixture();
 for(let i=0;i<100;i++)f.snapshot.availability[`extra.long_diagnostic_tool_${i}`]={status:'backend_missing'};
 assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'hello'}])).state,'completed');
 const body=JSON.stringify(f.requests[0]!.body);
 assert.match(body,/unavailableOmitted/);assert.doesNotMatch(body,/extra.long_diagnostic_tool_99/);
 function strings(value:unknown):string[] {return typeof value==='string'?[value]:value&&typeof value==='object'?Object.values(value).flatMap(strings):[];}
 const l0=strings(f.requests[0]!.body).map(s=>s.match(/<L0>(.*?)<\/L0>/s)?.[1]).find(Boolean)!;
 assert.ok(new TextEncoder().encode(l0).byteLength<=1024);assert.ok(JSON.parse(l0).unavailableOmitted>0);
 assert.deepEqual(JSON.parse(l0).enabled,f.snapshot.session.enabled);
 assert.ok(f.contexts[0]!.effectiveTools.every(id=>!id.startsWith('extra.')));
});

test('Chat Completions validates complete tool JSON and preserves image round trip',async()=>{
 const f=fixture('openai-completions',[{call:{name:'picture'}},{text:'三种协议均可用'}]);const r=new ThorRuntime(f.ports,registry());const result=await r.run(f.lease,[{type:'text',text:'inspect'},{type:'attachment',attachmentId:'user-image'}]);assert.equal(result.state,'completed');assert.equal(f.executions.length,1);assert.match(f.requests[0]!.url,/\/chat\/completions$/);
});
test('selected reasoning effort reaches Responses payload and uses model context profile',async()=>{
 const f=fixture();f.snapshot.session.model={...f.snapshot.session.model,contextWindow:1000000,reasoning:true,thinkingEffort:'max',thinkingLevelMap:{low:'low',high:'high',max:'max'}};await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'reason'}]);assert.equal(f.requests[0]!.body.reasoning.effort,'max');assert.equal(f.contexts[0]!.modelProfileRevision,1);
});
test('Completions strict audit rejects truncated and conflicting argument streams',()=>{const a=new ProtocolAudit();a.observe('openai-completions',{choices:[{index:0,delta:{tool_calls:[{index:0,id:'c',function:{arguments:'{"text":'}}]},finish_reason:null}]});a.observe('openai-completions',{choices:[{index:0,delta:{},finish_reason:'tool_calls'}]});assert.equal(a.permits('c',{}),false);const truncated=new ProtocolAudit();truncated.observe('openai-completions',{choices:[{index:0,delta:{tool_calls:[{index:0,id:'t',function:{arguments:'{}'}}]},finish_reason:'length'}]});assert.equal(truncated.permits('t',{}),false);});

test('one-million-token model budget is used without an extra fixed percentage cutoff',async()=>{const f=fixture();const p=f.snapshot.session.model;const model:Model<any>={id:p.modelId,name:'1M',provider:'openai',api:p.protocol,reasoning:false,input:['text'],baseUrl:p.baseUrl,contextWindow:1000000,maxTokens:2048,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}};const c=await compileContext({snapshot:f.snapshot,tools:[],registryVersion:'1',model,history:[],current:[{role:'user',content:'x'.repeat(850000),timestamp:1}],attachments:f.ports.attachments,imageTokens:0,maxHistoryTurns:1});assert.ok(c.manifest.estimatedTokens>850000);assert.ok(c.manifest.estimatedTokens<=997952);});
test('text-only model probe flags and image placeholders are respected',async()=>{const f=fixture('openai-completions',[{call:{name:'picture'}},{text:'文字模型继续'}]);f.snapshot.session.model.input=['text'];f.snapshot.session.model.lastProbe.imageInput=false;f.snapshot.session.model.lastProbe.imageToolRoundTrip=false;const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'read'}]);assert.equal(result.state,'completed');assert.ok(!JSON.stringify(f.requests).includes(PNG));assert.match(JSON.stringify(f.requests),/supports text only|does not support images/);});

test('invalid Skill resource nesting stays blocked and returns a safe actionable schema hint',()=>{
 const t={...descriptor('skills_save'),toolId:'skills.save',inputSchema:{type:'object',properties:{content:{type:'string'},expectedHash:{type:'string'},resources:{type:'object',additionalProperties:{type:'string'}}},required:['content','expectedHash'],additionalProperties:false}};
 const r=new ToolRegistry();r.register({packId:'test-skill-schema',packVersion:'1',tools:[t]});
 const good={content:'guide',expectedHash:'',resources:{'references/a.md':'A','references/b.md':'B'}};
 assert.equal(r.validate('skills_save',good).toolId,'skills.save');
 assert.throws(()=>r.validate('skills_save',{content:'guide',expectedHash:'',resources:{'references/a.md':'A'},'references/b.md':'PRIVATE_CONTENT_MUST_NOT_ECHO'}),(error:unknown)=>error instanceof RuntimeError && error.code==='PROTOCOL_ERROR' && error.message.includes('resources object') && !error.message.includes('PRIVATE_CONTENT_MUST_NOT_ECHO'));
});
