/** Provider metadata is untrusted. Retain only bounded numeric counters and local labels. */
const reasons = new Set(['max_output_tokens', 'content_filter', 'length', 'max_tokens']);
const stops = new Set(['pending', 'stop', 'length', 'toolUse', 'error', 'aborted', 'deferred']);
function object(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}
function count(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}
export class ModelRoundDiagnostics {
  private values: Record<string, number | string>;
  constructor(round: number) { this.values = { modelRound: round }; }
  private number(key: string, value: unknown): void {
    const safe = count(value); if (safe !== undefined) this.values[key] = safe;
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
      this.number('inputTokens', usage.input_tokens); this.number('outputTokens', usage.output_tokens);
      this.number('reasoningTokens', object(usage.output_tokens_details)?.reasoning_tokens);
    } else if (protocol === 'openai-completions') {
      const usage = object(e.usage);
      if (usage) {
        this.number('inputTokens', usage.prompt_tokens); this.number('outputTokens', usage.completion_tokens);
        this.number('reasoningTokens', object(usage.completion_tokens_details)?.reasoning_tokens);
      }
      if (Array.isArray(e.choices)) for (const inputChoice of e.choices) {
        const choice = object(inputChoice); if (!choice || choice.index !== 0) continue;
        const reason = choice.finish_reason;
        if (reason === 'length' || reason === 'content_filter') this.values.incompleteReason = reason;
      }
    } else if (protocol === 'anthropic-messages') {
      const usage = object(e.type === 'message_start' ? object(e.message)?.usage : e.usage);
      if (usage) { this.number('inputTokens', usage.input_tokens); this.number('outputTokens', usage.output_tokens); }
      if (object(e.delta)?.stop_reason === 'max_tokens') this.values.incompleteReason = 'max_tokens';
    }
  }
  finish(stopReason: unknown): void {
    this.values.stopReason = typeof stopReason === 'string' && stops.has(stopReason) ? stopReason : 'unknown';
  }
  snapshot(): Record<string, number | string> { return { ...this.values }; }
}
