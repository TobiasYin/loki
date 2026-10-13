const CHUNK = 32768, WINDOW = 262144, MAX_BODY = 16777216;
const encoder = new TextEncoder();
const b64 = bytes => { let s = ''; for (let i = 0; i < bytes.length; i += CHUNK) s += String.fromCharCode(...bytes.subarray(i, i + CHUNK)); return btoa(s); };
const unb64 = text => Uint8Array.from(atob(text), c => c.charCodeAt(0));
const failure = code => Object.assign(new Error(code), { code, name: code === 'CANCELLED' ? 'AbortError' : 'TypeError' });
export function encodeFrame(header, bytes = new Uint8Array()) {
  const meta = encoder.encode(JSON.stringify(header));
  const frame = new Uint8Array(4 + meta.length + bytes.length);
  new DataView(frame.buffer).setUint32(0, meta.length); frame.set(meta, 4); frame.set(bytes, 4 + meta.length);
  return frame.buffer;
}
export function decodeFrame(data) {
  if (typeof data === 'string') { const h = JSON.parse(data); if (typeof h.bytes === 'string') h.bytes = unb64(h.bytes); return h; }
  const raw = new Uint8Array(data), n = new DataView(data).getUint32(0);
  if (n > 65536 || n + 4 > raw.length) throw failure('INVALID_REQUEST');
  const h = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(raw.subarray(4, n + 4)));
  if (h.type === 'request.start' || h.type === 'response.chunk' || h.type === 'bridge.binaryProbe') h.bytes = raw.slice(n + 4); return h;
}
// Bounded clone branches: an abandoned reader cannot silently retain an entire response.
function boundedTee(source) {
  const reader = source.getReader(); let reading = false, ended = false;
  const queues = [[], []], sizes = [0, 0], controllers = [], cancelled = [false, false], finished = [false, false];
  function finish(i) { if (!cancelled[i] && !finished[i]) { finished[i] = true; controllers[i].close(); } }
  async function pump() {
    if (reading || ended) return; reading = true;
    try {
      const { value, done } = await reader.read();
      if (done) { ended = true; controllers.forEach((c, i) => { if (!cancelled[i] && !queues[i].length) finish(i); }); }
      else {
        for (let i = 0; i < 2; i++) if (!cancelled[i]) {
          queues[i].push(value.slice()); sizes[i] += value.byteLength;
          if (sizes[i] > WINDOW) throw failure('LIMIT_EXCEEDED');
        }
        flush();
      }
    } catch (e) { ended = true; controllers.forEach((c, i) => { if (!cancelled[i] && !finished[i]) { finished[i] = true; c.error(e); } }); await reader.cancel(e).catch(() => {}); }
    finally { reading = false; if (!ended && controllers.some((c, i) => !cancelled[i] && c.desiredSize > 0 && !queues[i].length)) pump(); }
  }
  function flush() {
    controllers.forEach((c, i) => {
      if (!cancelled[i] && c.desiredSize > 0 && queues[i].length) { const v = queues[i].shift(); sizes[i] -= v.byteLength; c.enqueue(v); }
      if (!cancelled[i] && ended && !queues[i].length) finish(i);
    });
  }
  return [0, 1].map(i => new ReadableStream({
    start(c) { controllers[i] = c; },
    pull() { flush(); return pump(); },
    cancel(reason) { cancelled[i] = true; queues[i] = []; sizes[i] = 0; if (cancelled.every(Boolean)) return reader.cancel(reason); }
  }, { highWaterMark: 1 }));
}
class FetchResponse {
  constructor(body, init, url) { this.inner = new Response(body, init); this.finalUrl = url; }
  get status() { return this.inner.status; } get statusText() { return this.inner.statusText; }
  get ok() { return this.inner.ok; } get headers() { return this.inner.headers; }
  get url() { return this.finalUrl; } get redirected() { return this.wasRedirected || false; }
  get type() { return 'basic'; } get body() { return this.inner.body; } get bodyUsed() { return this.inner.bodyUsed; }
  text() { return this.inner.text(); } json() { return this.inner.json(); } arrayBuffer() { return this.inner.arrayBuffer(); }
  clone() {
    if (this.bodyUsed || this.body?.locked) throw new TypeError('Body already used');
    const init = { status: this.status, statusText: this.statusText, headers: this.headers };
    if (!this.body) { const copy = new FetchResponse(null, init, this.url); copy.wasRedirected = this.wasRedirected; return copy; }
    const [a, b] = boundedTee(this.body); this.inner = new Response(a, init);
    const copy = new FetchResponse(b, init, this.url); copy.wasRedirected = this.wasRedirected; return copy;
  }
}
export function detectFetchFeatures() {
  const required = ['ReadableStream', 'Request', 'Response', 'Headers', 'TextEncoder', 'TextDecoder', 'AbortController', 'ArrayBuffer'];
  const missing = required.filter(name => typeof globalThis[name] !== 'function');
  if (typeof globalThis.crypto?.randomUUID !== 'function') missing.push('crypto.randomUUID');
  return { supported: missing.length === 0, missing };
}
/** send must post to the trusted Native connection; binding/profile come from Native bootstrap. */
export function createNativeFetch({ send, context, binary = false, onUnhandled = () => {} }) {
  if (!detectFetchFeatures().supported) throw failure('UNSUPPORTED');
  const requests = new Map(), uploads = new Set(); let counter = 0, closed = false;
  const prefix = globalThis.crypto.randomUUID().replaceAll('-', '');
  const post = (h, bytes) => {
    if (binary) send(encodeFrame(h, bytes));
    else send(JSON.stringify({ ...h, ...(bytes ? { bytes: b64(bytes) } : {}) }));
  };
  const base = (id, ctx) => ({ bridgeVersion: 1, requestId: id, ...ctx });
  function cleanup(s) { requests.delete(s.id); s.signal?.removeEventListener('abort', s.abort); clearTimeout(s.timer); }
  function terminate(s, code, notify = false) {
    if (!requests.has(s.id)) return;
    if (notify) { try { post({ ...base(s.id, s.ctx), type: 'request.abort' }); } catch { /* Native connection may already be gone. */ } }
    cleanup(s); const error = failure(code); if (s.controller) s.controller.error(error); else s.reject(error);
  }
  async function fetch(input, init = {}) {
    if (closed) throw failure('CANCELLED');
    const inputRequest = input instanceof Request;
    if (!(typeof input === 'string' || input instanceof URL || inputRequest)) throw new TypeError('Unsupported Fetch input');
    const ctx = context();
    const url = inputRequest ? input.url : String(input), method = (init.method ?? (inputRequest ? input.method : 'GET')).toUpperCase();
    const headers = new Headers(inputRequest ? input.headers : undefined);
    if (init.headers != null) new Headers(init.headers).forEach((v, k) => headers.set(k, v));
    const signal = init.signal ?? (inputRequest ? input.signal : undefined);
    if (signal?.aborted) throw failure('CANCELLED');
    for (const option of ['credentials', 'mode', 'cache', 'redirect', 'integrity', 'keepalive', 'referrer']) if (init[option] != null) throw new TypeError(`Unsupported Fetch option: ${option}`);
    let body = init.body;
    if (body == null && inputRequest && input.body != null) {
      if (input.bodyUsed || input.body.locked) throw new TypeError('Request body already used');
      if (/^(multipart\/form-data|application\/x-www-form-urlencoded)/i.test(input.headers.get('content-type') || '')) throw new TypeError('Form uploads unsupported');
      const reader = input.body.getReader(), chunks = []; let total = 0;
      let uploadCode;
      const cancelUpload = (code = 'CANCELLED') => { uploadCode ||= code; reader.cancel().catch(() => {}); };
      const stopUpload = () => cancelUpload('CANCELLED');
      const uploadTimer = setTimeout(() => cancelUpload('TIMEOUT'), Math.min(180000, ctx.timeoutMs || 180000));
      uploads.add(cancelUpload);
      signal?.addEventListener('abort', stopUpload, { once: true });
      try {
        while (true) {
          if (signal?.aborted) throw failure('CANCELLED');
          if (uploadCode) throw failure(uploadCode);
          const { value, done } = await reader.read(); if (done) break;
          if (!(value instanceof Uint8Array)) throw new TypeError('Request body must be bytes');
          total += value.byteLength; if (total > MAX_BODY) { await reader.cancel(); throw failure('LIMIT_EXCEEDED'); }
          chunks.push(value.slice());
        }
        if (signal?.aborted) throw failure('CANCELLED');
        if (uploadCode) throw failure(uploadCode);
        body = new Uint8Array(total); let offset = 0; for (const bytes of chunks) { body.set(bytes, offset); offset += bytes.length; }
      } finally { clearTimeout(uploadTimer); uploads.delete(cancelUpload); signal?.removeEventListener('abort', stopUpload); reader.releaseLock(); }
    }
    if (typeof body === 'string') { body = encoder.encode(body); if (!headers.has('content-type')) headers.set('content-type', 'text/plain;charset=UTF-8'); }
    else if (body instanceof ArrayBuffer) body = new Uint8Array(body.slice(0));
    else if (body instanceof Uint8Array) body = body.slice();
    else if (body != null) throw new TypeError('Serialize JSON to a string or bytes; form/blob/stream uploads unsupported');
    if ((body?.byteLength || 0) > MAX_BODY) throw failure('LIMIT_EXCEEDED');
    if (body != null && (method === 'GET' || method === 'HEAD')) throw new TypeError('GET/HEAD cannot have a body');
    const id = `js_${prefix}_${++counter}`;
    return new Promise((resolve, reject) => {
      const s = { id, ctx, resolve, reject, signal, url, method, seq: 0, inFlight: 0 };
      s.abort = () => terminate(s, 'CANCELLED', true);
      s.timer = setTimeout(() => terminate(s, 'TIMEOUT', true), Math.min(180000, ctx.timeoutMs || 180000));
      requests.set(id, s); signal?.addEventListener('abort', s.abort, { once: true });
      if (signal?.aborted) { s.abort(); return; }
      try { post({ ...base(id, ctx), type: 'request.start', url, method, headers: Object.fromEntries(headers), hasBody: body != null }, body); }
      catch (e) { cleanup(s); reject(e); }
    });
  }
  function receive(data) {
    let event;
    try { event = decodeFrame(data); } catch (e) { close('INVALID_REQUEST'); return; }
    const s = requests.get(event.requestId);
    if (!s) { onUnhandled(event); return; }
    try {
      if (event.bridgeVersion !== 1) throw failure('UNSUPPORTED');
      if (event.type === 'response.head') {
        if (s.controller || s.head) throw failure('INVALID_REQUEST'); s.head = true;
        const stream = new ReadableStream({
          start(c) { s.controller = c; },
          pull() { if (requests.has(s.id) && s.inFlight === 0) { s.inFlight = CHUNK; try { post({ ...base(s.id, s.ctx), type: 'stream.credit', bytes: CHUNK }); } catch { terminate(s, 'IO_ERROR'); } } },
          cancel() { terminate(s, 'CANCELLED', true); }
        }, { highWaterMark: 0 });
        const pairs = Object.entries(event.headers).flatMap(([k, vs]) => vs.map(v => [k, v]));
        const noBody = s.method === 'HEAD' || [204, 205, 304].includes(event.status);
        const response = new FetchResponse(noBody ? null : stream, { status: event.status, statusText: event.statusText, headers: pairs }, event.url);
        response.wasRedirected = event.url !== s.url; s.resolve(response);
      } else if (event.type === 'response.chunk') {
        if (!s.head || event.sequence !== s.seq++ || !(event.bytes instanceof Uint8Array) || event.bytes.length < 1 || event.bytes.length > CHUNK || event.bytes.length > s.inFlight) throw failure('INVALID_REQUEST');
        s.inFlight -= event.bytes.length; s.controller.enqueue(event.bytes);
      } else if (event.type === 'response.end') { if (!s.head) throw failure('INVALID_REQUEST'); cleanup(s); s.controller.close(); }
      else if (event.type === 'response.error') terminate(s, event.code);
      else throw failure('INVALID_REQUEST');
    } catch (e) { terminate(s, e.code || 'INVALID_REQUEST', true); }
  }
  function close(code = 'CANCELLED') { closed = true; for (const cancel of uploads) cancel(code); for (const s of [...requests.values()]) terminate(s, code, true); }
  return { fetch, receive, close };
}
