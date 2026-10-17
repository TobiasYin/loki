import { Agent, type AgentEvent, type AgentTool, type AgentOptions } from '@earendil-works/pi-agent-core';
import type { Message, Model } from '@earendil-works/pi-ai';
import { streamSimple as responses } from '@earendil-works/pi-ai/api/openai-responses';
import { streamSimple as completions } from '@earendil-works/pi-ai/api/openai-completions';
import { streamSimple as anthropic } from '@earendil-works/pi-ai/api/anthropic-messages';
import { compileContext, project, projectHistory, resolveContent } from './context.js';
import { DEFAULT_LIMITS, isErrorCode, RuntimeError, type AgentRun, type AuthoritySnapshot, type Content, type DomainMessage, type EffectRecord, type HistoryTurn, type ModelProfile, type RunEvent, type RunLease, type RunState, type RuntimeLimits, type RuntimePorts, type ToolDescriptor, type ToolEnvelope, type ToolOutcome, type ErrorCode, type Capability } from './contracts.js';
import { ToolRegistry } from './registry.js';
import { ProtocolAudit } from './protocol-audit.js';
import { RuntimeSession, closeInterruptedRound } from './session.js';
import { withoutProviderDeadline } from './provider-timeouts.js';
import { StreamEvents } from './stream-events.js';
import { checkProfile } from './profile-validation.js';
import { providerStopBoundary } from './provider-stop.js';
import { unavailableToolReason } from './tool-availability.js';
type WaitPhase = 'agent_prompt' | 'provider_next' | 'provider_result' | 'request_body' | 'authority' | 'persist' | 'network_headers' | 'network_read';

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
  stream?: StreamEvents; persistenceFailed: boolean; requestMessages: Message[]; resultRefs: Map<string, Content[]>;
  failure?: ErrorCode;
  observationRequired: boolean;
  waits: Map<number, { phase: WaitPhase; since: number }>; waitSequence: number; lastProgress: number;
  effects: EffectRecord[];
  turnId: string; streamMessage: number; messageOffset: number; input: Content[];
}
const currentScreenReads = new Set(['perception.app_info', 'perception.window_info', 'perception.ui_tree', 'perception.capture_screen']);
const screenResources = new Set(['selected-screen/snapshot', 'upper-display/snapshot']);
function dependsOnScreen(t: ToolDescriptor): boolean { return t.effectClass === 'input' && t.resources.some(r => screenResources.has(r)); }
function isCurrentScreenRead(t: ToolDescriptor): boolean {
  return t.effectClass === 'read' && t.requiredCapabilities.includes('screen_perception') && currentScreenReads.has(t.toolId) && t.resources.some(r => screenResources.has(r));
}
function toolOutcomeError(value: unknown): ErrorCode | undefined {
  if (value === undefined || value === null) return undefined;
  return isErrorCode(value) ? value : 'PROTOCOL_ERROR';
}
function fail(code: ErrorCode): never { throw new RuntimeError(code); }
export function assertRuntimeFeatures(): void {
  if (typeof AbortController === 'undefined' || typeof ReadableStream === 'undefined' || typeof TextEncoder === 'undefined' || typeof TextDecoder === 'undefined' || typeof Headers === 'undefined' || typeof Request === 'undefined' || typeof Response === 'undefined' || typeof structuredClone === 'undefined' || !globalThis.crypto?.subtle || !globalThis.crypto?.randomUUID || typeof globalThis.performance?.now !== 'function') throw new RuntimeError('UNSUPPORTED', 'Required browser streaming/crypto APIs unavailable');
}
function toModel(p: ModelProfile): Model<any> {
  return { id: p.modelId, name: p.label, api: p.protocol, provider:p.provider==='opencode-go'?'opencode-go':p.provider==='deepseek'?'deepseek':p.protocol==='anthropic-messages'?'anthropic':'openai', baseUrl: p.baseUrl.replace(/\/+$/, ''),
    reasoning: p.reasoning??(p.thinkingEffort!==undefined && p.thinkingEffort!=='off'), thinkingLevelMap:p.thinkingLevelMap as Model<any>['thinkingLevelMap'], input:p.input??['text','image'], contextWindow: p.contextWindow, maxTokens: p.maxOutput,
    // Pi requires a price object. Zero is an internal sentinel, never a displayed price claim.
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, compat: { ...p.compat, supportsMidConvoSystemMessages: false } };
}
function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new RuntimeError('CANCELLED'));
    if (signal.aborted) { promise.catch(() => {}); abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
  });
}

