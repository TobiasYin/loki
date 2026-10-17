import type { TSchema } from 'typebox';
import type { AssistantMessage } from '@earendil-works/pi-ai';

export const CAPABILITIES = ['screen_perception', 'web_search', 'screen_control', 'adb_debug'] as const;
export type Capability = typeof CAPABILITIES[number];
export type Capabilities = Record<Capability, boolean>;
export const defaultCapabilities = (): Capabilities => ({ screen_perception: true, web_search: true, screen_control: false, adb_debug: false });
export type Availability = { status: 'ready' | 'permission_missing' | 'backend_missing' | 'unsupported' | 'temporarily_unavailable'; reason?: string; reasonCode?: 'TARGET_CHANGED'|'PERMISSION_MISSING'|'SECURE_CONTENT'|'DISPLAY_GONE' };
export interface ModelProfile {
  profileId: string; revision: number; label: string;
  protocol: 'openai-responses' | 'anthropic-messages' | 'openai-completions'; baseUrl: string; modelId: string;
  credentialRef: string; contextWindow: number; maxOutput: number;
  /** Native marks only an implicit official-profile output ceiling as automatic. */
  outputBudgetAutomatic?: boolean;
  input?: ('text'|'image')[];
  provider?: string;
  /** Native-normalized official catalog facts, not a fabricated successful probe. */
  providerMetadataKnown?: boolean; probeRequired?: boolean; supportsTools?: boolean; configurationProbe?: boolean;
  thinkingEffort?: 'off'|'minimal'|'low'|'medium'|'high'|'xhigh'|'max';
  reasoning?: boolean; thinkingLevelMap?: Record<string,string|null>; compat?: Record<string,unknown>;
  customHeaders?: Record<string, string>;
  /** Optional official Pi session settings (tests/host configuration), never a second context policy. */
  piSettings?: Record<string, unknown>;
  lastProbe: { revision: number; textStream: boolean; imageInput: boolean; toolCalling: boolean; imageToolRoundTrip: boolean };
}
export interface SessionConfig { sessionId: string; title: string; model: ModelProfile; enabled: Capabilities; capabilityEpoch: number }
export interface RunLease { sessionId: string; runId: string; leaseId: string; capabilityEpoch: number; expiresAt: number }
export interface AppMetadata {
  packageName: string; label?: string; versionName?: string; versionCode?: string;
  metadataStatus?: 'available' | 'unavailable';
}
export interface L0 {
  snapshotId: string; device?: string; upperApp?: AppMetadata;
  upperAppObservation?: { status: 'observed' | 'unknown' | 'ambiguous' | 'unavailable'; displayId?: number };
  targetScreen?: 'upper'|'lower'; displaySnapshotId?: string;
}
export interface AuthoritySnapshot { session: SessionConfig; lease: RunLease; availability: Record<string, Availability>; l0: L0; skills?: { name: string; description: string; hash: string }[] }
export interface AuthorityPort {
  /** Caller-owned mirror of Native's authority. No method here grants authority. */
  read(sessionId: string, runId: string, refresh?: boolean): Promise<AuthoritySnapshot>;
}
export interface ToolDescriptor {
  toolId: string; modelName: string; schemaVersion: string; description: string; inputSchema: TSchema;
  requiredCapabilities: Capability[]; platformPrerequisites: string[];
  executor: string; resources: string[]; effectClass: 'read' | 'network' | 'input' | 'write' | 'arbitrary-shell';
  timeoutMs: number; cancellable: boolean; concurrencyKey: string;
  provenance: string; modelVisibility: boolean;
  outputPolicy: { maxTextBytes: number; maxImageBytes: number; maxImages: number; imageTokenEstimate: number };
}
export interface ToolPack { packId: string; packVersion: string; tools: ToolDescriptor[] }
export interface ToolEnvelope {
  bridgeVersion: '0.1'; sessionId: string; runId: string; leaseId: string; toolCallId: string;
  toolId: string; schemaVersion: string; capabilityEpoch: number; requestId: string;
  deadline: number; arguments: unknown; targetSnapshotId: string; resources: string[];
}
export const ERROR_CODES = ['CAPABILITY_DISABLED', 'STALE_AUTHORIZATION', 'PERMISSION_MISSING', 'BACKEND_UNAVAILABLE', 'TARGET_CHANGED', 'UNSUPPORTED', 'TIMEOUT', 'CANCELLED', 'LIMIT_EXCEEDED', 'IO_ERROR', 'PROTOCOL_ERROR', 'PERSISTENCE_ERROR', 'INVALID_ARGUMENT', 'HASH_CONFLICT', 'APPROVAL_REQUIRED', 'COMMAND_BLOCKED', 'SECURE_CONTENT', 'DISPLAY_GONE', 'RATE_LIMITED'] as const;
export type ErrorCode = typeof ERROR_CODES[number];
export function isErrorCode(value: unknown): value is ErrorCode { return typeof value === 'string' && (ERROR_CODES as readonly string[]).includes(value); }
export class RuntimeError extends Error {
  public code: ErrorCode;
  constructor(code: ErrorCode, message?: string) {
    const normalized = isErrorCode(code) ? code : 'PROTOCOL_ERROR';
    super(message ?? normalized); this.code = normalized;
  }
}
export interface Attachment { attachmentId: string; mimeType: 'image/png' | 'image/jpeg' | 'image/webp'; data: string; observedAt: number; expiresAt: number; source: Capability[] }
export interface AttachmentPort { put(attachment: Attachment): Promise<void>; get(id: string): Promise<Attachment | undefined> }
export type Content = { type: 'text'; text: string } | { type: 'attachment'; attachmentId: string };
export interface ToolOutcome {
  content: Content[]; observedAt: number; source: string; truncated: boolean; evidenceRef?: string;
  /** Native JSON success commonly serializes error:null. */
  error?: ErrorCode | null; outcome?: 'submitted' | 'observed' | 'unknown';
  /** Fixed Native control metadata, never parsed from an untrusted result body. */
  targetChangedReason?: 'input_focus_mismatch';
}
export interface ToolPort { execute(envelope: ToolEnvelope, signal: AbortSignal, awaiting?: (waiting: boolean) => Promise<void>): Promise<ToolOutcome> }
export interface NetworkEnvelope {
  bridgeVersion: '0.1'; purpose: 'model'; requestId: string; sessionId: string; runId: string;
  leaseId: string; capabilityEpoch: number; profileRef: string; profileRevision: number;
  credentialRef: string; credentialPlaceholder: string; deadline: number;
}
export interface FetchPort { fetch(input: RequestInfo | URL, init: RequestInit | undefined, envelope: NetworkEnvelope): Promise<Response> }
export type RunState = 'queued' | 'preparing' | 'generating' | 'executing_tools' | 'awaiting_user' | 'stopping' | 'completed' | 'failed' | 'cancelled' | 'interrupted';
export interface AgentRun { sessionId: string; runId: string; state: RunState; modelProfileRevision: number; capabilityEpoch: number; startedAt: number; error?: ErrorCode }
export interface RunEvent {
  sessionId: string; runId: string; eventId: string; sequence: number; wallTime: number; elapsedMs: number;
  type: 'state' | 'text_delta' | 'thinking_delta' | 'tool_call_update' | 'tool_intent' | 'tool_started' | 'tool_result' | 'authorization_changed' | 'context' | 'model_round' | 'diagnostic';
  toolCallId?: string; data: Record<string, unknown>;
}
export type DomainMessage =
  | { kind: 'user'; content: Content[] }
  | { kind: 'assistant'; text: string; calls: { id: string; name: string; arguments: Record<string, unknown> }[];
      /** Exact SDK content blocks and source identity, retained on disk for stable replay. */
      replay?: Pick<AssistantMessage, 'api' | 'provider' | 'model' | 'content'> & { baseUrl?: string } }
  | { kind: 'tool_result'; toolCallId: string; toolName: string; content: Content[]; isError: boolean };
