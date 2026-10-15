import { Agent, type AgentEvent, type AgentTool } from '@earendil-works/pi-agent-core';
import type { Message, Model } from '@earendil-works/pi-ai';
import { streamSimple as responses } from '@earendil-works/pi-ai/api/openai-responses';
import { streamSimple as anthropic } from '@earendil-works/pi-ai/api/anthropic-messages';
import { compileContext, project, resolveContent } from './context.js';
import { DEFAULT_LIMITS, RuntimeError, type AgentRun, type AuthoritySnapshot, type Content, type DomainMessage, type EffectRecord, type HistoryTurn, type ModelProfile, type RunEvent, type RunLease, type RunState, type RuntimeLimits, type RuntimePorts, type ToolDescriptor, type ToolEnvelope, type ToolOutcome, type ErrorCode, type Capability } from './contracts.js';
import { ToolRegistry } from './registry.js';
import { ProtocolAudit } from './protocol-audit.js';

const terminal = new Set<RunState>(['completed', 'failed', 'cancelled', 'interrupted']);
const transitions: Record<RunState, RunState[]> = {
  queued: ['preparing', 'cancelled', 'failed'], preparing: ['generating', 'stopping', 'failed'],
  generating: ['executing_tools', 'completed', 'stopping', 'failed'], executing_tools: ['awaiting_user', 'generating', 'completed', 'stopping', 'failed'],
  awaiting_user: ['executing_tools', 'stopping', 'failed'], stopping: ['cancelled', 'interrupted', 'failed'],
  completed: [], failed: [], cancelled: [], interrupted: [],
};
interface Active {
  run: AgentRun; lease: RunLease; profile: ModelProfile; agent?: Agent; controller: AbortController;
  sequence: number; startMono: number; requests: number; requestSnapshot?: AuthoritySnapshot;
  effective: ToolDescriptor[]; seenCalls: Set<string>; source: Set<Capability>; stopReason?: ErrorCode;
  persistenceFailed: boolean; requestMessages: Message[]; resultRefs: Map<string, Content[]>;
  failure?: ErrorCode;
  effects: EffectRecord[];
  turnId: string; streamMessage: number;
}
function fail(code: ErrorCode): never { throw new RuntimeError(code); }
export function assertRuntimeFeatures(): void {
  if (typeof AbortController === 'undefined' || typeof ReadableStream === 'undefined' || typeof TextEncoder === 'undefined' || typeof TextDecoder === 'undefined' || typeof Headers === 'undefined' || typeof Request === 'undefined' || typeof Response === 'undefined' || typeof structuredClone === 'undefined' || !globalThis.crypto?.subtle || !globalThis.crypto?.randomUUID || typeof globalThis.performance?.now !== 'function') throw new RuntimeError('UNSUPPORTED', 'Required browser streaming/crypto APIs unavailable');
}
function checkProfile(p: ModelProfile): void {
  const u = new URL(p.baseUrl);
  if (!['https:', 'http:'].includes(u.protocol) || u.username || u.password || u.search || u.hash) fail('UNSUPPORTED');
  if (!['openai-responses', 'anthropic-messages'].includes(p.protocol) || !p.modelId || !p.credentialRef || p.contextWindow <= p.maxOutput || p.maxOutput < 16 || !Number.isSafeInteger(p.contextWindow) || !Number.isSafeInteger(p.maxOutput)) fail('UNSUPPORTED');
  if (p.lastProbe.revision !== p.revision || !p.lastProbe.textStream || !p.lastProbe.imageInput || !p.lastProbe.toolCalling || !p.lastProbe.imageToolRoundTrip) fail('UNSUPPORTED');
  for (const [h, v] of Object.entries(p.customHeaders ?? {})) if (!/^[a-z0-9-]+$/i.test(h) || /^(host|authorization|x-api-key|cookie|proxy-authorization|content-length|connection)$/i.test(h) || /[\r\n]/.test(v)) fail('UNSUPPORTED');
}
function toModel(p: ModelProfile): Model<any> {
  return { id: p.modelId, name: p.label, api: p.protocol, provider: p.protocol === 'openai-responses' ? 'openai' : 'anthropic', baseUrl: p.baseUrl.replace(/\/+$/, ''),
    reasoning: false, input: ['text', 'image'], contextWindow: p.contextWindow, maxTokens: p.maxOutput,
    // Pi requires a price object. Zero is an internal sentinel, never a displayed price claim.
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, compat: { supportsMidConvoSystemMessages: false } };
}
function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new RuntimeError('CANCELLED'));
    if (signal.aborted) { promise.catch(() => {}); abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
  });
}

