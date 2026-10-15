import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNativeFetch, encodeFrame, decodeFrame } from './fetch-adapter.js';
const te = new TextEncoder();
const ctx = { binding: { sessionId: 's', runId: 'r', lease: 'l', connectionId: 'c', capabilityEpoch: 1 }, purpose: 'model', profileRef: 'local', deadlineMs: 999999, timeoutMs: 2000 };
test('unbounded model has no elapsed timer while ordinary web requests still time out',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const sent=[];let settled=false;
  const adapter=createNativeFetch({send:v=>sent.push(decodeFrame(v)),context:()=>({...ctx,timeoutMs:0})});
  const controller=new AbortController();const pending=adapter.fetch('https://local/model',{signal:controller.signal});
  pending.then(()=>{settled=true;},()=>{settled=true;});
  t.mock.timers.tick(30*86400000);await Promise.resolve();assert.equal(settled,false);
  controller.abort();await assert.rejects(pending,{code:'CANCELLED'});assert.equal(sent.filter(e=>e.type==='request.abort').length,1);
  const web=createNativeFetch({send:()=>{},context:()=>({...ctx,purpose:'web_fetch',timeoutMs:0})});
  const bounded=web.fetch('https://local/web');t.mock.timers.tick(180001);await assert.rejects(bounded,{code:'TIMEOUT'});
  adapter.close();web.close();
});
function fixture({ binary = false, status = 200, bytes = te.encode('中文🙂\nbytes'), headers = { 'content-type': ['text/plain'] }, chunkSize = 1, pause = false } = {}) {
  const sent = [], calls = new Map(); let adapter;
  const emit = (header, bytes) => queueMicrotask(() => adapter.receive(binary && bytes ? encodeFrame({ bridgeVersion: 1, ...header }, bytes) : JSON.stringify({ bridgeVersion: 1, ...header, ...(bytes ? { bytes: Buffer.from(bytes).toString('base64') } : {}) })));
  adapter = createNativeFetch({ binary, context: () => ctx, send: frame => {
    const event = decodeFrame(frame); sent.push(event);
    if (event.type === 'request.start') {
      calls.set(event.requestId, { offset: 0, sequence: 0 });
      emit({ type: 'response.head', requestId: event.requestId, status, statusText: '', headers, url: event.url });
      if (!bytes.length || event.method === 'HEAD' || [204, 205, 304].includes(status)) { emit({ type: 'response.end', requestId: event.requestId }); calls.delete(event.requestId); }
    } else if (event.type === 'stream.credit') {
      const call = calls.get(event.requestId); if (!call || pause) return;
      let allowance = event.bytes;
      while (call.offset < bytes.length && allowance > 0) {
        const n = Math.min(chunkSize, allowance, bytes.length - call.offset);
        emit({ type: 'response.chunk', requestId: event.requestId, sequence: call.sequence++ }, bytes.slice(call.offset, call.offset + n)); call.offset += n; allowance -= n;
      }
      if (call.offset === bytes.length) { emit({ type: 'response.end', requestId: event.requestId }); calls.delete(event.requestId); }
    } else if (event.type === 'request.abort') { calls.delete(event.requestId); emit({ type: 'response.error', requestId: event.requestId, code: 'CANCELLED' }); }
  } });
  return { adapter, sent, emit };
}
test('base64 and ArrayBuffer keep split UTF-8 raw until JS decode', async () => {
  for (const binary of [false, true]) {
    const { adapter, sent } = fixture({ binary });
    const response = await adapter.fetch(new URL('https://local/bytes'));
    assert.equal(sent.filter(e => e.type === 'stream.credit').length, 0, 'no body demand no credit');
    assert.equal(response.bodyUsed, false); assert.equal(await response.text(), '中文🙂\nbytes'); assert.equal(response.bodyUsed, true);
    assert.equal(response.url, 'https://local/bytes'); assert.throws(() => response.clone(), TypeError);
  }
});
test('4xx/5xx are ordinary Responses with complete body and duplicate headers', async () => {
  for (const status of [400, 429, 500]) {
    const { adapter } = fixture({ status, bytes: te.encode('{"error":"fixture"}'), headers: { 'x-repeat': ['one', 'two'] } });
    const r = await adapter.fetch('https://local/error'); assert.equal(r.status, status); assert.equal(r.ok, false); assert.equal(r.headers.get('x-repeat'), 'one, two'); assert.deepEqual(await r.json(), { error: 'fixture' });
  }
});
test('string/bytes/ArrayBuffer and Request inputs serialize and merge headers', async () => {
  for (const binary of [false, true]) {
    for (const body of ['{"hello":"中文"}', te.encode('raw'), te.encode('array').buffer]) {
      const { adapter, sent } = fixture({ binary }); await (await adapter.fetch('https://local/post', { method: 'POST', body, headers: { 'x-custom': 'v' } })).text();
      const start = sent.find(e => e.type === 'request.start'); assert.equal(start.method, 'POST'); assert.equal(start.headers['x-custom'], 'v'); assert.ok(start.bytes instanceof Uint8Array); assert.deepEqual(start.binding, ctx.binding);
    }
    const { adapter, sent } = fixture({ binary }); const request = new Request('https://local/post', { method: 'POST', headers: { 'content-type': 'application/json', 'x-base': 'one' }, body: '{"k":1}' });
    await (await adapter.fetch(request, { headers: { 'x-extra': 'two' } })).text(); const start = sent.find(e => e.type === 'request.start'); assert.equal(new TextDecoder().decode(start.bytes), '{"k":1}'); assert.equal(start.headers['x-base'], 'one'); assert.equal(start.headers['x-extra'], 'two'); assert.equal(request.bodyUsed, true);
  }
});
test('unsupported uploads/options and oversized bodies fail before posting', async () => {
  const { adapter, sent } = fixture();
  for (const body of [new FormData(), new Blob(['x']), {}, new ReadableStream()]) await assert.rejects(adapter.fetch('https://local', { method: 'POST', body }), TypeError);
  await assert.rejects(adapter.fetch('https://local', { method: 'POST', body: new Uint8Array(16777217) }), { code: 'LIMIT_EXCEEDED' });
  await assert.rejects(adapter.fetch('https://local', { credentials: 'include' }), TypeError); await assert.rejects(adapter.fetch('https://local', { body: 'x' }), TypeError);
  assert.equal(sent.length, 0);
});
test('abort before start and during body cancels Native call, rejects readers, ignores late bytes', async () => {
  const { adapter, sent, emit } = fixture({ pause: true }); const controller = new AbortController(); controller.abort(); await assert.rejects(adapter.fetch('https://local', { signal: controller.signal }), { name: 'AbortError' }); assert.equal(sent.length, 0);
  const active = new AbortController(); const response = await adapter.fetch('https://local', { signal: active.signal }); const reading = response.text(); active.abort(); await assert.rejects(reading, { name: 'AbortError' });
  const id = sent.find(e => e.type === 'request.start').requestId; emit({ type: 'response.chunk', requestId: id, sequence: 0 }, te.encode('late')); assert.equal(sent.filter(e => e.type === 'request.abort').length, 1);
});
test('clone preserves independent bodyUsed and bounded consumers', async () => {
  const { adapter } = fixture({ chunkSize: 3 }); const r = await adapter.fetch('https://local'); const clone = r.clone(); const [a, b] = await Promise.all([r.text(), clone.arrayBuffer()]); assert.equal(a, '中文🙂\nbytes'); assert.equal(new TextDecoder().decode(b), a); assert.equal(r.bodyUsed, true); assert.equal(clone.bodyUsed, true);
});
test('clone permits sequential consumers below the buffer budget', async () => {
  const { adapter } = fixture({ bytes: new Uint8Array(100000).fill(42), chunkSize: 32768 }); const r = await adapter.fetch('https://local'); const copy = r.clone(); const a = await r.arrayBuffer(); const b = await copy.arrayBuffer(); assert.deepEqual(new Uint8Array(a), new Uint8Array(b));
});
test('abandoned clone fails at the 256 KiB branch limit', async () => {
  const { adapter, sent } = fixture({ bytes: new Uint8Array(400000), chunkSize: 32768 }); const r = await adapter.fetch('https://local'); const copy = r.clone(); await assert.rejects(r.arrayBuffer(), { code: 'LIMIT_EXCEEDED' }); await assert.rejects(copy.arrayBuffer(), { code: 'LIMIT_EXCEEDED' }); assert.equal(sent.filter(e => e.type === 'request.abort').length, 1);
});
test('invalid chunk sequence/credit and error after head fail body', async () => {
  for (const event of [{ type: 'response.chunk', sequence: 3, bytes: te.encode('x') }, { type: 'response.error', code: 'IO_ERROR' }]) {
    const { adapter, sent, emit } = fixture({ pause: true }); const r = await adapter.fetch('https://local'); const reading = r.text(); const requestId = sent.find(e => e.type === 'request.start').requestId;
    emit({ ...event, requestId }, event.bytes); await assert.rejects(reading, { code: event.code || 'INVALID_REQUEST' });
  }
});
test('HEAD and no-body statuses have null body and no consumer credits', async () => {
  for (const status of [204, 205, 304]) { const { adapter, sent } = fixture({ status }); const r = await adapter.fetch('https://local'); assert.equal(r.body, null); assert.equal(await r.text(), ''); assert.equal(sent.filter(e => e.type === 'stream.credit').length, 0); }
  const { adapter } = fixture(); const r = await adapter.fetch('https://local', { method: 'HEAD' }); assert.equal(r.body, null);
});
test('binary frame bounds and arbitrary bytes round-trip', () => {
  const bytes = new Uint8Array([0, 255, 128, 1]); const decoded = decodeFrame(encodeFrame({ type: 'response.chunk', sequence: 2 }, bytes)); assert.deepEqual(decoded.bytes, bytes); assert.equal(decoded.sequence, 2);
  assert.throws(() => decodeFrame(new Uint8Array([0, 1, 0, 0]).buffer));
});

test('stalled Request serialization times out or cancels on bridge close', async () => {
  for (const action of ['timeout', 'close']) {
    const sent = []; const adapter = createNativeFetch({ send: v => sent.push(v), context: () => ({ ...ctx, timeoutMs: 20 }) });
    const request = new Request('https://local/post', { method: 'POST', body: new ReadableStream({ pull() {} }), duplex: 'half' });
    const promise = adapter.fetch(request); if (action === 'close') adapter.close();
    await assert.rejects(promise, { code: action === 'close' ? 'CANCELLED' : 'TIMEOUT' }); assert.equal(sent.length, 0);
    adapter.close(); await assert.rejects(adapter.fetch('https://local'), { code: 'CANCELLED' });
  }
});
