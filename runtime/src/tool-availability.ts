import type { AuthoritySnapshot, ErrorCode, ToolDescriptor } from './contracts.js';

/** Classify a failed availability precondition, never authorize a tool. The
 * registry must still exclude every non-ready prerequisite. Only the fixed
 * Native selected-screen input projection may request observation recovery. */
export function unavailableToolReason(tool: ToolDescriptor, snapshot: AuthoritySnapshot): ErrorCode {
  const blocked = tool.platformPrerequisites.map(id => ({ id, state: snapshot.availability[id] })).filter(p => p.state?.status !== 'ready');
  if (blocked.some(p => p.state?.status === 'permission_missing')) return 'PERMISSION_MISSING';
  const nativeScreenInput = tool.executor === 'native-device' && tool.effectClass === 'input' && tool.requiredCapabilities.includes('screen_control') &&
    tool.resources.some(r => r === 'selected-screen/snapshot' || r === 'upper-display/snapshot');
  if (!nativeScreenInput || !blocked.length || blocked.some(p => p.id !== tool.toolId || p.state?.status !== 'temporarily_unavailable')) return 'BACKEND_UNAVAILABLE';
  const reasons = blocked.map(p => p.state?.reasonCode);
  // A hard refusal has priority over a recoverable stale observation. Missing,
  // unknown, or prose-only reasons remain genuine generic unavailability.
  if (reasons.some(reason => !['TARGET_CHANGED', 'PERMISSION_MISSING', 'SECURE_CONTENT', 'DISPLAY_GONE'].includes(reason ?? ''))) return 'BACKEND_UNAVAILABLE';
  for (const reason of ['PERMISSION_MISSING', 'SECURE_CONTENT', 'DISPLAY_GONE', 'TARGET_CHANGED'] as const) if (reasons.includes(reason)) return reason;
  return 'BACKEND_UNAVAILABLE';
}