/** One coordinator per device. All Pi/model protocol processing lives in this JS module. */
export class ThorRuntime {
  private active?: Active;
  private reserved = false;
  private pending?: { sessionId: string; epoch: number; controller: AbortController; reason?: ErrorCode };
  private consumedRuns = new Map<string, number>();
  private listeners = new Set<(event: RunEvent) => void>();
  private limits: RuntimeLimits;
  constructor(private ports: RuntimePorts, private registry: ToolRegistry, limits: Partial<RuntimeLimits> = {}) {
    this.limits = { ...DEFAULT_LIMITS, ...limits };
    if (Object.values(this.limits).some(v => !Number.isSafeInteger(v) || v < 1)) fail('LIMIT_EXCEEDED');
  }
  subscribe(listener: (event: RunEvent) => void): () => void { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  get currentRun(): AgentRun | undefined { return this.active && structuredClone(this.active.run); }
  stop(): void {
    if (this.pending) { this.pending.reason = 'CANCELLED'; this.pending.controller.abort(); }
    if (this.active && !terminal.has(this.active.run.state)) { this.active.stopReason = 'CANCELLED'; this.active.controller.abort(); this.active.agent?.abort(); }
  }
  /** Call immediately when the Native capability mirror changes, even for OFF -> ON. */
  authorizationChanged(sessionId: string, capabilityEpoch: number): void {
    if (this.pending && this.pending.sessionId === sessionId && capabilityEpoch !== this.pending.epoch) { this.pending.reason = 'STALE_AUTHORIZATION'; this.pending.controller.abort(); }
    const a = this.active;
    if (a && a.run.sessionId === sessionId && capabilityEpoch !== a.lease.capabilityEpoch && !terminal.has(a.run.state)) {
      a.stopReason = 'STALE_AUTHORIZATION'; a.controller.abort(); a.agent?.abort();
    }
  }
  /** Restore only state, never execute or continue an interrupted Run. */
  async recover(run: AgentRun): Promise<AgentRun> {
    const recovered = structuredClone(run);
    if (!terminal.has(recovered.state)) recovered.state = 'interrupted';
    await this.ports.persistence.saveRun(recovered); return recovered;
  }
  private async event(a: Active, type: RunEvent['type'], data: Record<string, unknown>, toolCallId?: string, run?: AgentRun): Promise<void> {
    const e: RunEvent = { sessionId: a.run.sessionId, runId: a.run.runId, eventId: crypto.randomUUID(), sequence: ++a.sequence, wallTime: Date.now(), elapsedMs: Math.round(performance.now() - a.startMono), type, data, ...(toolCallId ? { toolCallId } : {}) };
    await this.persist(a, () => run ? this.ports.persistence.commitRun(structuredClone(run), structuredClone(e)) : this.ports.persistence.appendEvent(structuredClone(e)));
    if (run) a.run = run;
    for (const listener of this.listeners) { try { listener(structuredClone(e)); } catch { /* A view cannot change persisted execution state. */ } }
  }
  private async persist(a: Active, write: () => Promise<void>): Promise<void> {
    try { await write(); } catch { a.persistenceFailed = true; a.stopReason = 'PERSISTENCE_ERROR'; a.controller.abort(); a.agent?.abort(); fail('PERSISTENCE_ERROR'); }
  }
  private async state(a: Active, next: RunState): Promise<void> {
    if (a.run.state === next || terminal.has(a.run.state)) return;
    if (!transitions[a.run.state].includes(next)) fail('PROTOCOL_ERROR');
    const updated = { ...a.run, state: next };
    await this.event(a, 'state', { state: next, ...(a.run.error ? { error: a.run.error } : {}) }, undefined, updated);
  }
  private async authority(a: Active, refresh = false): Promise<AuthoritySnapshot> {
    if (a.controller.signal.aborted) fail(a.stopReason ?? 'CANCELLED');
    const s = await abortable(this.ports.authority.read(a.run.sessionId, a.run.runId, refresh), a.controller.signal);
    if (s.session.sessionId !== a.run.sessionId || s.lease.sessionId !== a.run.sessionId || s.lease.runId !== a.run.runId || s.lease.leaseId !== a.lease.leaseId || s.lease.capabilityEpoch !== a.lease.capabilityEpoch || s.session.capabilityEpoch !== a.lease.capabilityEpoch || s.lease.expiresAt <= Date.now()) fail('STALE_AUTHORIZATION');
    if (a.controller.signal.aborted) fail(a.stopReason ?? 'CANCELLED');
    return s;
  }
  private async toolCheck(a: Active, t: ToolDescriptor): Promise<AuthoritySnapshot> {
    const s = await this.authority(a);
    if (!t.requiredCapabilities.every(c => s.session.enabled[c])) fail('CAPABILITY_DISABLED');
    if (!a.effective.some(x => x.toolId === t.toolId) || !this.registry.effective(s).some(x => x.toolId === t.toolId)) fail('BACKEND_UNAVAILABLE');
    if (s.l0.snapshotId !== a.requestSnapshot?.l0.snapshotId) fail('TARGET_CHANGED');
    return s;
  }
  private tools(a: Active, descriptors: ToolDescriptor[]): AgentTool[] {
    return descriptors.map(t => ({ name: t.modelName, label: t.toolId, description: t.description, parameters: t.inputSchema, replay: 'never', executionMode: 'sequential',
      prepareArguments: args => { this.registry.validate(t.modelName, args); return args as any; },
      execute: async (callId, args, signal) => {
        const s = await this.toolCheck(a, t);
        if (a.seenCalls.has(callId)) fail('PROTOCOL_ERROR');
        a.seenCalls.add(callId); t.requiredCapabilities.forEach(c => a.source.add(c));
        const effect = t.effectClass !== 'read' && t.effectClass !== 'network' ? { toolCallId: callId, toolId: t.toolId, effectClass: t.effectClass, outcome: 'unknown' as const } : undefined;
        if (effect) a.effects.push(effect);
        const deadline = Math.min(Date.now() + t.timeoutMs, a.lease.expiresAt, a.run.startedAt + this.limits.runTimeoutMs);
        const envelope: ToolEnvelope = { bridgeVersion: '0.1', sessionId: a.run.sessionId, runId: a.run.runId, leaseId: a.lease.leaseId, toolCallId: callId, toolId: t.toolId, schemaVersion: t.schemaVersion,
          capabilityEpoch: a.lease.capabilityEpoch, requestId: crypto.randomUUID(), deadline, arguments: args, targetSnapshotId: s.l0.snapshotId, resources: t.resources };
        // Intent commits before the port may perform a side effect.
        await this.event(a, 'tool_intent', { toolId: t.toolId, schemaVersion: t.schemaVersion, capabilityEpoch: a.lease.capabilityEpoch, requestId: envelope.requestId, outcome: 'unknown' }, callId);
        await this.toolCheck(a, t);
        const controller = new AbortController();
        const cancel = () => controller.abort(); a.controller.signal.addEventListener('abort', cancel, { once: true }); signal?.addEventListener('abort', cancel, { once: true });
        let timedOut = false;
        const timer = setTimeout(() => { timedOut = true; controller.abort(); }, Math.max(0, deadline - Date.now()));
        try {
          await this.event(a, 'tool_started', { toolId: t.toolId, arguments: args }, callId);
          await this.toolCheck(a, t);
          const outcome = await abortable(this.ports.tools.execute(envelope, controller.signal, async waiting => { await this.state(a, waiting ? 'awaiting_user' : 'executing_tools'); }), controller.signal);
          if (effect) Object.assign(effect, { outcome: outcome.outcome ?? 'unknown', evidenceRef: outcome.evidenceRef });
          await this.toolCheck(a, t); // Reject late data following revocation, cancellation or target change.
          const bounded = await this.boundOutcome(outcome, t, s);
          a.resultRefs.set(callId, bounded.domain);
          await this.event(a, 'tool_result', { toolId: t.toolId, observedAt: outcome.observedAt, source: outcome.source, truncated: bounded.truncated, error: outcome.error, outcome: outcome.outcome, evidenceRef: outcome.evidenceRef, content: bounded.domain }, callId);
          return { content: bounded.content, details: { observedAt: outcome.observedAt, source: outcome.source, truncated: bounded.truncated, outcome: outcome.outcome ?? 'observed' }, isError: !!outcome.error };
        } catch (error) {
          const code = timedOut ? 'TIMEOUT' : error instanceof RuntimeError ? error.code : 'IO_ERROR';
          if (code === 'PERSISTENCE_ERROR') { a.stopReason = code; a.controller.abort(); }
          await this.event(a, 'tool_result', { toolId: t.toolId, error: code, outcome: 'unknown' }, callId);
          return { content: [{ type: 'text' as const, text: code }], details: { error: code, outcome: 'unknown' }, isError: true, terminate: a.controller.signal.aborted };
        } finally { clearTimeout(timer); a.controller.signal.removeEventListener('abort', cancel); signal?.removeEventListener('abort', cancel); }
      } }));
  }
  private async boundOutcome(outcome: ToolOutcome, t: ToolDescriptor, s: AuthoritySnapshot) {
    let remaining = t.outputPolicy.maxTextBytes; let images = 0; let imageBytes = 0; let truncated = outcome.truncated;
    const domain: Content[] = [];
    for (const c of outcome.content) {
      if (c.type === 'text') {
        const bytes = new TextEncoder().encode(c.text);
        const cut = bytes.subarray(0, remaining);
        const text = new TextDecoder('utf-8', { fatal: false }).decode(cut, { stream: true });
        truncated ||= bytes.byteLength > remaining; remaining -= cut.byteLength; domain.push({ type: 'text', text });
      } else {
        const attachment = await this.ports.attachments.get(c.attachmentId);
        if (attachment) {
          if (t.requiredCapabilities.some(cap => !attachment.source.includes(cap))) fail('PROTOCOL_ERROR');
          images++; imageBytes += Math.ceil(attachment.data.length * 3 / 4);
          if (images > t.outputPolicy.maxImages || imageBytes > t.outputPolicy.maxImageBytes) fail('LIMIT_EXCEEDED');
        }
        domain.push(c);
      }
    }
    return { domain, content: await resolveContent(domain, this.ports.attachments, s.session.enabled), truncated };
  }
  private fetch(a: Active): typeof globalThis.fetch {
    return async (input, init) => {
      await this.authority(a);
      const request = new Request(input, init); const u = new URL(request.url); const base = new URL(a.profile.baseUrl);
      const expectedPath = a.profile.protocol === 'openai-responses' ? `${base.pathname.replace(/\/$/, '')}/responses` : `${base.pathname.replace(/\/$/, '').replace(/\/v1$/, '')}/v1/messages`;
      const allowedQuery = a.profile.protocol === 'anthropic-messages' ? u.search === '?beta=true' || u.search === '' : u.search === '';
      if (request.method !== 'POST' || u.origin !== base.origin || u.pathname !== expectedPath || !allowedQuery) fail('UNSUPPORTED');
      const body = new Uint8Array(await request.clone().arrayBuffer());
      if (body.byteLength > this.limits.maxRequestBytes) fail('LIMIT_EXCEEDED');
      const controller = new AbortController();
      const abort = () => controller.abort(); a.controller.signal.addEventListener('abort', abort, { once: true }); request.signal.addEventListener('abort', abort, { once: true });
      const cleanup = () => { a.controller.signal.removeEventListener('abort', abort); request.signal.removeEventListener('abort', abort); };
      if (a.controller.signal.aborted || request.signal.aborted) controller.abort();
      try {
        const response = await abortable(this.ports.network.fetch(request, { signal: controller.signal, redirect: 'error' }, {
          bridgeVersion: '0.1', purpose: 'model', requestId: crypto.randomUUID(), sessionId: a.run.sessionId, runId: a.run.runId, leaseId: a.lease.leaseId, capabilityEpoch: a.lease.capabilityEpoch,
          profileRef: a.profile.profileId, profileRevision: a.profile.revision, credentialRef: a.profile.credentialRef, credentialPlaceholder: 'sk-thor-native-placeholder', deadline: Math.min(a.lease.expiresAt, a.run.startedAt + this.limits.runTimeoutMs),
        }), controller.signal);
        if (response.redirected || response.status >= 300 && response.status < 400 || response.url && new URL(response.url).origin !== base.origin) fail('UNSUPPORTED');
        if (!response.body) { cleanup(); return response; }
        const reader = response.body.getReader(); let received = 0;
        const limited = new ReadableStream<Uint8Array>({
          pull: async stream => {
            try {
              const next = await abortable(reader.read(), controller.signal);
              if (next.done) { cleanup(); stream.close(); return; }
              received += next.value.byteLength;
              if (received > this.limits.maxResponseBytes) fail('LIMIT_EXCEEDED');
              stream.enqueue(next.value);
            } catch (e) { cleanup(); controller.abort(); void reader.cancel().catch(() => {}); stream.error(e); }
          },
          cancel: async reason => { cleanup(); controller.abort(); await reader.cancel(reason); },
        }, { highWaterMark: 1 });
        const decorate = (result: Response): Response => {
          Object.defineProperty(result, 'url', { value: response.url });
          const clone = result.clone.bind(result);
          Object.defineProperty(result, 'clone', { value: () => decorate(clone()) });
          return result;
        };
        return decorate(new Response(limited, { status: response.status, statusText: response.statusText, headers: response.headers }));
      } catch (e) { cleanup(); controller.abort(); throw e; }
    };
  }
  private async onPi(a: Active, e: AgentEvent): Promise<void> {
    if (terminal.has(a.run.state)) return;
    if (e.type === 'message_start' && e.message.role === 'assistant') a.streamMessage++;
    if (e.type === 'message_update' && !a.controller.signal.aborted) {
      const delta = e.assistantMessageEvent;
      if (delta.type === 'text_delta' || delta.type === 'thinking_delta') {
        await this.event(a, delta.type, { text: delta.delta, blockId: `${a.streamMessage}:${delta.contentIndex}` });
      } else if (delta.type === 'toolcall_start' || delta.type === 'toolcall_delta' || delta.type === 'toolcall_end') {
        const call = delta.partial.content[delta.contentIndex];
        if (call?.type === 'toolCall') await this.event(a, 'tool_call_update', { toolName: call.name, arguments: call.arguments, complete: delta.type === 'toolcall_end' }, call.id);
      }
    }
    if (e.type === 'tool_execution_start' && !a.controller.signal.aborted) await this.state(a, 'executing_tools');
    if (e.type === 'tool_execution_end' && e.isError) await this.event(a, 'diagnostic', { code: 'TOOL_FAILED', toolName: e.toolName }, e.toolCallId);
  }
  async run(lease: RunLease, input: Content[], history: HistoryTurn[] = []): Promise<AgentRun> {
    assertRuntimeFeatures();
    if (this.reserved) fail('BACKEND_UNAVAILABLE');
    lease = structuredClone(lease); input = structuredClone(input); history = structuredClone(history);
    for (const [id, expiry] of this.consumedRuns) if (expiry <= Date.now()) this.consumedRuns.delete(id);
    if (lease.expiresAt <= Date.now()) fail('STALE_AUTHORIZATION');
    if (this.consumedRuns.has(lease.runId)) fail('STALE_AUTHORIZATION');
    this.consumedRuns.set(lease.runId, lease.expiresAt);
    this.reserved = true;
    let a: Active | undefined; let timer: ReturnType<typeof setTimeout> | undefined;
    const pending = { sessionId: lease.sessionId, epoch: lease.capabilityEpoch, controller: new AbortController(), reason: undefined as ErrorCode | undefined };
    this.pending = pending;
    timer = setTimeout(() => { pending.reason = 'TIMEOUT'; if (a) a.stopReason = 'TIMEOUT'; pending.controller.abort(); a?.agent?.abort(); }, Math.max(0, Math.min(this.limits.runTimeoutMs, lease.expiresAt - Date.now())));
    try {
      const initial = await abortable(this.ports.authority.read(lease.sessionId, lease.runId), pending.controller.signal);
      const profile = structuredClone(initial.session.model); checkProfile(profile);
      a = { run: { sessionId: lease.sessionId, runId: lease.runId, state: 'queued', modelProfileRevision: profile.revision, capabilityEpoch: lease.capabilityEpoch, startedAt: Date.now() },
        lease: structuredClone(lease), profile, controller: pending.controller, sequence: 0, startMono: performance.now(), requests: 0, effective: [], seenCalls: new Set(), source: new Set(), persistenceFailed: false, requestMessages: [], resultRefs: new Map(), effects: [], streamMessage: 0, turnId: crypto.randomUUID() };
      this.active = a; const active = a; const model = toModel(profile);
      await this.event(a, 'state', { state: 'queued' }, undefined, a.run); await this.state(a, 'preparing');
      await this.persist(a, () => this.ports.persistence.saveTurn(lease.sessionId, { turnId: active.turnId, runId: lease.runId, messages: [{ kind: 'user', content: input }], source: [], observedAt: Date.now(), complete: false }));
      await this.authority(a);
      const user = await project([{ kind: 'user', content: input }], model, this.ports.attachments, initial);
      let audit = new ProtocolAudit();
      const agent = new Agent({ initialState: { model, thinkingLevel: 'off', messages: [] }, toolExecution: 'sequential', transport: 'sse',
        beforeToolCall: async ({ toolCall }) => audit.permits(toolCall.id, toolCall.arguments) ? undefined : { block: true, reason: 'PROTOCOL_ERROR: incomplete or conflicting JSON', terminate: true },
        prepareRequest: async request => {
          try {
          const s = await this.authority(active, true);
          if (++active.requests > this.limits.maxRequests) fail('LIMIT_EXCEEDED');
          active.requestSnapshot = s; active.effective = this.registry.effective(s);
          if (s.session.enabled.screen_perception && (s.l0.device || s.l0.upperApp)) active.source.add('screen_perception');
          audit = new ProtocolAudit();
          const tools = this.tools(active, active.effective);
          // Refresh image references on every request; expired/deleted/disabled data never survives
          // merely because Pi still holds an earlier in-memory image block.
          const current: Message[] = [];
          for (const m of request.context.messages) {
            if (m.role === 'system') continue;
            if (m.role === 'user') current.push({ ...m, content: await resolveContent(input, this.ports.attachments, s.session.enabled) });
            else if (m.role === 'toolResult' && active.resultRefs.has(m.toolCallId)) current.push({ ...m, content: await resolveContent(active.resultRefs.get(m.toolCallId)!, this.ports.attachments, s.session.enabled) });
            else current.push(m);
          }
          const compiled = await compileContext({ snapshot: { ...s, session: { ...s.session, model: profile } }, tools: active.effective, registryVersion: this.registry.version, model,
            history, current, currentRefs: [...input, ...[...active.resultRefs.values()].flat()].filter(c => c.type === 'attachment').map(c => c.attachmentId), attachments: this.ports.attachments, imageTokens: this.limits.imageTokenEstimate, maxHistoryTurns: this.limits.maxHistoryTurns });
          active.requestMessages = compiled.messages;
          await this.persist(active, () => this.ports.persistence.saveContext(compiled.manifest)); await this.event(active, 'context', { snapshotId: compiled.manifest.snapshotId, estimatedTokens: compiled.manifest.estimatedTokens, droppedTurnIds: compiled.manifest.droppedTurnIds });
          await this.state(active, 'generating');
          // Keep history out of Agent's evolving transcript; inject the bounded projection only at convertToLlm.
          return { context: { messages: request.context.messages.filter(m => m.role !== 'system'), tools } };
          } catch (e) { active.failure = e instanceof RuntimeError ? e.code : 'IO_ERROR'; throw e; }
        },
        convertToLlm: () => active.requestMessages,
        streamFn: (_model, context, options) => {
          const common = { ...options, onProviderStreamEvent: (event: unknown) => audit.observe(profile.protocol, event), apiKey: 'sk-thor-native-placeholder', fetch: this.fetch(active), transport: 'sse' as const, maxTokens: profile.maxOutput, maxRetries: 0, timeoutMs: this.limits.runTimeoutMs, cacheRetention: 'none' as const, headers: profile.customHeaders, env: {} };
          return profile.protocol === 'openai-responses' ? responses(model, context, common) : anthropic(model, context, common);
        },
      });
      a.agent = agent;
      const abortAgent = () => agent.abort(); a.controller.signal.addEventListener('abort', abortAgent, { once: true });
      agent.subscribe(e => this.onPi(active, e));
      await agent.prompt(user);
      if (a.persistenceFailed) fail('PERSISTENCE_ERROR');
      const assistant = agent.state.messages.filter(m => m.role === 'assistant').at(-1);
      const saveIncomplete = () => this.persist(active, () => this.ports.persistence.saveTurn(lease.sessionId, { turnId: active.turnId, runId: lease.runId, messages: [{ kind: 'user', content: input }], source: [...active.source], observedAt: Date.now(), complete: false, effects: active.effects }));
      if (a.controller.signal.aborted) {
        await saveIncomplete();
        a.run.error = a.stopReason ?? 'CANCELLED'; await this.state(a, 'stopping');
        if (a.stopReason === 'STALE_AUTHORIZATION') await this.event(a, 'authorization_changed', { previousEpoch: a.lease.capabilityEpoch });
        await this.state(a, a.stopReason === 'TIMEOUT' || a.stopReason === 'PERSISTENCE_ERROR' ? 'failed' : 'cancelled');
      } else if (!assistant || assistant.role !== 'assistant' || assistant.stopReason === 'error' || assistant.stopReason === 'aborted' || assistant.stopReason === 'length') {
        await saveIncomplete();
        // Do not persist upstream error bodies/headers that may echo credentials.
        a.run.error = a.failure ?? 'IO_ERROR'; await this.state(a, 'failed');
      } else {
        const messages: DomainMessage[] = [{ kind: 'user', content: input }];
        for (const m of agent.state.messages) {
          if (m.role === 'assistant') messages.push({ kind: 'assistant', text: m.content.filter(c => c.type === 'text').map(c => c.text).join(''), calls: m.content.filter(c => c.type === 'toolCall').map(c => ({ id: c.id, name: c.name, arguments: c.arguments })) });
          if (m.role === 'toolResult') messages.push({ kind: 'tool_result', toolCallId: m.toolCallId, toolName: m.toolName, content: a.resultRefs.get(m.toolCallId) ?? m.content.filter(c => c.type === 'text').map(c => ({ type: 'text', text: c.text })), isError: m.isError });
        }
        await this.persist(a, () => this.ports.persistence.saveTurn(lease.sessionId, { turnId: active.turnId, runId: lease.runId, messages, source: [...active.source], observedAt: Date.now(), complete: true, effects: active.effects }));
        await this.state(a, 'completed');
      }
      return structuredClone(a.run);
    } catch (error) {
      if (!a) throw pending.reason ? new RuntimeError(pending.reason) : error;
      const code = error instanceof RuntimeError ? error.code : 'IO_ERROR';
      a.controller.abort(); a.agent?.abort(); a.run.error = code;
      // A broken durable store cannot confirm a terminal transition. Surface the failure,
      // retain the last committed nonterminal state for interrupted recovery, and stop work.
      if (a.persistenceFailed) throw new RuntimeError('PERSISTENCE_ERROR');
      if (code === 'CANCELLED' || code === 'STALE_AUTHORIZATION') {
        await this.state(a, 'stopping'); await this.state(a, 'cancelled');
      } else await this.state(a, 'failed');
      return structuredClone(a.run);
    } finally { if (timer) clearTimeout(timer); this.active = undefined; this.pending = undefined; this.reserved = false; }
  }
}
