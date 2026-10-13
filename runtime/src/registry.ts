import { Value } from 'typebox/value';
import { CAPABILITIES, RuntimeError, type AuthoritySnapshot, type ToolDescriptor, type ToolPack } from './contracts.js';

/** Only explicitly registered, audited build-time packs. No script/plugin loading. */
export class ToolRegistry {
  private packs: ToolPack[] = [];
  private tools = new Map<string, ToolDescriptor>();
  get version(): string { return this.packs.map(p => `${p.packId}@${p.packVersion}`).sort().join('|') || 'empty-v1'; }
  register(pack: ToolPack): void {
    if (!pack.packId || !pack.packVersion || this.packs.some(p => p.packId === pack.packId)) throw new RuntimeError('PROTOCOL_ERROR', 'Duplicate/invalid ToolPack');
    const names = new Set<string>(); const ids = new Set<string>();
    for (const t of pack.tools) {
      if (!/^[a-zA-Z0-9_-]{1,64}$/.test(t.modelName) || this.tools.has(t.modelName) || names.has(t.modelName) || ids.has(t.toolId) || [...this.tools.values()].some(x => x.toolId === t.toolId)) throw new RuntimeError('PROTOCOL_ERROR', 'Duplicate/invalid tool identity');
      const internalProbe = pack.packId === 'configuration-probe' && t.toolId === 'probe.picture' && t.executor === 'native-probe' && t.provenance === 'native-configuration-probe' && t.effectClass === 'read';
      if (!t.executor || !t.schemaVersion || !t.toolId || !t.concurrencyKey || !t.provenance || (!t.requiredCapabilities.length && !internalProbe) || t.requiredCapabilities.some(c => !CAPABILITIES.includes(c))) throw new RuntimeError('UNSUPPORTED', 'Invalid tool contract/capability');
      if (t.effectClass === 'arbitrary-shell' && !t.requiredCapabilities.includes('adb_debug')) throw new RuntimeError('UNSUPPORTED', 'Shell requires adb_debug');
      const schema = t.inputSchema as { type?: string; additionalProperties?: boolean };
      if (t.timeoutMs <= 0 || t.outputPolicy.maxTextBytes < 1 || t.outputPolicy.maxImageBytes < 0 || t.outputPolicy.maxImages < 0 || t.outputPolicy.imageTokenEstimate < 1 || schema.type !== 'object' || schema.additionalProperties !== false) throw new RuntimeError('UNSUPPORTED', 'Tool schema/budget unsupported');
      // Interpret schema without generating executable code. Pi performs its own validation too.
      Value.Check(t.inputSchema, {});
      names.add(t.modelName); ids.add(t.toolId);
    }
    const copy = structuredClone(pack);
    this.packs.push(copy); for (const t of copy.tools) this.tools.set(t.modelName, t);
  }
  get(name: string): ToolDescriptor | undefined { const t = this.tools.get(name); return t && structuredClone(t); }
  effective(snapshot: AuthoritySnapshot): ToolDescriptor[] {
    return [...this.tools.values()].filter(t => t.modelVisibility && t.requiredCapabilities.every(c => snapshot.session.enabled[c]) &&
      t.platformPrerequisites.every(p => snapshot.availability[p]?.status === 'ready')).map(t => structuredClone(t));
  }
  validate(name: string, args: unknown): ToolDescriptor {
    const t = this.get(name);
    if (!t || !Value.Check(t.inputSchema, args)) throw new RuntimeError('PROTOCOL_ERROR', 'Unknown tool or invalid arguments');
    return t;
  }
}
