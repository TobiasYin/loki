import type { Agent, AgentTool } from '@earendil-works/pi-agent-core';
import type { Model } from '@earendil-works/pi-ai';
import { AgentSession, type AgentSessionConfig } from './core/agent-session.js';
import { SessionManager, type FileEntry, type SessionHeader } from './core/session-manager.js';
import { SettingsManager } from './core/settings-manager.js';
export { AgentSession } from './core/agent-session.js';
export type { AgentSessionEvent, AgentSessionEventListener, PromptOptions } from './core/agent-session.js';
export { SessionManager, buildSessionContext, buildSessionProjection, CURRENT_SESSION_VERSION } from './core/session-manager.js';
export type { FileEntry, SessionEntry, SessionHeader, SessionProjection } from './core/session-manager.js';
export { SettingsManager } from './core/settings-manager.js';
export { convertToLlm } from './core/messages.js';
export * from './core/compaction/index.js';
export interface PiSessionOptions {
  agent: Agent;
  sessionId: string;
  entries?: FileEntry[];
  header?: SessionHeader;
  leafId?: string | null;
  model: Model<any>;
  models?: Model<any>[];
  systemPrompt: string;
  tools: AgentTool[];
  settings?: object;
  cwd?: string;
  resourceLoader?: AgentSessionConfig['resourceLoader'];
  modelRuntime?: AgentSessionConfig['modelRuntime'];
}
export interface PiSessionHandle {
  session: AgentSession;
  manager: SessionManager;
  settingsManager: SettingsManager;
  snapshot(): { entries: FileEntry[]; leafId: string | null };
  dispose(): void;
}
export function createPiSession(options: PiSessionOptions): PiSessionHandle;
