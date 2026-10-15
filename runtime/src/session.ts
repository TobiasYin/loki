import { Agent } from '@earendil-works/pi-agent-core';
import type { DomainMessage } from './contracts.js';

/** One Pi Agent per conversation. Each execution rebinds Native ports and authority;
 * the transcript survives failures and follow-ups, never crossing session IDs.
 * Native's persisted, budgeted projection remains authoritative after restart/revocation.
 */
export class RuntimeSession {
  agent?: Agent;
  constructor(readonly sessionId: string) {}
}

/** Close a recorded partial tool round as unknown; never replay pending calls on recovery. */
export function closeInterruptedRound(messages: DomainMessage[]): DomainMessage[] {
  const result: DomainMessage[] = [];
  const pending = new Map<string, string>();
  const close = () => {
    for (const [toolCallId, toolName] of pending) result.push({ kind:'tool_result',toolCallId,toolName,isError:true,
      content:[{type:'text',text:'Previous execution interrupted before a confirmed result. Outcome unknown; verify state before repeating any side effect.'}] });
    pending.clear();
  };
  for (const message of messages) {
    if(message.kind!=='tool_result')close();
    if(message.kind==='assistant') {
      result.push(structuredClone(message));
      for(const call of message.calls)pending.set(call.id,call.name);
    } else if(message.kind==='tool_result') {
      if(pending.get(message.toolCallId)===message.toolName) {result.push(structuredClone(message));pending.delete(message.toolCallId);}
    } else result.push(structuredClone(message));
  }
  close();return result;
}
