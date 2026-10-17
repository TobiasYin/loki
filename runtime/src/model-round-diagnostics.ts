/** Provider metadata is untrusted. Retain only bounded numeric counters and local labels. */
const reasons = new Set(['max_output_tokens', 'content_filter', 'length', 'max_tokens']);
const stops = new Set(['pending', 'stop', 'length', 'toolUse', 'error', 'aborted', 'deferred']);
function object(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}
function count(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}
function firstCount(...values: unknown[]): number | undefined {
  for (const value of values) { const safe = count(value); if (safe !== undefined) return safe; }
  return undefined;
}
export class ModelRoundDiagnostics {
  private values: Record<string, number | string>;
  private inputExcludesCache = false;
  constructor(round: number) { this.values = { modelRound: round }; }
  private number(key: string, value: unknown): void {
    const safe = count(value); if (safe !== undefined) this.values[key] = safe;
  }
  private usage(protocol: string, usage: Record<string, unknown>): void {
    this.inputExcludesCache = protocol === 'anthropic-messages';
    const input = protocol === 'openai-completions' ? usage.prompt_tokens : usage.input_tokens;
    const output = protocol === 'openai-completions' ? usage.completion_tokens : usage.output_tokens;
    const details = object(protocol === 'openai-completions' ? usage.prompt_tokens_details : usage.input_tokens_details);
    this.number('inputTokens', input); this.number('outputTokens', output);
    this.number('cacheReadTokens', firstCount(details?.cached_tokens, usage.prompt_cache_hit_tokens, usage.cache_read_input_tokens, usage.cached_tokens));
    this.number('cacheWriteTokens', firstCount(details?.cache_write_tokens, details?.cache_creation_tokens, usage.cache_creation_input_tokens));
    this.number('uncachedInputTokens', usage.prompt_cache_miss_tokens);
    this.number('reasoningTokens', object(protocol === 'openai-completions' ? usage.completion_tokens_details : usage.output_tokens_details)?.reasoning_tokens);
  }
  payload(protocol: string, input: unknown): void {
    const p = object(input); if (!p) return;
    this.number('requestedMaxOutputTokens', protocol === 'openai-responses' ? p.max_output_tokens : protocol === 'openai-completions' ? p.max_completion_tokens ?? p.max_tokens : p.max_tokens);
  }
  observe(protocol: string, input: unknown): void {
    const e = object(input); if (!e) return;
    if (protocol === 'openai-responses' && typeof e.type === 'string' && ['response.completed', 'response.incomplete', 'response.failed'].includes(e.type)) {
      const response = object(e.response); if (!response) return;
      const reason = object(response.incomplete_details)?.reason;
      if (e.type === 'response.incomplete' || reason !== undefined) this.values.incompleteReason = typeof reason === 'string' && reasons.has(reason) ? reason : 'unknown';
      const usage = object(response.usage); if (!usage) return;
      this.usage(protocol, usage);
    } else if (protocol === 'openai-completions') {
      const usage = object(e.usage);
      if (usage) {
        this.usage(protocol, usage);
      }
      if (Array.isArray(e.choices)) for (const inputChoice of e.choices) {
        const choice = object(inputChoice); if (!choice || choice.index !== 0) continue;
        const reason = choice.finish_reason;
        if (reason === 'length' || reason === 'content_filter') this.values.incompleteReason = reason;
      }
    } else if (protocol === 'anthropic-messages') {
      const usage = object(e.type === 'message_start' ? object(e.message)?.usage : e.usage);
      if (usage) this.usage(protocol, usage);
      if (object(e.delta)?.stop_reason === 'max_tokens') this.values.incompleteReason = 'max_tokens';
    }
  }
  finish(stopReason: unknown): void {
    this.values.stopReason = typeof stopReason === 'string' && stops.has(stopReason) ? stopReason : 'unknown';
  }
  snapshot(): Record<string, number | string> {
    const values = { ...this.values };
    const input = count(values.inputTokens), read = count(values.cacheReadTokens), write = count(values.cacheWriteTokens);
    if (input !== undefined) {
      // OpenAI/DeepSeek input includes cache reads/writes; Anthropic input excludes both.
      const total = this.inputExcludesCache ? input + (read ?? 0) + (write ?? 0) : input;
      if (Number.isSafeInteger(total)) values.totalInputTokens = total;
      if (this.inputExcludesCache) values.uncachedInputTokens = input;
      else if (values.uncachedInputTokens === undefined && (read !== undefined || write !== undefined)) {
        const uncached = input - (read ?? 0) - (write ?? 0);
        if (Number.isSafeInteger(uncached) && uncached >= 0) values.uncachedInputTokens = uncached;
      }
    }
    // Missing provider counters stay absent: a missing cache report is not a 0% hit rate.
    return values;
  }
}
