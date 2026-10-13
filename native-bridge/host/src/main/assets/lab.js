import { createNativeFetch, encodeFrame, decodeFrame } from './fetch-adapter.js';
const log = text => document.querySelector('#log').textContent += '\n' + text;
let ctx, adapter, binary = false;
const post = value => ThorNative.postMessage(value);
function ready() {
  adapter = createNativeFetch({ send: post, context: () => ctx, binary, onUnhandled: e => { if (e.type === 'tool.result') log(`mock.echo: ${e.code || 'PASS'} ${e.text}`); } });
  document.querySelector('#run').disabled = false; log(`READY: ${binary ? 'ArrayBuffer probe passed' : 'bounded base64'} · trusted main frame`);
}
ThorNative.onmessage = event => {
  const data = decodeFrame(event.data);
  if (data.type === 'bridge.ready') {
    ctx = data.context;
    if (data.arrayBufferSupported) post(encodeFrame({ type: 'bridge.binaryProbe' }, new Uint8Array([0, 255, 42]))); else ready();
  } else if (data.type === 'bridge.binaryReady') { binary = true; ready(); }
  else adapter?.receive(event.data);
};
post(JSON.stringify({ type: 'bridge.hello' }));
const assert = (condition, text) => { if (!condition) throw Error(text); log('PASS ' + text); };
document.querySelector('#run').onclick = async () => {
  document.querySelector('#run').disabled = true;
  try {
    const response = await adapter.fetch('http://127.0.0.1:8877/stream');
    const reader = response.body.getReader(), decoder = new TextDecoder(); let text = '', chunks = 0;
    while (true) { const v = await reader.read(); if (v.done) break; chunks++; text += decoder.decode(v.value, { stream: true }); }
    text += decoder.decode(); assert(text === '中文🙂\nbytes only\n' && chunks > 1, '分块 UTF-8');
    for (const status of [400, 500]) { const r = await adapter.fetch(`http://127.0.0.1:8877/status${status}`); assert(r.status === status && !r.ok && await r.text() === `error-${status}`, `HTTP ${status} body`); }
    const controller = new AbortController(); const r = await adapter.fetch('http://127.0.0.1:8877/slow', { signal: controller.signal }); const read = r.text(); controller.abort(); await read.then(() => { throw Error('abort unexpectedly succeeded'); }, e => assert(e.name === 'AbortError', 'abort 后终态'));
    const previous = ctx; ctx = { ...ctx, profileRef: 'local-auth', credentialRef: 'fixture' };
    const redirect = await adapter.fetch('http://127.0.0.1:8877/redirect'); assert(redirect.status === 302, '凭据重定向未自动跟随'); ctx = previous;
    const id = 'echo_' + crypto.randomUUID().replaceAll('-', '');
    post(JSON.stringify({ ...ctx, bridgeVersion: 1, type: 'tool.call', requestId: id, toolCallId: id, toolId: 'mock.echo', schemaVersion: 1, params: { text: 'no device effects' }, resource: 'fixture', snapshotId: 'fixture-v1' }));
  } catch (e) { log('FAIL ' + (e.code || e.message)); }
  finally { document.querySelector('#run').disabled = false; }
};