/** One coordinator per active run. Native owns cross-run authorization and device serialization. */
export class ThorRuntime {
  private active?: Active;
  private reserved = false;
  private pending?: { sessionId: string; epoch: number; controller: AbortController; reason?: ErrorCode };
  private consumedRuns = new Map<string, number>();
  private listeners = new Set<(event: RunEvent) => void>();
  private limits: RuntimeLimits;
  constructor(private ports: RuntimePorts, private registry: ToolRegistry, limits: Partial<RuntimeLimits> = {}, private session?:RuntimeSession) {
    this.limits = { ...DEFAULT_LIMITS, ...limits };
    if (Object.entries(this.limits).some(([key,v]) => !Number.isSafeInteger(v) || v < (key==='runTimeoutMs'?0:1))) fail('LIMIT_EXCEEDED');
  }
  subscribe(listener: (event: RunEvent) => void): () => void { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  get currentRun(): AgentRun | undefined { return this.active && structuredClone(this.active.run); }
  /** Fixed local labels and numeric timing only; never provider payloads or credentials. */
  diagnostics() {
    const a = this.active, now = performance.now();
    return { reserved: this.reserved, preparing: !!this.pending && !a, ...(a ? {
      state: a.run.state, stopRequested: a.controller.signal.aborted, requests: a.requests,
      eventCount: a.sequence, toolCallCount: a.seenCalls.size, effectCount: a.effects.length,
      sinceProgressMs: Math.max(0, Math.round(now - a.lastProgress)),
      piStreaming: !!a.agent?.state.isStreaming, pendingToolCount: a.agent?.state.pendingToolCalls.size ?? 0,
      waits: [...a.waits.values()].slice(0, 32).map(w => ({phase:w.phase, ageMs:Math.max(0,Math.round(now-w.since))})),
    } : {}) };
  }
  private async watch<T>(a:Active, phase:WaitPhase, work:()=>Promise<T>):Promise<T> {
    const id=++a.waitSequence; a.waits.set(id,{phase,since:performance.now()});
    try {return await work();} finally {a.waits.delete(id);}
  }
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
    await a.stream?.flush();
    await this.writeEvent(a,type,data,toolCallId,run);
  }
  private async writeEvent(a: Active, type: RunEvent['type'], data: Record<string, unknown>, toolCallId?: string, run?: AgentRun): Promise<void> {
    const e: RunEvent = { sessionId: a.run.sessionId, runId: a.run.runId, eventId: crypto.randomUUID(), sequence: ++a.sequence, wallTime: Date.now(), elapsedMs: Math.round(performance.now() - a.startMono), type, data, ...(toolCallId ? { toolCallId } : {}) };
    await this.persist(a, () => run ? this.ports.persistence.commitRun(structuredClone(run), structuredClone(e)) : this.ports.persistence.appendEvent(structuredClone(e)));
    a.lastProgress = performance.now();
    if (run) a.run = run;
    for (const listener of this.listeners) { try { listener(structuredClone(e)); } catch { /* A view cannot change persisted execution state. */ } }
  }
  private async persist(a: Active, write: () => Promise<void>): Promise<void> {
    try { await this.watch(a, 'persist', write); } catch { a.persistenceFailed = true; a.stopReason = 'PERSISTENCE_ERROR'; a.controller.abort(); a.agent?.abort(); fail('PERSISTENCE_ERROR'); }
  }
  private async state(a: Active, next: RunState): Promise<void> {
    if (a.run.state === next || terminal.has(a.run.state)) return;
    if (!transitions[a.run.state].includes(next)) fail('PROTOCOL_ERROR');
    const updated = { ...a.run, state: next };
    await this.event(a, 'state', { state: next, ...(a.run.error ? { error: a.run.error } : {}) }, undefined, updated);
  }
  private async authority(a: Active, refresh = false): Promise<AuthoritySnapshot> {
    if (a.controller.signal.aborted) fail(a.stopReason ?? 'CANCELLED');
    const s = await this.watch(a, 'authority', () => abortable(this.ports.authority.read(a.run.sessionId, a.run.runId, refresh), a.controller.signal));
    if (s.session.sessionId !== a.run.sessionId || s.lease.sessionId !== a.run.sessionId || s.lease.runId !== a.run.runId || s.lease.leaseId !== a.lease.leaseId || s.lease.capabilityEpoch !== a.lease.capabilityEpoch || s.session.capabilityEpoch !== a.lease.capabilityEpoch || s.lease.expiresAt <= Date.now()) fail('STALE_AUTHORIZATION');
    if (a.controller.signal.aborted) fail(a.stopReason ?? 'CANCELLED');
    return s;
  }
  private async toolCheck(a: Active, t: ToolDescriptor): Promise<AuthoritySnapshot> {
    const s = await this.authority(a);
    if (!t.requiredCapabilities.every(c => s.session.enabled[c])) fail('CAPABILITY_DISABLED');
    if (!a.effective.some(x => x.toolId === t.toolId)) fail('BACKEND_UNAVAILABLE');
    if (!this.registry.effective(s).some(x => x.toolId === t.toolId)) fail(unavailableToolReason(t, s));
    if (s.l0.snapshotId !== a.requestSnapshot?.l0.snapshotId) fail('TARGET_CHANGED');
    if (a.observationRequired && dependsOnScreen(t)) fail('TARGET_CHANGED');
    return s;
  }
  private async toolResultCheck(a: Active, t: ToolDescriptor, checkReadSource = true): Promise<AuthoritySnapshot> {
    const s = await this.authority(a);
    if (!t.requiredCapabilities.every(c => s.session.enabled[c])) fail('CAPABILITY_DISABLED');
    // An action can legitimately navigate, replace its old window or make its
    // own backend unavailable. Those are execution preconditions, not grounds
    // to discard Native's receipt. Pure screen reads still cannot deliver data
    // after their request source is replaced by an unrelated observation.
    if (checkReadSource && t.effectClass === 'read' && t.requiredCapabilities.includes('screen_perception') && s.l0.snapshotId !== a.requestSnapshot?.l0.snapshotId) fail('TARGET_CHANGED');
    return s;
  }
  private tools(a: Active, descriptors: ToolDescriptor[]): AgentTool[] {
    return descriptors.map(t => ({ name: t.modelName, label: t.toolId, description: t.description, parameters: t.inputSchema, replay: 'never', executionMode: 'sequential',
      prepareArguments: args => { this.registry.validate(t.modelName, args); return args as any; },
      execute: async (callId, args, signal) => {
        const deadline = Math.min(Date.now() + t.timeoutMs, a.lease.expiresAt, this.limits.runTimeoutMs ? a.run.startedAt + this.limits.runTimeoutMs : Number.MAX_SAFE_INTEGER);
        const controller = new AbortController();
        const cancel = () => controller.abort(); a.controller.signal.addEventListener('abort', cancel, { once: true }); signal?.addEventListener('abort', cancel, { once: true });
        if (a.controller.signal.aborted || signal?.aborted) controller.abort();
        let timedOut = false, dispatched = false;
        let effect: EffectRecord | undefined;
        let receipt: { outcome: NonNullable<ToolOutcome['outcome']>; evidenceRef?: string } | undefined;
        let targetChangedReason: 'input_focus_mismatch' | undefined;
        const timer = setTimeout(() => { timedOut = true; controller.abort(); }, Math.max(0, deadline - Date.now()));
        try {
          // Preflight failures must return through the same durable tool-result
          // path as Native rejections, rather than becoming an opaque SDK error.
          if (a.seenCalls.has(callId)) fail('PROTOCOL_ERROR');
          a.seenCalls.add(callId);
          const s = await abortable(this.toolCheck(a, t), controller.signal);
          t.requiredCapabilities.forEach(c => a.source.add(c));
          effect = t.effectClass !== 'read' && t.effectClass !== 'network' ? { toolCallId: callId, toolId: t.toolId, effectClass: t.effectClass, outcome: 'unknown' } : undefined;
          if (effect) a.effects.push(effect);
          const envelope: ToolEnvelope = { bridgeVersion: '0.1', sessionId: a.run.sessionId, runId: a.run.runId, leaseId: a.lease.leaseId, toolCallId: callId, toolId: t.toolId, schemaVersion: t.schemaVersion,
            capabilityEpoch: a.lease.capabilityEpoch, requestId: crypto.randomUUID(), deadline, arguments: args, targetSnapshotId: s.l0.snapshotId, resources: t.resources };
          // Intent commits before the port may perform a side effect.
          await this.event(a, 'tool_intent', { toolId: t.toolId, schemaVersion: t.schemaVersion, capabilityEpoch: a.lease.capabilityEpoch, requestId: envelope.requestId, outcome: 'unknown' }, callId);
          await abortable(this.toolCheck(a, t), controller.signal);
          await this.event(a, 'tool_started', { toolId: t.toolId, arguments: args }, callId);
          await abortable(this.toolCheck(a, t), controller.signal);
          if (controller.signal.aborted) fail(timedOut ? 'TIMEOUT' : 'CANCELLED');
          if (Date.now() >= deadline) fail('TIMEOUT');
          dispatched = true;
          const outcome = await abortable(this.ports.tools.execute(envelope, controller.signal, async waiting => { await this.state(a, waiting ? 'awaiting_user' : 'executing_tools'); }), controller.signal);
          receipt = { outcome: outcome.outcome ?? 'unknown', evidenceRef: outcome.evidenceRef };
          if (effect) Object.assign(effect, receipt);
          const outcomeError = toolOutcomeError(outcome.error);
          const rejectedScreenRead = t.effectClass === 'read' && t.requiredCapabilities.includes('screen_perception') && outcomeError !== undefined;
          // A rejected read has no body to admit. Recheck authority/capabilities
          // first, then preserve Native's fixed refusal instead of masking it
          // with a source change. Successful reads keep both source checks.
          const current = await abortable(this.toolResultCheck(a, t, !rejectedScreenRead), controller.signal);
          if (rejectedScreenRead) fail(outcomeError!);
          if (outcomeError === 'TARGET_CHANGED') {
            if (outcome.targetChangedReason === 'input_focus_mismatch' &&
                ['input.press_button', 'input.set_axes', 'input.navigate', 'input.key_press'].includes(t.toolId) && t.effectClass === 'input') targetChangedReason = 'input_focus_mismatch';
            fail('TARGET_CHANGED');
          }
          if (outcomeError === 'PROTOCOL_ERROR' && outcome.error !== 'PROTOCOL_ERROR') fail('PROTOCOL_ERROR');
          const bounded = await abortable(this.boundOutcome(outcome, t, current), controller.signal);
          // Attachment resolution is asynchronous too: revocation or stop while
          // unpacking must not admit its text/images into the transcript.
          await abortable(this.toolResultCheck(a, t), controller.signal);
          a.resultRefs.set(callId, bounded.domain);
          await this.event(a, 'tool_result', { toolId: t.toolId, observedAt: outcome.observedAt, source: outcome.source, truncated: bounded.truncated, error: outcomeError, outcome: outcome.outcome, evidenceRef: outcome.evidenceRef, content: bounded.domain }, callId);
          if (outcomeError === undefined) {
            if (t.toolId === 'perception.select_screen') a.observationRequired = true;
            else if (isCurrentScreenRead(t)) a.observationRequired = false;
          }
          return { content: bounded.content, details: { observedAt: outcome.observedAt, source: outcome.source, truncated: bounded.truncated, outcome: outcome.outcome ?? 'observed' }, isError: outcomeError !== undefined };
        } catch (error) {
          const code = timedOut ? 'TIMEOUT' : a.controller.signal.aborted ? a.stopReason ?? 'CANCELLED' : error instanceof RuntimeError ? error.code : 'IO_ERROR';
          if (code === 'PERSISTENCE_ERROR') { a.stopReason = code; a.controller.abort(); }
          // Revocation withholds content, but cannot undo a submitted action.
          // Only a receipt already received before this rejection is retained;
          // uncooperative executors never write events after terminal state.
          const recovery = code === 'TARGET_CHANGED' ? {
            kind: 'observe_again', continueRun: true, retryPreviousAction: false, discardPreviousTarget: true,
            submission: !dispatched ? 'not_dispatched' : receipt?.outcome === 'submitted' ? 'submitted' : 'unknown',
            sideEffectPossible: t.effectClass !== 'read' && t.effectClass !== 'network',
            ...(targetChangedReason ? { reason: targetChangedReason } : {}),
            instruction: 'The page or target changed. Continue this Run by observing the current selected screen before deciding the next action. Discard old coordinates, frame IDs and node refs. Do not replay the previous action. Unknown submission is not proof it did not execute.' +
              (targetChangedReason ? ' The authorized target does not currently own the required input focus. Repeated screenshots alone do not restore input focus. First observe the current windows and focus; if still permitted by the task and current guards, use an existing legal app launch or a touch on the selected screen to restore focus, then observe again before deciding a new action. Do not change focus locks or permissions, auto-focus, or automatically retry the rejected input.' : ''),
          } : undefined;
          if (recovery) a.observationRequired = true;
          const result = { error: code, outcome: receipt?.outcome ?? 'unknown', evidenceRef: receipt?.evidenceRef, contentDiscarded: receipt !== undefined, ...(recovery ? { recovery } : {}) };
          const content = [{ type: 'text' as const, text: recovery ? JSON.stringify(result) : code }];
          if (recovery) a.resultRefs.set(callId, content);
          await this.event(a, 'tool_result', { toolId: t.toolId, ...result, ...(recovery ? { content } : {}) }, callId);
          return { content, details: result, isError: true, terminate: a.controller.signal.aborted || !!signal?.aborted };
        } finally { clearTimeout(timer); a.controller.signal.removeEventListener('abort', cancel); signal?.removeEventListener('abort', cancel); }
      } }));
  }
  private async boundOutcome(outcome: ToolOutcome, t: ToolDescriptor, s: AuthoritySnapshot) {
    let remaining = t.outputPolicy.maxTextBytes; let images = 0; let imageBytes = 0; let truncated = outcome.truncated;
    const domain: Content[] = [];
    for (const c of outcome.content) {
      if (c.type === 'text') {
        if (typeof c.text !== 'string') fail('PROTOCOL_ERROR');
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
      const expectedPath = a.profile.protocol === 'openai-responses' ? `${base.pathname.replace(/\/$/, '')}/responses` : a.profile.protocol==='openai-completions' ? `${base.pathname.replace(/\/$/,'')}/chat/completions` : `${base.pathname.replace(/\/$/, '').replace(/\/v1$/, '')}/v1/messages`;
      const allowedQuery = a.profile.protocol === 'anthropic-messages' ? u.search === '?beta=true' || u.search === '' : u.search === '';
      if (request.method !== 'POST' || u.origin !== base.origin || u.pathname !== expectedPath || !allowedQuery) fail('UNSUPPORTED');
      const body = new Uint8Array(await this.watch(a, 'request_body', () => abortable(request.clone().arrayBuffer(), a.controller.signal)));
      if (body.byteLength > this.limits.maxRequestBytes) fail('LIMIT_EXCEEDED');
      const controller = new AbortController();
      const abort = () => controller.abort(); a.controller.signal.addEventListener('abort', abort, { once: true }); request.signal.addEventListener('abort', abort, { once: true });
      const cleanup = () => { a.controller.signal.removeEventListener('abort', abort); request.signal.removeEventListener('abort', abort); };
      if (a.controller.signal.aborted || request.signal.aborted) controller.abort();
      try {
        const response = await this.watch(a, 'network_headers', () => abortable(this.ports.network.fetch(request, { signal: controller.signal, redirect: 'error' }, {
          bridgeVersion: '0.1', purpose: 'model', requestId: crypto.randomUUID(), sessionId: a.run.sessionId, runId: a.run.runId, leaseId: a.lease.leaseId, capabilityEpoch: a.lease.capabilityEpoch,
          profileRef: a.profile.profileId, profileRevision: a.profile.revision, credentialRef: a.profile.credentialRef, credentialPlaceholder: 'sk-thor-native-placeholder', deadline: Math.min(a.lease.expiresAt, this.limits.runTimeoutMs ? a.run.startedAt + this.limits.runTimeoutMs : Number.MAX_SAFE_INTEGER),
        }), controller.signal));
        if (response.redirected || response.status >= 300 && response.status < 400 || response.url && new URL(response.url).origin !== base.origin) fail('UNSUPPORTED');
        if (!response.body) { cleanup(); return response; }
        const reader = response.body.getReader(); let received = 0;
        const limited = new ReadableStream<Uint8Array>({
          pull: async stream => {
            try {
              const next = await this.watch(a, 'network_read', () => abortable(reader.read(), controller.signal));
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
  private async saveTurn(a:Active,complete:boolean):Promise<void> {
    await a.stream?.flush();
    const messages:DomainMessage[]=[];
    for(const m of a.agent?.state.messages.filter(m=>m.role!=='system').slice(a.messageOffset)??[]) {
      if(m.role==='user')messages.push({kind:'user',content:a.input});
      else if(m.role==='assistant') {
        // Partial JSON is diagnostic data, never an executable historical tool call.
        const calls=m.content.filter(c=>c.type==='toolCall').filter(c=>m.stopReason==='toolUse' || a.seenCalls.has(c.id)).map(c=>({id:c.id,name:c.name,arguments:c.arguments}));
        const text=m.content.filter(c=>c.type==='text').map(c=>c.text).join('');
        if(text || calls.length)messages.push({kind:'assistant',text,calls});
      } else if(m.role==='toolResult')messages.push({kind:'tool_result',toolCallId:m.toolCallId,toolName:m.toolName,
        content:a.resultRefs.get(m.toolCallId)??m.content.filter(c=>c.type==='text').map(c=>({type:'text',text:c.text})),isError:m.isError});
    }
    if(!messages.some(m=>m.kind==='user'))messages.unshift({kind:'user',content:a.input});
    await this.persist(a,()=>this.ports.persistence.saveTurn(a.run.sessionId,{turnId:a.turnId,runId:a.run.runId,messages:closeInterruptedRound(messages),source:[...a.source],observedAt:Date.now(),complete,effects:a.effects}));
  }
  private async onPi(a: Active, e: AgentEvent): Promise<void> {
    if (terminal.has(a.run.state)) return;
    if (e.type !== 'message_update') await a.stream?.flush();
    if (e.type === 'message_start' && e.message.role === 'assistant') a.streamMessage++;
    if (e.type === 'message_update' && !a.controller.signal.aborted) {
      const delta = e.assistantMessageEvent;
      if (delta.type === 'text_delta' || delta.type === 'thinking_delta') {
        await a.stream!.push({type:delta.type,data:{ text: delta.delta, blockId: `${a.streamMessage}:${delta.contentIndex}` }});
      } else if (delta.type === 'toolcall_start' || delta.type === 'toolcall_delta' || delta.type === 'toolcall_end') {
        const call = delta.partial.content[delta.contentIndex];
        if (call?.type === 'toolCall') await a.stream!.push({type:'tool_call_update',data:{ toolName: call.name, arguments: call.arguments, complete: delta.type === 'toolcall_end' },toolCallId:call.id});
      }
    }
    if(e.type==='message_end' && !a.persistenceFailed)await this.saveTurn(a,false);
    if (e.type === 'tool_execution_start' && !a.controller.signal.aborted) await this.state(a, 'executing_tools');
    // A tool-local timeout/rejection can finish while its Native approval RPC is
    // still unwinding. Leave awaiting_user before Pi starts its next model turn.
    if(e.type==='tool_execution_end' && a.run.state==='awaiting_user' && !a.controller.signal.aborted)await this.state(a,'executing_tools');
    if (e.type === 'tool_execution_end' && e.isError) await this.event(a, 'diagnostic', { code: e.result.details?.recovery?.kind === 'observe_again' ? 'TARGET_CHANGED' : 'TOOL_FAILED', toolName: e.toolName }, e.toolCallId);
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
    if(this.limits.runTimeoutMs)timer = setTimeout(() => { pending.reason = 'TIMEOUT'; if (a) a.stopReason = 'TIMEOUT'; pending.controller.abort(); a?.agent?.abort(); }, Math.max(0, Math.min(this.limits.runTimeoutMs, lease.expiresAt - Date.now())));
    try {
      const initial = await abortable(this.ports.authority.read(lease.sessionId, lease.runId), pending.controller.signal);
      const profile = structuredClone(initial.session.model); checkProfile(profile);
      a = { run: { sessionId: lease.sessionId, runId: lease.runId, state: 'queued', modelProfileRevision: profile.revision, capabilityEpoch: lease.capabilityEpoch, startedAt: Date.now() },
        lease: structuredClone(lease), profile, controller: pending.controller, sequence: 0, startMono: performance.now(), requests: 0, effective: [], seenCalls: new Set(), source: new Set(), persistenceFailed: false, requestMessages: [], resultRefs: new Map(), effects: [], observationRequired:false, waits:new Map(),waitSequence:0,lastProgress:performance.now(), streamMessage: 0, messageOffset:0, input, turnId: crypto.randomUUID() };
      this.active = a; const active = a; const model = toModel(profile);
      active.stream=new StreamEvents(f=>this.writeEvent(active,f.type,f.data,f.toolCallId));
      if(initial.session.enabled.screen_perception && (initial.l0.device || initial.l0.upperApp))a.source.add('screen_perception');
      await this.event(a, 'state', { state: 'queued' }, undefined, a.run); await this.state(a, 'preparing');
      await this.persist(a, () => this.ports.persistence.saveTurn(lease.sessionId, { turnId: active.turnId, runId: lease.runId, messages: [{ kind: 'user', content: input }], source: [...active.source], observedAt: Date.now(), complete: false }));
      await this.authority(a);
      const user = await project([{ kind: 'user', content: input }], model, this.ports.attachments, initial);
      let audit = new ProtocolAudit();
      if(this.session && this.session.sessionId!==lease.sessionId)fail('STALE_AUTHORIZATION');
      const previous=this.session?.agent;
      const restored=previous?[]:await projectHistory(history.slice(-this.limits.maxHistoryTurns).flatMap(t=>t.source.every(c=>initial.session.enabled[c])?closeInterruptedRound(t.messages):t.messages.filter(m=>m.kind==='user')),model,this.ports.attachments,initial);
      active.messageOffset=previous?.state.messages.filter(m=>m.role!=='system').length??restored.length;
      const agentOptions:AgentOptions={initialState:{model,thinkingLevel:profile.thinkingEffort??'off',messages:restored},sessionId:lease.sessionId,toolExecution:'sequential',transport:'sse',
        beforeToolCall: async ({ toolCall }) => audit.permits(toolCall.id, toolCall.arguments) ? undefined : { block: true, reason: 'PROTOCOL_ERROR: incomplete or conflicting JSON', terminate: true },
        prepareRequest: async request => {
          try {
          const s = await this.authority(active, true);
          if (++active.requests > this.limits.maxRequests) fail('LIMIT_EXCEEDED');
          active.requestSnapshot = s; active.effective = profile.supportsTools === false ? [] : this.registry.effective(s);
          if (s.session.enabled.screen_perception && (s.l0.device || s.l0.upperApp)) active.source.add('screen_perception');
          audit = new ProtocolAudit();
          const tools = this.tools(active, active.effective);
          // Refresh image references on every request; expired/deleted/disabled data never survives
          // merely because Pi still holds an earlier in-memory image block.
          const current: Message[] = [];
          for (const m of request.context.messages.filter(m=>m.role!=='system').slice(active.messageOffset)) {
            if (m.role === 'user') current.push({ ...m, content: await resolveContent(input, this.ports.attachments, s.session.enabled) });
            else if (m.role === 'toolResult' && active.resultRefs.has(m.toolCallId)) current.push({ ...m, content: await resolveContent(active.resultRefs.get(m.toolCallId)!, this.ports.attachments, s.session.enabled) });
            else current.push(m);
          }
          const compiled = await compileContext({ snapshot: { ...s, session: { ...s.session, model: profile } }, tools: active.effective, registryVersion: this.registry.version, model,
            history, current, currentRefs: [...input, ...[...active.resultRefs.values()].flat()].filter(c => c.type === 'attachment').map(c => c.attachmentId), attachments: this.ports.attachments, imageTokens: this.limits.imageTokenEstimate, maxHistoryTurns: this.limits.maxHistoryTurns });
          active.requestMessages = compiled.messages;
          await this.persist(active, () => this.ports.persistence.saveContext(compiled.manifest)); await this.event(active, 'context', { snapshotId: compiled.manifest.snapshotId, estimatedTokens: compiled.manifest.estimatedTokens, droppedTurnIds: compiled.manifest.droppedTurnIds, piSessionReused:!!previous });
          await this.state(active, 'generating');
          // Keep history out of Agent's evolving transcript; inject the bounded projection only at convertToLlm.
          return { context: { messages: request.context.messages.filter(m => m.role !== 'system'), tools } };
          } catch (e) { active.failure = e instanceof RuntimeError ? e.code : 'IO_ERROR'; throw e; }
        },
        convertToLlm: () => active.requestMessages,
        streamFn: (_model, context, options) => {
          const fetcher=this.fetch(active);
          const common = { ...options, reasoning:profile.thinkingEffort==='off'?undefined:profile.thinkingEffort, onProviderStreamEvent: (event: unknown) => audit.observe(profile.protocol, event), apiKey: 'sk-thor-native-placeholder', fetch: this.limits.runTimeoutMs?fetcher:withoutProviderDeadline(fetcher), transport: 'sse' as const, maxTokens: profile.maxOutput, maxRetries: 0, timeoutMs: this.limits.runTimeoutMs, cacheRetention: 'none' as const, headers: profile.customHeaders, env: {} };
          const source = profile.protocol === 'openai-responses' ? responses(model, context, common) : profile.protocol==='openai-completions' ? completions(model,context,common) : anthropic(model, context, common);
          return providerStopBoundary(source, active.controller.signal, model, (phase, work) => this.watch(active, phase, work));
        },
      };
      const agent=previous??new Agent(agentOptions);
      if(previous) {agent.state.model=model;agent.state.thinkingLevel=profile.thinkingEffort??'off';agent.beforeToolCall=agentOptions.beforeToolCall;agent.prepareRequest=agentOptions.prepareRequest;agent.convertToLlm=agentOptions.convertToLlm!;agent.streamFunction=agentOptions.streamFn;}
      if(this.session)this.session.agent=agent;
      a.agent = agent;
      const abortAgent = () => agent.abort(); a.controller.signal.addEventListener('abort', abortAgent, { once: true });
      const unsubscribe=agent.subscribe(e => this.onPi(active, e));
      try {await this.watch(a, 'agent_prompt', () => agent.prompt(user));} finally {unsubscribe();a.controller.signal.removeEventListener('abort',abortAgent);}
      if (a.persistenceFailed) fail('PERSISTENCE_ERROR');
      const assistant = agent.state.messages.filter(m => m.role === 'assistant').at(-1);
      const saveIncomplete = () => this.saveTurn(active,false);
      if (a.controller.signal.aborted) {
        await saveIncomplete();
        a.run.error = a.stopReason ?? 'CANCELLED'; await this.state(a, 'stopping');
        if (a.stopReason === 'STALE_AUTHORIZATION') await this.event(a, 'authorization_changed', { previousEpoch: a.lease.capabilityEpoch });
        await this.state(a, a.stopReason === 'TIMEOUT' || a.stopReason === 'PERSISTENCE_ERROR' ? 'failed' : 'cancelled');
      } else if (!assistant || assistant.role !== 'assistant' || assistant.stopReason === 'error' || assistant.stopReason === 'aborted' || assistant.stopReason === 'length') {
        await saveIncomplete();
        // Persist only fixed local reason labels, never SDK errorMessage, provider
        // bodies or headers. A clean HTTP EOF can still be an output-token limit.
        const stopReason = !assistant || assistant.role !== 'assistant' ? 'missing' : assistant.stopReason === 'length' ? 'length' : assistant.stopReason === 'aborted' ? 'aborted' : 'error';
        a.run.error = stopReason === 'length' ? 'LIMIT_EXCEEDED' : a.failure ?? 'IO_ERROR';
        await this.event(a,'diagnostic',{code:stopReason==='length'?'MODEL_OUTPUT_LIMIT':'MODEL_STREAM_FAILED',stopReason});
        await this.state(a, 'failed');
      } else {
        await this.saveTurn(active,true);
        await this.state(a, 'completed');
      }
      return structuredClone(a.run);
    } catch (error) {
      if (!a) throw pending.reason ? new RuntimeError(pending.reason) : error;
      const code = error instanceof RuntimeError ? error.code : 'IO_ERROR';
      if(!a.persistenceFailed)await this.saveTurn(a,false);
      a.controller.abort(); a.agent?.abort(); a.run.error = code;
      // A broken durable store cannot confirm a terminal transition. Surface the failure,
      // retain the last committed nonterminal state for interrupted recovery, and stop work.
      if (a.persistenceFailed) throw new RuntimeError('PERSISTENCE_ERROR');
      if (code === 'CANCELLED' || code === 'STALE_AUTHORIZATION') {
        await this.state(a, 'stopping'); await this.state(a, 'cancelled');
      } else await this.state(a, 'failed');
      return structuredClone(a.run);
    } finally { a?.stream?.dispose();if (timer) clearTimeout(timer); this.active = undefined; this.pending = undefined; this.reserved = false; }
  }
}
