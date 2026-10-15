import OpenAI from 'openai';
import Anthropic from '@anthropic-ai/sdk';

// Pi 1.2.0 forwards timeoutMs to these pinned SDKs, where 0 normally arms an
// immediate timeout. Adapt their transport hook only for our marked Native
// fetch and the explicit zero sentinel. Finite calls and other SDK clients
// retain their original implementation. Native owns cancellation and connect
// failures; no SDK/model-generation timer is installed for this path.
const unbounded = new WeakSet<Function>();
type TransportClient={fetch:typeof fetch};
type TimeoutHook=(this:TransportClient,url:RequestInfo|URL,init:RequestInit|undefined,ms:number,controller:AbortController)=>Promise<Response>;
for(const sdk of [OpenAI,Anthropic]) {
  const prototype=sdk.prototype as unknown as {fetchWithTimeout:TimeoutHook};
  const original=prototype.fetchWithTimeout;
  prototype.fetchWithTimeout=async function(url,init,ms,controller) {
    if(ms!==0 || !unbounded.has(this.fetch))return original.call(this,url,init,ms,controller);
    const signal=init?.signal;
    const abort=()=>controller.abort(signal?.reason);
    signal?.addEventListener('abort',abort,{once:true});
    if(signal?.aborted)abort();
    try {return await this.fetch(url,{...init,signal:controller.signal});}
    finally {signal?.removeEventListener('abort',abort);}
  };
}
export function withoutProviderDeadline(fetcher:typeof fetch):typeof fetch {
  unbounded.add(fetcher);return fetcher;
}
