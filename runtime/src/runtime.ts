import { Agent, type AgentEvent, type AgentTool, type AgentOptions, type AgentMessage } from '@earendil-works/pi-agent-core';
import type { Message, Model, AssistantMessage } from '@earendil-works/pi-ai';
import { streamSimple as responses } from '@earendil-works/pi-ai/api/openai-responses';
import { streamSimple as completions } from '@earendil-works/pi-ai/api/openai-completions';
import { streamSimple as anthropic } from '@earendil-works/pi-ai/api/anthropic-messages';
import { hasPerceptionContext, buildProductPrompt, buildRuntimeObservation, resolveContent, hash } from './context.js';
import { DEFAULT_LIMITS, isErrorCode, RuntimeError, type AgentRun, type AuthoritySnapshot, type Content, type DomainMessage, type EffectRecord, type ModelProfile, type RunEvent, type RunLease, type RunState, type RuntimeLimits, type RuntimePorts, type ToolDescriptor, type ToolEnvelope, type ToolOutcome, type ErrorCode, type Capability } from './contracts.js';
import { ToolRegistry } from './registry.js';
import { ProtocolAudit } from './protocol-audit.js';
import { RuntimeSession, closeInterruptedRound } from './session.js';
import { withoutProviderDeadline } from './provider-timeouts.js';
import { StreamEvents } from './stream-events.js';
import { checkProfile } from './profile-validation.js';
import { providerStopBoundary } from './provider-stop.js';
import { unavailableToolReason } from './tool-availability.js';
import { ModelRoundDiagnostics } from './model-round-diagnostics.js';
import { createPiSession, estimateContextTokens, estimateProjectedContextTokens, type PiSessionHandle, type AgentSessionEvent } from '@thor-agent/pi-session-browser';
import { AssistantMessageEventStream } from '@earendil-works/pi-ai/utils/event-stream';
import { preparePiHistory, type PiHistoryState, type PendingPiInput } from './pi-history.js';
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
  toolSchemaId?: string;
  stream?: StreamEvents; persistenceFailed: boolean; resultRefs: Map<string, Content[]>;
  failure?: ErrorCode;
  observationRequired: boolean;
  waits: Map<number, { phase: WaitPhase; since: number }>; waitSequence: number; lastProgress: number;
  effects: EffectRecord[];
  turnId: string; streamMessage: number; input: Content[];
  modelDiagnostics?: ModelRoundDiagnostics;
  pi?: PiSessionHandle; persistedEntries: Set<string>; currentMessages: AgentMessage[]; lastObservation?: string;
  provenance: Record<string, Capability[]>; userSources: Capability[]; inputRunIds: Record<string,string>; compacting: boolean;
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
function toolSubmission(outcome: ToolOutcome): ToolOutcome['submission'] {
  // Read this fixed bridge field only. An observed result (or a body claiming
  // no dispatch) does not say whether Native crossed its effect checkpoint.
  // A positive submitted receipt always outranks contradictory metadata.
  if (outcome.submission !== 'not_dispatched' && outcome.submission !== 'submitted' && outcome.submission !== 'unknown') return undefined;
  return outcome.outcome === 'submitted' ? 'submitted' : outcome.submission;
}
const targetChangedDiagnostics = new Set<NonNullable<ToolOutcome['targetChangedDiagnostic']>>([
  'snapshot_missing', 'snapshot_expired_or_display_changed', 'input_layout_changed', 'frame_missing',
  'frame_snapshot_mismatch', 'input_clip_changed', 'input_outside_clip', 'expected_package_mismatch',
]);
function fail(code: ErrorCode): never { throw new RuntimeError(code); }
type PreflightDiagnostic = 'availability_target_changed' | 'request_snapshot_changed' | 'observation_required';
class ToolPreflightError extends RuntimeError {
  constructor(readonly diagnostic: PreflightDiagnostic) { super('TARGET_CHANGED'); }
}
export function assertRuntimeFeatures(): void {
  if (typeof AbortController === 'undefined' || typeof ReadableStream === 'undefined' || typeof TextEncoder === 'undefined' || typeof TextDecoder === 'undefined' || typeof Headers === 'undefined' || typeof Request === 'undefined' || typeof Response === 'undefined' || typeof structuredClone === 'undefined' || !globalThis.crypto?.subtle || !globalThis.crypto?.randomUUID || typeof globalThis.performance?.now !== 'function') throw new RuntimeError('UNSUPPORTED', 'Required browser streaming/crypto APIs unavailable');
}
function toModel(p: ModelProfile): Model<any> {
  return { id: p.modelId, name: p.label, api: p.protocol, provider:p.provider==='opencode-go'?'opencode-go':p.provider==='deepseek'?'deepseek':p.protocol==='anthropic-messages'?'anthropic':'openai', baseUrl: p.baseUrl.replace(/\/+$/, ''),
    reasoning: p.reasoning??(p.thinkingEffort!==undefined && p.thinkingEffort!=='off'), thinkingLevelMap:p.thinkingLevelMap as Model<any>['thinkingLevelMap'], input:p.input??['text','image'], contextWindow: Math.min(p.contextWindow,p.maxInput??p.contextWindow), maxTokens: p.maxOutput,
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
    if (this.active && !terminal.has(this.active.run.state)) { this.active.stopReason = 'CANCELLED'; this.active.controller.abort(); this.active.agent?.abort(); void this.active.pi?.session.abort(); }
  }
  /** Call immediately when the Native capability mirror changes, even for OFF -> ON. */
  authorizationChanged(sessionId: string, capabilityEpoch: number): void {
    if (this.pending && this.pending.sessionId === sessionId && capabilityEpoch !== this.pending.epoch) { this.pending.reason = 'STALE_AUTHORIZATION'; this.pending.controller.abort(); }
    const a = this.active;
    if (a && a.run.sessionId === sessionId && capabilityEpoch !== a.lease.capabilityEpoch && !terminal.has(a.run.state)) {
      a.stopReason = 'STALE_AUTHORIZATION'; a.controller.abort(); a.agent?.abort(); void a.pi?.session.abort();
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
  /** A temporarily stale target remains callable so Native can return its
   * actionable recovery refusal. Genuine disabled/missing backends stay hidden.
   * Only update this loadout at Pi's initial/between-turn boundary. */
  private publishedTools(snapshot: AuthoritySnapshot, profile: ModelProfile): ToolDescriptor[] {
    if(profile.supportsTools===false)return [];
    const ready=new Set(this.registry.effective(snapshot).map(tool=>tool.toolId));
    return this.registry.all().filter(tool=>tool.modelVisibility && tool.requiredCapabilities.every(cap=>snapshot.session.enabled[cap]) &&
      (ready.has(tool.toolId) || unavailableToolReason(tool,snapshot)==='TARGET_CHANGED'));
  }
  private async toolCheck(a: Active, t: ToolDescriptor): Promise<AuthoritySnapshot> {
    const s = await this.authority(a);
    if (!t.requiredCapabilities.every(c => s.session.enabled[c])) fail('CAPABILITY_DISABLED');
    if (!a.effective.some(x => x.toolId === t.toolId)) fail('BACKEND_UNAVAILABLE');
    if (!this.registry.effective(s).some(x => x.toolId === t.toolId)) {
      const reason = unavailableToolReason(t, s);
      if(reason === 'TARGET_CHANGED') throw new ToolPreflightError('availability_target_changed');
      fail(reason);
    }
    // Sleep has no screen target. A changing page must not invalidate its timer.
    if (t.toolId !== 'agent.sleep' && s.l0.snapshotId !== a.requestSnapshot?.l0.snapshotId) throw new ToolPreflightError('request_snapshot_changed');
    if (a.observationRequired && dependsOnScreen(t)) throw new ToolPreflightError('observation_required');
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
  /** Zen's wire name maps to the real, scoped Skill reader; Native identity is unchanged. */
  private modelToolName(t: ToolDescriptor, profile: ModelProfile): string {
    return profile.provider==='opencode-zen' && t.toolId==='skills.read' && !this.registry.get('read') ? 'read' : t.modelName;
  }
  private tools(a: Active, descriptors: ToolDescriptor[]): AgentTool[] {
    return descriptors.map(t => ({ name: this.modelToolName(t,a.profile), label: t.toolId, description: t.description, parameters: t.inputSchema, replay: 'never', executionMode: 'sequential',
      prepareArguments: args => { this.registry.validate(t.modelName, args); return args as any; },
      execute: async (callId, args, signal) => {
        const deadline = Math.min(Date.now() + t.timeoutMs, a.lease.expiresAt, this.limits.runTimeoutMs ? a.run.startedAt + this.limits.runTimeoutMs : Number.MAX_SAFE_INTEGER);
        const controller = new AbortController();
        const cancel = () => controller.abort(); a.controller.signal.addEventListener('abort', cancel, { once: true }); signal?.addEventListener('abort', cancel, { once: true });
        if (a.controller.signal.aborted || signal?.aborted) controller.abort();
        let timedOut = false, dispatched = false;
        let effect: EffectRecord | undefined;
        let receipt: { outcome: NonNullable<ToolOutcome['outcome']>; evidenceRef?: string; submission?: ToolOutcome['submission'] } | undefined;
        let targetChangedReason: 'input_focus_mismatch' | undefined;
        let targetChangedDiagnostic: ToolOutcome['targetChangedDiagnostic'];
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
          const submission = toolSubmission(outcome);
          receipt = { outcome: outcome.outcome ?? 'unknown', evidenceRef: outcome.evidenceRef, ...(submission ? { submission } : {}) };
          if (effect) Object.assign(effect, receipt);
          const outcomeError = toolOutcomeError(outcome.error);
          // Native revokes the lease synchronously on Stop, before its UI state
          // publication reaches JS. Preserve that terminal receipt immediately;
          // another authority RPC would mask cancellation as a stale lease.
          if (outcomeError === 'CANCELLED' || outcomeError === 'STALE_AUTHORIZATION') fail(outcomeError);
          const rejectedScreenRead = t.effectClass === 'read' && t.requiredCapabilities.includes('screen_perception') && outcomeError !== undefined;
          // A rejected read has no body to admit. Recheck authority/capabilities
          // first, then preserve Native's fixed refusal instead of masking it
          // with a source change. Successful reads keep both source checks.
          const current = await abortable(this.toolResultCheck(a, t, !rejectedScreenRead), controller.signal);
          if (outcomeError === 'TARGET_CHANGED' && outcome.targetChangedDiagnostic && targetChangedDiagnostics.has(outcome.targetChangedDiagnostic)) targetChangedDiagnostic = outcome.targetChangedDiagnostic;
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
          await this.event(a, 'tool_result', { toolId: t.toolId, observedAt: outcome.observedAt, source: outcome.source, truncated: bounded.truncated, error: outcomeError, outcome: outcome.outcome, evidenceRef: outcome.evidenceRef, ...(submission ? { submission } : {}), content: bounded.domain }, callId);
          if (outcomeError === undefined) {
            if (t.toolId === 'perception.select_screen') a.observationRequired = true;
            else if (isCurrentScreenRead(t)) a.observationRequired = false;
          }
          return { content: bounded.content, details: { observedAt: outcome.observedAt, source: outcome.source, truncated: bounded.truncated, outcome: outcome.outcome ?? 'observed', ...(submission ? { submission } : {}) }, isError: outcomeError !== undefined };
        } catch (error) {
          const code = timedOut ? 'TIMEOUT' : a.controller.signal.aborted ? a.stopReason ?? 'CANCELLED' : error instanceof RuntimeError ? error.code : 'IO_ERROR';
          if (code === 'PERSISTENCE_ERROR') { a.stopReason = code; a.controller.abort(); }
          // Revocation withholds content, but cannot undo a submitted action.
          // Only a receipt already received before this rejection is retained;
          // uncooperative executors never write events after terminal state.
          const submission = !dispatched ? 'not_dispatched' : receipt?.outcome === 'submitted' ? 'submitted' : receipt?.submission ?? 'unknown';
          const preflightDiagnostic = code === 'TARGET_CHANGED' && error instanceof ToolPreflightError ? error.diagnostic : undefined;
          const recovery = code === 'TARGET_CHANGED' ? {
            kind: 'observe_again', continueRun: true, retryPreviousAction: false, discardPreviousTarget: true,
            submission,
            sideEffectPossible: submission !== 'not_dispatched' && t.effectClass !== 'read' && t.effectClass !== 'network',
            ...(targetChangedReason ? { reason: targetChangedReason } : {}),
            ...(targetChangedDiagnostic ? { diagnostic: targetChangedDiagnostic } : {}),
            ...(preflightDiagnostic ? { preflightDiagnostic } : {}),
            instruction: 'The page or target changed. Continue this Run by observing the current selected screen before deciding the next action. Discard old coordinates, frame IDs and node refs. Do not replay the previous action. Unknown submission is not proof it did not execute.' +
              (targetChangedReason ? ' The authorized target does not currently own the required input focus. Repeated screenshots alone do not restore input focus. First observe the current windows and focus; if still permitted by the task and current guards, use an existing legal app launch or a touch on the selected screen to restore focus, then observe again before deciding a new action. Do not change focus locks or permissions, auto-focus, or automatically retry the rejected input.' : ''),
          } : undefined;
          if (recovery) a.observationRequired = true;
          const result = { error: code, outcome: receipt?.outcome ?? 'unknown', evidenceRef: receipt?.evidenceRef, ...(receipt?.submission ? { submission: receipt.submission } : {}), contentDiscarded: receipt !== undefined, ...(recovery ? { recovery, ...(targetChangedDiagnostic ? { diagnostic: targetChangedDiagnostic } : {}), ...(preflightDiagnostic ? { preflightDiagnostic } : {}) } : {}) };
          const content = [{ type: 'text' as const, text: recovery ? JSON.stringify(result) : code }];
          if (recovery) a.resultRefs.set(callId, content);
          await this.event(a, 'tool_result', { toolId: t.toolId, ...result, ...(recovery ? { content } : {}) }, callId);
          // Commit the final tool result before aborting Pi. A terminal Native
          // rejection must never be fed into a new model round while we wait for
          // a delayed chat/authority publication to call stop().
          if (code === 'CANCELLED' || code === 'STALE_AUTHORIZATION') {
            a.stopReason = code; a.controller.abort(); a.agent?.abort();
          }
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
          // A screen action may attach a post-action screenshot. Its pixels
          // originate from perception; screen_control authorizes the action,
          // and is already rechecked before and after admitting the result.
          if (dependsOnScreen(t) && !s.session.enabled.screen_perception) fail('CAPABILITY_DISABLED');
          const requiredSources = dependsOnScreen(t)
            ? [...t.requiredCapabilities.filter(cap => cap !== 'screen_control'), 'screen_perception'] as const
            : t.requiredCapabilities;
          if (requiredSources.some(cap => !attachment.source.includes(cap))) fail('PROTOCOL_ERROR');
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
  private async savePi(a:Active):Promise<void> {
    if(!a.pi || a.persistenceFailed)return;
    const all=a.pi.snapshot();
    const header=all.entries.find(entry=>entry.type==='session')!;
    const entries=all.entries.filter(entry=>entry.type!=='session');
    const appended=entries.filter(entry=>!a.persistedEntries.has(entry.id));
    const entrySources:Record<string,Capability[]>={},inputRunIds:Record<string,string>={};
    for(const entry of appended) {
      const source=a.provenance[entry.id]??(entry.type==='message' && entry.message.role==='user'?[...a.userSources]:[...a.source]);
      entrySources[entry.id]=source;
      if(a.inputRunIds[entry.id])inputRunIds[entry.id]=a.inputRunIds[entry.id]!;
    }
    // A migration/branch may append many records at once. Commit its entire
    // suffix, final leaf and input acknowledgements atomically, never a prefix.
    if(this.ports.persistence.savePiSession)await this.persist(a,()=>this.ports.persistence.savePiSession!(a.run.sessionId,{header,entries:appended,leafId:all.leafId,source:[...a.source],entrySources,inputRunIds}));
    for(const entry of appended){a.persistedEntries.add(entry.id);a.provenance[entry.id]=entrySources[entry.id]!;}
    if(this.session)this.session.piSnapshot={header,entries,leafId:all.leafId,provenance:structuredClone(a.provenance)};
  }
  private async saveTurn(a:Active,complete:boolean):Promise<void> {
    await a.stream?.flush();
    const messages:DomainMessage[]=[];
    for(const m of a.currentMessages) {
      if(m.role==='user')messages.push({kind:'user',content:a.input});
      else if(m.role==='assistant') {
        // Partial JSON is diagnostic data, never an executable historical tool call.
        const calls=m.content.filter(c=>c.type==='toolCall').filter(c=>m.stopReason==='toolUse' || a.seenCalls.has(c.id)).map(c=>({id:c.id,name:c.name,arguments:c.arguments}));
        const text=m.content.filter(c=>c.type==='text').map(c=>c.text).join('');
        const allowedCalls=new Set(calls.map(c=>c.id));
        const content=m.content.filter(c=>c.type!=='toolCall' || allowedCalls.has(c.id));
        messages.push({kind:'assistant',text,calls,replay:{api:m.api,provider:m.provider,model:m.model,baseUrl:a.profile.baseUrl.replace(/\/+$/, ''),content:structuredClone(content)}});
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
    if(e.type==='message_end' && !a.persistenceFailed) {
      a.currentMessages.push(structuredClone(e.message));
      // The upstream Session listener appends this message before our listener.
      // Only a real current prompt acknowledges the current input; restored
      // user entries have their original Run IDs supplied by preparePiHistory.
      if(e.message.role==='user' && a.pi) {
        const entry=a.pi.manager.getEntries().slice().reverse().find(entry=>entry.type==='message' && entry.message.role==='user');
        if(entry)a.inputRunIds[entry.id]=a.run.runId;
      }
      await this.savePi(a);
      await this.saveTurn(a,false);
    }
    if (e.type === 'tool_execution_start' && !a.controller.signal.aborted) await this.state(a, 'executing_tools');
    // A tool-local timeout/rejection can finish while its Native approval RPC is
    // still unwinding. Leave awaiting_user before Pi starts its next model turn.
    if(e.type==='tool_execution_end' && a.run.state==='awaiting_user' && !a.controller.signal.aborted)await this.state(a,'executing_tools');
    if (e.type === 'tool_execution_end' && e.isError) await this.event(a, 'diagnostic', { code: e.result.details?.recovery?.kind === 'observe_again' ? 'TARGET_CHANGED' : 'TOOL_FAILED', toolName: e.toolName }, e.toolCallId);
  }
  async run(lease: RunLease, input: Content[], piState?: PiHistoryState, pendingInputs: PendingPiInput[] = []): Promise<AgentRun> {
    assertRuntimeFeatures();
    if (this.reserved) fail('BACKEND_UNAVAILABLE');
    lease = structuredClone(lease); input = structuredClone(input);
    for (const [id, expiry] of this.consumedRuns) if (expiry <= Date.now()) this.consumedRuns.delete(id);
    if (lease.expiresAt <= Date.now()) fail('STALE_AUTHORIZATION');
    if (this.consumedRuns.has(lease.runId)) fail('STALE_AUTHORIZATION');
    this.consumedRuns.set(lease.runId, lease.expiresAt);
    this.reserved = true;
    let a: Active | undefined; let timer: ReturnType<typeof setTimeout> | undefined;
    const pending = { sessionId: lease.sessionId, epoch: lease.capabilityEpoch, controller: new AbortController(), reason: undefined as ErrorCode | undefined };
    this.pending = pending;
    if(this.limits.runTimeoutMs)timer = setTimeout(() => { pending.reason = 'TIMEOUT'; if (a) a.stopReason = 'TIMEOUT'; pending.controller.abort(); a?.agent?.abort(); void a?.pi?.session.abort(); }, Math.max(0, Math.min(this.limits.runTimeoutMs, lease.expiresAt - Date.now())));
    try {
      const initial = await abortable(this.ports.authority.read(lease.sessionId, lease.runId), pending.controller.signal);
      const profile = structuredClone(initial.session.model); checkProfile(profile);
      a = { run: { sessionId: lease.sessionId, runId: lease.runId, state: 'queued', modelProfileRevision: profile.revision, capabilityEpoch: lease.capabilityEpoch, startedAt: Date.now() },
        lease: structuredClone(lease), profile, controller: pending.controller, sequence: 0, startMono: performance.now(), requests: 0, effective: [], seenCalls: new Set(), source: new Set(), persistenceFailed: false, resultRefs: new Map(), effects: [], observationRequired:false, waits:new Map(),waitSequence:0,lastProgress:performance.now(), streamMessage: 0, input, persistedEntries:new Set(), currentMessages:[], provenance:{}, userSources:[], inputRunIds:{}, compacting:false, turnId: crypto.randomUUID() };
      this.active = a; const active = a; const model = toModel(profile);
      active.stream=new StreamEvents(f=>this.writeEvent(active,f.type,f.data,f.toolCallId));
      if(hasPerceptionContext(initial))a.source.add('screen_perception');
      await this.event(a, 'state', { state: 'queued' }, undefined, a.run); await this.state(a, 'preparing');
      await this.persist(a, () => this.ports.persistence.saveTurn(lease.sessionId, { turnId: active.turnId, runId: lease.runId, messages: [{ kind: 'user', content: input }], source: [...active.source], observedAt: Date.now(), complete: false }));
      await this.authority(a);
      const prepared=await preparePiHistory(piState ?? this.session?.piSnapshot,initial,model,this.ports.attachments,pendingInputs);
      active.provenance=prepared.provenance;
      active.inputRunIds=prepared.inputRunIds;
      for(const cap of prepared.source)active.source.add(cap);
      if(piState)for(const entry of piState.entries)active.persistedEntries.add(entry.id);
      for(const ref of input)if(ref.type==='attachment') {
        const attachment=await this.ports.attachments.get(ref.attachmentId);
        for(const cap of attachment?.source??[])if(!active.userSources.includes(cap))active.userSources.push(cap);
      }
      const content=await resolveContent(input,this.ports.attachments,initial.session.enabled);
      const text=content.filter(c=>c.type==='text').map(c=>c.text).join('\n');
      const images=content.filter(c=>c.type==='image');
      let audit=new ProtocolAudit();
      const allTools=this.tools(active,this.registry.all());
      const agentOptions:AgentOptions={initialState:{model,thinkingLevel:profile.thinkingEffort??'off'},sessionId:lease.sessionId,toolExecution:'sequential',transport:'sse',
        // Native authorization/diagnostics only. Official AgentSession owns the
        // canonical transcript, projection, continuation and compaction hooks.
        finishTurn:async({toolResults})=>{
          if(!toolResults.length || active.controller.signal.aborted || !active.pi)return;
          // Every call from this model response keeps its original target. Only
          // after the whole batch completes may Native promote its fresh read.
          // AgentSession wraps this hook and flushes this queued context at the
          // following turn_end, before preparing the next provider request.
          const snapshot=await this.authority(active,true);
          active.effective=this.publishedTools(snapshot,profile);
          active.toolSchemaId=snapshot.toolSchemaId;
          active.pi.session.setActiveToolsByName(active.effective.map(tool=>this.modelToolName(tool,profile)));
          const observation=buildRuntimeObservation(snapshot);
          if(observation!==active.lastObservation) {
            active.lastObservation=observation;
            if(hasPerceptionContext(snapshot))active.source.add('screen_perception');
            await active.pi.session.sendCustomMessage({customType:'thor-runtime-context',content:observation,display:false},{triggerTurn:false});
          }
        },
        prepareRequest:async request=>{
          try {
            const snapshot=await this.authority(active,true);
            active.requestSnapshot=snapshot;
            audit=new ProtocolAudit();
            await this.savePi(active);
            const usage=estimateProjectedContextTokens(active.pi!.manager.buildSessionProjection(),active.pi!.manager.getBranch());
            const manifest={snapshotId:crypto.randomUUID(),modelProfileRevision:profile.revision,capabilityEpoch:lease.capabilityEpoch,
              registryVersion:this.registry.version,l0SnapshotId:snapshot.l0.snapshotId,
              includedMessageIds:active.pi!.manager.getBranch().map(e=>e.id),attachmentRefs:[],
              effectiveTools:active.agent!.state.tools.flatMap(tool=>active.effective.find(t=>this.modelToolName(t,profile)===tool.name)?.toolId??[]),
              ...(active.toolSchemaId ? {toolSchemaId:active.toolSchemaId} : {}),
              estimatedTokens:usage.tokens,estimated:true as const,compressionVersion:'pi-session-v1' as const,droppedTurnIds:[],contentHash:await hash(request.context.messages)};
            await this.persist(active,()=>this.ports.persistence.saveContext(manifest));
            await this.event(active,'context',{snapshotId:manifest.snapshotId,estimatedTokens:usage.tokens,droppedTurnIds:[],piSessionReused:!!piState || !!this.session?.piSnapshot,
              historyRestored:prepared.entries.length>0,restoredHistoryMessages:prepared.entries.filter(e=>e.type==='message').length,contextManager:'pi',compactionManager:'pi'});
            await this.state(active,'generating');
            // The official Pi wrapper has already projected this request's tool
            // declarations. Do not mutate its loadout after that snapshot.
            return undefined;
          }catch(error){active.failure=error instanceof RuntimeError?error.code:'IO_ERROR';throw error;}
        },
        streamFn:(requestModel,context,options)=>{
          const output=new AssistantMessageEventStream();
          void (async()=>{
            if(active.controller.signal.aborted || options?.signal?.aborted) {
              const message:AssistantMessage={role:'assistant',content:[],api:model.api,provider:model.provider,model:model.id,
                usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}},stopReason:'aborted',timestamp:Date.now()};
              output.push({type:'error',reason:'aborted',error:message});output.end();return;
            }
            try {
              if(++active.requests>this.limits.maxRequests)fail('LIMIT_EXCEEDED');
              const diagnostics=active.modelDiagnostics=new ModelRoundDiagnostics(active.requests);
              const fetcher=this.fetch(active);
              // Pi invalidates old provider usage after compaction/context edits.
              // Summary requests have their own context, ordinary requests use
              // Pi's projection-aware estimate rather than stale raw usage.
              const estimated=active.compacting?estimateContextTokens(context.messages).tokens:
                estimateProjectedContextTokens(active.pi!.manager.buildSessionProjection(),active.pi!.manager.getBranch()).tokens;
              if(profile.maxInput!==undefined && estimated+4096>profile.maxInput)fail('LIMIT_EXCEEDED');
              const available=profile.contextWindow-estimated-(profile.outputBudgetAutomatic?4096:profile.maxOutput);
              if(available<(profile.outputBudgetAutomatic?16:0))fail('LIMIT_EXCEEDED');
              const maxTokens=profile.outputBudgetAutomatic?Math.min(options?.maxTokens??profile.maxOutput,available):(options?.maxTokens??profile.maxOutput);
              const common={...options,onPayload:(payload:unknown)=>{diagnostics.payload(profile.protocol,payload);},
                onProviderStreamEvent:(event:unknown)=>{audit.observe(profile.protocol,event);diagnostics.observe(profile.protocol,event);},
                apiKey:'sk-thor-native-placeholder',fetch:this.limits.runTimeoutMs?fetcher:withoutProviderDeadline(fetcher),transport:'sse' as const,maxTokens,maxRetries:0,
                timeoutMs:this.limits.runTimeoutMs,cacheRetention:options?.cacheRetention??'short' as const,headers:profile.customHeaders,env:{}};
              const source=profile.protocol==='openai-responses'?responses(requestModel as Model<'openai-responses'>,context,common):profile.protocol==='openai-completions'?completions(requestModel as Model<'openai-completions'>,context,common):anthropic(requestModel as Model<'anthropic-messages'>,context,common);
              for await(const event of providerStopBoundary(source,active.controller.signal,requestModel,(phase,work)=>this.watch(active,phase,work))) {
                if(event.type==='done' || event.type==='error') {
                  const message=event.type==='done'?event.message:event.error;diagnostics.finish(message.stopReason);
                  await this.event(active,'model_round',diagnostics.snapshot());
                }
                output.push(event);
              }
              output.end();
            }catch(error){
              active.failure=error instanceof RuntimeError?error.code:'IO_ERROR';
              const message:AssistantMessage={role:'assistant',content:[],api:model.api,provider:model.provider,model:model.id,
                usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}},stopReason:'error',timestamp:Date.now(),errorMessage:active.failure};
              output.push({type:'error',reason:'error',error:message});output.end();
            }
          })();
          return output;
        },
      };
      const agent=new Agent(agentOptions);active.agent=agent;
      active.effective=this.publishedTools(initial,profile);
      active.toolSchemaId=initial.toolSchemaId;
      active.pi=createPiSession({agent,sessionId:lease.sessionId,header:prepared.header,entries:prepared.entries,leafId:prepared.leafId,model,
        systemPrompt:buildProductPrompt(initial,active.effective.map(t=>({...t,modelName:this.modelToolName(t,profile)}))),tools:allTools,
        settings:{...profile.piSettings}});
      active.pi.session.setActiveToolsByName(active.effective.map(t=>this.modelToolName(t,profile)));
      if(this.session)this.session.agent=agent;
      const officialBefore=agent.beforeToolCall;
      agent.beforeToolCall=async(call,signal)=>audit.permits(call.toolCall.id,call.toolCall.arguments)?officialBefore?.(call,signal):{block:true,reason:'PROTOCOL_ERROR: incomplete or conflicting JSON',terminate:true};
      const abortSession=()=>{agent.abort();void active.pi?.session.abort();};active.controller.signal.addEventListener('abort',abortSession,{once:true});
      const unsubscribe=agent.subscribe(e=>this.onPi(active,e));
      const unsubscribeSession=active.pi.session.subscribe((event:AgentSessionEvent)=>{
        if(event.type==='compaction_start' || event.type==='compaction_end') {
          active.compacting=event.type==='compaction_start';
          void this.event(active,'diagnostic',{code:event.type==='compaction_start'?'CONTEXT_COMPACTION_STARTED':'CONTEXT_COMPACTION_FINISHED',reason:event.reason,
            ...(event.type==='compaction_end'?{aborted:event.aborted,compacted:!!event.result}:{} )}).catch(()=>{});
        }
      });
      active.lastObservation=buildRuntimeObservation(initial);
      await active.pi.session.sendCustomMessage({customType:'thor-runtime-context',content:active.lastObservation,display:false},{triggerTurn:false});
      await this.savePi(active);
      try {await this.watch(active,'agent_prompt',()=>active.pi!.session.prompt(text,{images,expandPromptTemplates:false}));}
      finally {await this.savePi(active);unsubscribe();unsubscribeSession();active.controller.signal.removeEventListener('abort',abortSession);}
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
        await this.event(a,'diagnostic',{code:stopReason==='length'?'MODEL_OUTPUT_LIMIT':'MODEL_STREAM_FAILED',...a.modelDiagnostics?.snapshot(),stopReason});
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
    } finally { if(a?.pi){await this.savePi(a).catch(()=>{});a.pi.dispose();} a?.stream?.dispose();if (timer) clearTimeout(timer); this.active = undefined; this.pending = undefined; this.reserved = false; }
  }
}