export interface EffectRecord { toolCallId: string; toolId: string; effectClass: ToolDescriptor['effectClass']; outcome: 'submitted' | 'observed' | 'unknown'; evidenceRef?: string }
export interface HistoryTurn {
  turnId: string; runId: string; messages: DomainMessage[]; source: Capability[]; observedAt: number; complete: boolean;
  /** Caller-labelled explicit user constraints; bounded projection stops if these cannot fit. */
  constraints?: string[];
  /** Chronological Native observation blocks, positioned among this turn’s original messages. */
  contextUpdates?: { beforeMessage: number; text: string }[];
  /** Compact action ledger survives compression with the same provenance filter as raw results. */
  effects?: EffectRecord[];
}
export interface ContextSnapshot {
  snapshotId: string; modelProfileRevision: number; capabilityEpoch: number; registryVersion: string;
  l0SnapshotId: string; includedMessageIds: string[]; attachmentRefs: string[]; effectiveTools: string[];
  estimatedTokens: number; estimated: true; compressionVersion: 'deterministic-v1' | 'pi-session-v1';
  droppedTurnIds: string[]; contentHash: string;
}
export interface PersistencePort {
  appendEvent(event: RunEvent): Promise<void>;
  /** Commit the Run state and corresponding sequenced event in one storage transaction. */
  commitRun(run: AgentRun, event: RunEvent): Promise<void>;
  saveRun(run: AgentRun): Promise<void>;
  saveContext(snapshot: ContextSnapshot): Promise<void>;
  saveTurn(sessionId: string, turn: HistoryTurn): Promise<void>;
  /** Native stores official Pi entries without interpreting the conversation. */
  savePiSession?(sessionId: string, value: {header: unknown;entries: unknown[];leafId: string|null;source: Capability[];inputRunId?:string;inputRunIds?:Record<string,string>;entrySources?:Record<string,Capability[]>}): Promise<void>;
}
export interface RuntimePorts { authority: AuthorityPort; tools: ToolPort; network: FetchPort; persistence: PersistencePort; attachments: AttachmentPort }
export interface RuntimeLimits { maxRequests: number; runTimeoutMs: number; maxRequestBytes: number; maxResponseBytes: number; maxHistoryTurns: number; imageTokenEstimate: number }
/** runTimeoutMs=0: no model/task wall-clock deadline; individual tools keep their own timeout. */
export const DEFAULT_LIMITS: RuntimeLimits = { maxRequests: 256, runTimeoutMs: 0, maxRequestBytes: 16 * 1024 * 1024, maxResponseBytes: 32 * 1024 * 1024, maxHistoryTurns: 50, imageTokenEstimate: 4096 };
