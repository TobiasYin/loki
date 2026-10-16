import type { AssistantMessage, AssistantMessageEvent, Model } from '@earendil-works/pi-ai';
import { AssistantMessageEventStream } from '@earendil-works/pi-ai/utils/event-stream';

export type ProviderWait = 'provider_next' | 'provider_result';
type Wait = <T>(phase: ProviderWait, work: () => Promise<T>) => Promise<T>;

/** Give Pi its own terminal event on explicit cancellation. The original SDK
 * may fail to finish an iterator/result promise; it never owns Pi's stop state.
 * No generation deadline is added, and no tool or persistence runs in this pump.
 */
export function providerStopBoundary(source: AssistantMessageEventStream, signal: AbortSignal, model: Model<any>, wait?: Wait): AssistantMessageEventStream {
  const output = new AssistantMessageEventStream();
  let finished = false;
  let partial: AssistantMessage = { role: 'assistant', content: [], api: model.api, provider: model.provider, model: model.id,
    usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
    stopReason: 'aborted', timestamp: Date.now() };
  const iterator = source[Symbol.asyncIterator]();
  const closeSource = () => { try { void iterator.return?.().catch(() => {}); } catch { /* Best-effort SDK cleanup must not own cancellation. */ } };
  const finish = (event: AssistantMessageEvent) => {
    if (finished) return;
    finished = true; signal.removeEventListener('abort', abort);
    output.push(event); output.end(); closeSource();
  };
  const abort = () => {
    // Detach from mutable SDK partial buffers before any late source event.
    const message = structuredClone(partial);
    message.stopReason = 'aborted'; message.errorMessage = 'CANCELLED';
    finish({ type: 'error', reason: 'aborted', error: message });
  };
  signal.addEventListener('abort', abort, { once: true });
  if (signal.aborted) abort();
  const pending = <T>(phase: ProviderWait, work: () => Promise<T>) => {
    const bounded = () => new Promise<T>((resolve, reject) => {
      const cancelled = () => reject(new Error('CANCELLED'));
      if (signal.aborted) { cancelled(); return; }
      signal.addEventListener('abort', cancelled, { once: true });
      try { work().then(resolve, reject).finally(() => signal.removeEventListener('abort', cancelled)); }
      catch (error) { signal.removeEventListener('abort', cancelled); reject(error); }
    });
    return wait ? wait(phase, bounded) : bounded();
  };
  void (async () => {
    try {
      while (!finished) {
        const next = await pending('provider_next', () => iterator.next());
        if (finished) return;
        if (next.done) {
          const message = await pending('provider_result', () => source.result());
          if (finished) return;
          if (message.stopReason === 'pending') { message.stopReason = 'error'; message.errorMessage = 'MODEL_STREAM_FAILED'; }
          finish(message.stopReason === 'error' || message.stopReason === 'aborted'
            ? { type: 'error', reason: message.stopReason, error: message }
            : { type: 'done', reason: message.stopReason, message });
          return;
        }
        const event = next.value;
        if ('partial' in event) partial = event.partial;
        if (event.type === 'done' || event.type === 'error') { finish(event); return; }
        output.push(event);
      }
    } catch {
      if (finished) return;
      const message = structuredClone(partial);
      message.stopReason = signal.aborted ? 'aborted' : 'error'; message.errorMessage = signal.aborted ? 'CANCELLED' : 'MODEL_STREAM_FAILED';
      finish({ type: 'error', reason: message.stopReason, error: message });
    }
  })();
  return output;
}
