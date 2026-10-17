import type { AssistantMessage, Message, ToolCall } from '@earendil-works/pi-ai';
import { SessionManager } from '@thor-agent/pi-session-browser';
import type { Capability } from '../src/contracts.js';
import type { PiHistoryState } from '../src/pi-history.js';
import { ZERO_USAGE } from '../src/context.js';
import type { fixture } from './fixtures.js';

/** Synthetic fixtures use the official manager's native records, never DomainTurn imports. */
export function piAssistant(f: ReturnType<typeof fixture>, text: string, calls: ToolCall[] = []): AssistantMessage {
  const profile=f.snapshot.session.model;
  return { role:'assistant',content:[...(text?[{type:'text' as const,text}]:[]),...structuredClone(calls)],
    api:profile.protocol,provider:profile.provider==='opencode-go'?'opencode-go':profile.provider==='deepseek'?'deepseek':profile.protocol==='anthropic-messages'?'anthropic':'openai',
    model:profile.modelId,usage:structuredClone(ZERO_USAGE),stopReason:calls.length?'toolUse':'stop',timestamp:1 };
}
export function nativePiState(f: ReturnType<typeof fixture>, messages: Message[], source: Capability[] = []): PiHistoryState {
  const manager=SessionManager.inMemory('/thor',{id:f.lease.sessionId});
  const provenance:Record<string,Capability[]>={};
  for(const message of messages)provenance[manager.appendMessage(structuredClone(message))]=message.role==='user'?[]:[...source];
  return {header:manager.getHeader()!,entries:manager.getEntries(),leafId:manager.getLeafId(),provenance};
}
