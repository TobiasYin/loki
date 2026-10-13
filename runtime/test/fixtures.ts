import { Type } from 'typebox';
import { defaultCapabilities, type AgentRun, type Attachment, type AuthoritySnapshot, type ContextSnapshot, type HistoryTurn, type ModelProfile, type RunEvent, type ToolEnvelope, type RuntimePorts, type ToolDescriptor, type ToolOutcome } from '../src/contracts.js';
import { ToolRegistry } from '../src/registry.js';

export const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS1cAAAAASUVORK5CYII=';
export function profile(protocol: ModelProfile['protocol'] = 'openai-responses'): ModelProfile {
  return { profileId: 'local-fixture', revision: 1, label: 'Fixture', protocol, baseUrl: protocol === 'openai-responses' ? 'https://fixture.invalid/v1' : 'https://fixture.invalid', modelId: 'fixture-model', credentialRef: 'credential-local-test', contextWindow: 32_000, maxOutput: 1024,
    lastProbe: { revision: 1, textStream: true, imageInput: true, toolCalling: true, imageToolRoundTrip: true } };
}
export const descriptor = (modelName = 'echo', caps: ToolDescriptor['requiredCapabilities'] = ['screen_perception']): ToolDescriptor => ({
  toolId: `test.${modelName}`, modelName, schemaVersion: '1', description: 'Return a harmless local test value.', inputSchema: Type.Object({ text: Type.String() }, { additionalProperties: false }),
  requiredCapabilities: caps, platformPrerequisites: ['local-test'], executor: 'fake', resources: ['synthetic'], effectClass: 'read', timeoutMs: 1000, cancellable: true, concurrencyKey: 'fake', provenance: 'fixture', modelVisibility: true,
  outputPolicy: { maxTextBytes: 1024, maxImageBytes: 4096, maxImages: 1, imageTokenEstimate: 4096 },
});
export function registry(): ToolRegistry { const r = new ToolRegistry(); r.register({ packId: 'test', packVersion: '1', tools: [descriptor(), descriptor('picture'), { ...descriptor('search', ['web_search']), platformPrerequisites: ['search-backend'] }, descriptor('tap', ['screen_control']), descriptor('shell', ['adb_debug'])] }); return r; }
export type Reply = { text?: string; call?: { id?: string; name: string; args?: string }; status?: number; disconnect?: boolean; wait?: boolean; chunkSize?: number; delayMs?: number };
function sse(event: Record<string, unknown>): string { return `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`; }
export function protocolSse(protocol: ModelProfile['protocol'], reply: Reply, index = 0): string {
  const call = reply.call; const text = reply.text ?? '完成中文'; const id = call?.id ?? `call_${index}`;
  if (protocol === 'anthropic-messages') {
    const events: Record<string, unknown>[] = [ { type: 'message_start', message: { id: `msg_${index}`, type: 'message', role: 'assistant', model: 'fixture-model', content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } } ];
    if (call) events.push({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id, name: call.name, input: {} } }, { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: call.args ?? '{"text":"hello"}' } });
    else events.push({ type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }, { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: text.slice(0, 2) } }, { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: text.slice(2) } });
    if (reply.disconnect) return events.map(sse).join('');
    events.push({ type: 'content_block_stop', index: 0 }, { type: 'message_delta', delta: { stop_reason: call ? 'tool_use' : 'end_turn' }, usage: { output_tokens: 4 } }, { type: 'message_stop' });
    return ': heartbeat\n\n' + events.map(sse).join('');
  }
  const item = call ? { type: 'function_call', id: `fc_${id}`, call_id: id, name: call.name, arguments: '', status: 'in_progress' } : { type: 'message', id: `msg_${index}`, role: 'assistant', content: [], status: 'in_progress' };
  const events: Record<string, unknown>[] = [ { type: 'response.created', response: { id: `resp_${index}`, status: 'in_progress' } }, { type: 'response.output_item.added', output_index: 0, item } ];
  if (call) events.push({ type: 'response.function_call_arguments.delta', output_index: 0, item_id: item.id, delta: call.args ?? '{"text":"hello"}' });
  else events.push({ type: 'response.content_part.added', output_index: 0, content_index: 0, item_id: item.id, part: { type: 'output_text', text: '', annotations: [] } }, { type: 'response.output_text.delta', output_index: 0, content_index: 0, item_id: item.id, delta: text.slice(0, 2) }, { type: 'response.output_text.delta', output_index: 0, content_index: 0, item_id: item.id, delta: text.slice(2) });
  if (reply.disconnect) return events.map(sse).join('');
  const doneItem = call ? { ...item, arguments: call.args ?? '{"text":"hello"}', status: 'completed' } : { ...item, content: [{ type: 'output_text', text, annotations: [] }], status: 'completed' };
  events.push({ type: 'response.output_item.done', output_index: 0, item: doneItem }, { type: 'response.completed', response: { id: `resp_${index}`, status: 'completed', output: [doneItem], usage: { input_tokens: 10, output_tokens: 4, total_tokens: 14 } } });
  return ': heartbeat\n\n' + events.map(sse).join('');
}
export function fixture(protocol: ModelProfile['protocol'] = 'openai-responses', replies: Reply[] = [{ text: '完成中文' }]) {
  const p = profile(protocol); const runId = crypto.randomUUID(); const sessionId = 'session-test';
  const lease = { sessionId, runId, leaseId: `lease-${runId}`, capabilityEpoch: 1, expiresAt: Date.now() + 60_000 };
  const snapshot: AuthoritySnapshot = { session: { sessionId, title: 'Test', model: p, enabled: defaultCapabilities(), capabilityEpoch: 1 }, lease, availability: { 'local-test': { status: 'ready' }, 'search-backend': { status: 'backend_missing', reason: 'No search backend configured' } }, l0: { snapshotId: 'l0-1', device: 'Synthetic device', upperApp: { packageName: 'local.test', label: 'Fixture' } } };
  const events: RunEvent[] = []; const contexts: ContextSnapshot[] = []; const runs: AgentRun[] = []; const turns: HistoryTurn[] = []; const executions: ToolEnvelope[] = [];
  const requests: { url: string; headers: Record<string, string>; body: any; envelope: any }[] = [];
  const closedResponses: number[] = [];
  const attachments = new Map<string, Attachment>([['user-image', { attachmentId: 'user-image', mimeType: 'image/png', data: PNG, source: [], observedAt: Date.now(), expiresAt: Date.now() + 60_000 }], ['tool-image', { attachmentId: 'tool-image', mimeType: 'image/png', data: PNG, source: ['screen_perception'], observedAt: Date.now(), expiresAt: Date.now() + 60_000 }]]);
  let networkAborted = 0; let toolImplementation: RuntimePorts['tools']['execute'] | undefined;
  let onRequest: ((index: number) => void | Promise<void>) | undefined;
  const ports: RuntimePorts = {
    authority: { read: async () => structuredClone(snapshot) },
    attachments: { get: async id => structuredClone(attachments.get(id)), put: async a => { attachments.set(a.attachmentId, structuredClone(a)); } },
    persistence: { appendEvent: async e => { events.push(structuredClone(e)); }, commitRun: async (r, e) => { runs.push(structuredClone(r)); events.push(structuredClone(e)); }, saveRun: async r => { runs.push(structuredClone(r)); }, saveContext: async c => { contexts.push(structuredClone(c)); }, saveTurn: async (_, t) => { turns.push(structuredClone(t)); } },
    tools: { execute: async (envelope, signal): Promise<ToolOutcome> => {
      executions.push(structuredClone(envelope)); if (toolImplementation) return toolImplementation(envelope, signal);
      return { content: envelope.toolId === 'test.picture' ? [{ type: 'text', text: 'Synthetic image' }, { type: 'attachment', attachmentId: 'tool-image' }] : [{ type: 'text', text: 'echo-result' }], observedAt: Date.now(), source: 'fixture', truncated: false, outcome: 'observed' };
    } },
    network: { fetch: async (input, init, envelope) => {
      const request = new Request(input, init); const index = requests.length; const reply = replies[index] ?? { text: '完成中文' };
      requests.push({ url: request.url, headers: Object.fromEntries(request.headers), body: await request.json(), envelope });
      await onRequest?.(index);
      if (reply.status) return new Response(JSON.stringify({ error: { message: 'synthetic-http-failure' } }), { status: reply.status, headers: { 'content-type': 'application/json' } });
      const bytes = new TextEncoder().encode(protocolSse(protocol, reply, index)); let offset = 0; let ended = false;
      let streamController: ReadableStreamDefaultController<Uint8Array>;
      const onAbort = () => { networkAborted++; if (!ended) { ended = true; streamController.error(new DOMException('Aborted', 'AbortError')); } };
      request.signal.addEventListener('abort', onAbort, { once: true });
      const body = new ReadableStream<Uint8Array>({ start: c => { streamController = c; if (request.signal.aborted) onAbort(); }, pull: async c => {
        if (reply.wait || ended) return;
        if (reply.delayMs) await new Promise(resolve => setTimeout(resolve, reply.delayMs));
        if (ended) return;
        if (offset >= bytes.length) { ended = true; closedResponses.push(index); c.close(); request.signal.removeEventListener('abort', onAbort); return; }
        const size = reply.chunkSize ?? 7; c.enqueue(bytes.slice(offset, offset + size)); offset += size;
      }, cancel: () => { ended = true; request.signal.removeEventListener('abort', onAbort); } }, { highWaterMark: 1 });
      return new Response(body, { headers: { 'content-type': 'text/event-stream' } });
    } },
  };
  return { ports, snapshot, lease, events, contexts, runs, turns, executions, requests, closedResponses, attachments, get networkAborted() { return networkAborted; },
    setTool: (fn: RuntimePorts['tools']['execute']) => { toolImplementation = fn; }, setOnRequest: (fn: typeof onRequest) => { onRequest = fn; } };
}
