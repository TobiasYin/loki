import type { ToolDescriptor, ToolPack } from './contracts.js';

/** Target-free Native timer. It grants no screen, network or shell authority. */
export const agentSleepTool: ToolDescriptor = {
  toolId: 'agent.sleep', modelName: 'agent_sleep', schemaVersion: '1',
  description: 'Wait without blocking other tasks for durationMs (integer 1..60000 milliseconds). Use for animations, loading or a deliberate pause; then observe the current state again before acting. Waiting does not prove that a page or operation succeeded. Prefer a suitable delay to rapid polling. Stop cancels the wait immediately. This never sleeps the device or changes either display.',
  inputSchema: { type: 'object', properties: { durationMs: { type: 'integer', minimum: 1, maximum: 60000 } }, required: ['durationMs'], additionalProperties: false },
  requiredCapabilities: [], platformPrerequisites: ['agent.sleep'], executor: 'native-agent', resources: ['agent-runtime'], effectClass: 'read',
  timeoutMs: 65000, cancellable: true, concurrencyKey: 'agent', provenance: 'builtin', modelVisibility: true,
  outputPolicy: { maxTextBytes: 1024, maxImageBytes: 0, maxImages: 0, imageTokenEstimate: 4096 },
};

// Canonical equality prevents this capability-free exception from admitting
// renamed tools, expanded schemas, alternate executors or resource grants.
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(',')}}`;
  return JSON.stringify(value);
}
const sleepContract = canonical(agentSleepTool);
export function isBuiltinAgentSleep(pack: ToolPack, tool: ToolDescriptor): boolean {
  return pack.packId === 'thor.agent' && pack.packVersion === '1' && canonical(tool) === sleepContract;
}
