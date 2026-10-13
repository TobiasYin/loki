/** Additional strict JSON guard around Pi's intentionally forgiving partial-JSON parser. */
export class ProtocolAudit {
  private valid = new Map<string, unknown>();
  private blocks = new Map<number, { id: string; raw: string; receivedDelta: boolean }>();
  observe(protocol: string, input: unknown): void {
    const e = input as any;
    if (protocol === 'openai-responses' && e.type === 'response.output_item.done' && e.item?.type === 'function_call') {
      this.accept(`${e.item.call_id}|${e.item.id}`, e.item.arguments);
    }
    if (protocol === 'anthropic-messages') {
      if (e.type === 'content_block_start' && e.content_block?.type === 'tool_use') this.blocks.set(e.index, { id: e.content_block.id, raw: JSON.stringify(e.content_block.input ?? {}), receivedDelta: false });
      if (e.type === 'content_block_delta' && e.delta?.type === 'input_json_delta') {
        const b = this.blocks.get(e.index); if (b) { if (!b.receivedDelta) b.raw = ''; b.receivedDelta = true; b.raw += e.delta.partial_json; }
      }
      if (e.type === 'content_block_stop') {
        const b = this.blocks.get(e.index); if (b) { this.accept(b.id, b.raw); this.blocks.delete(e.index); }
      }
    }
  }
  private accept(id: string, raw: string): void {
    if (this.valid.has(id)) { this.valid.set(id, undefined); return; }
    try { const value = JSON.parse(raw); this.valid.set(id, value && typeof value === 'object' && !Array.isArray(value) ? value : undefined); } catch { this.valid.set(id, undefined); }
  }
  permits(id: string, args: unknown): boolean { const v = this.valid.get(id); return v !== undefined && JSON.stringify(v) === JSON.stringify(args); }
}
