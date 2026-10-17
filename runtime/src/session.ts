import { Agent } from '@earendil-works/pi-agent-core';
import type { PiHistoryState } from './pi-history.js';
import type { DomainMessage } from './contracts.js';

/** Active Pi session holder. Each execution rebinds Native ports and authority;
 * Pi's original entries survive failures and follow-ups, never crossing session IDs.
 * Native stores those opaque entries; Pi owns their current context projection.
 */
export class RuntimeSession {
  agent?: Agent;
  /** Exact Pi snapshot; product hosts persist it and release the idle instance. */
  piSnapshot?: PiHistoryState;
  constructor(readonly sessionId: string) {}
}

/** Close the UI/audit projection of a partial round. Never used as Pi history. */
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
