import type { Model } from '@earendil-works/pi-ai';
import { SessionManager, type FileEntry, type SessionEntry, type SessionHeader } from '@thor-agent/pi-session-browser';
import { CAPABILITIES, RuntimeError, type AttachmentPort, type AuthoritySnapshot, type Capability, type Content } from './contracts.js';
import { resolveContent } from './context.js';

/** Native owns storage and data provenance. Pi alone projects the stored entry tree. */
export interface PiHistoryState {
  header: SessionHeader;
  entries: SessionEntry[];
  leafId: string | null;
  provenance: Record<string, Capability[]>;
}
export interface PreparedPiHistory extends PiHistoryState {
  source: Capability[];
  inputRunIds: Record<string, string>;
}
export interface PendingPiInput { runId: string; content: Content[] }
const union = (...values: Capability[][]): Capability[] => CAPABILITIES.filter(cap => values.some(value => value.includes(cap)));
const isUser = (entry: SessionEntry): boolean => entry.type === 'message' && entry.message.role === 'user';
const hasImages = (message: { content?: unknown }): boolean => Array.isArray(message.content) && message.content.some(block => block && typeof block === 'object' && block.type === 'image');
const isEditable = (entry: SessionEntry): boolean => entry.type === 'custom_message' || entry.type === 'message' && ['user', 'assistant', 'toolResult'].includes(entry.message.role);
function createManager(state: PiHistoryState | undefined, snapshot: AuthoritySnapshot): SessionManager {
  if (state && state.header.id !== snapshot.session.sessionId) throw new RuntimeError('STALE_AUTHORIZATION', 'Pi session identity mismatch');
  const file: FileEntry[] | undefined = state ? [structuredClone(state.header), ...structuredClone(state.entries)] : undefined;
  const manager = SessionManager.inMemory('/thor', { id: snapshot.session.sessionId }, file);
  if (state?.leafId === null) manager.resetLeaf();
  else if (state?.leafId !== undefined) manager.branch(state.leafId);
  return manager;
}
function activeSource(manager: SessionManager, provenance: Record<string, Capability[]>): Capability[] {
  // Compatibility for an older checkpoint without its own provenance record:
  // infer dependencies without modifying the persisted message or metadata.
  const inferred = new Map<string, Capability[]>(); let inherited: Capability[] = [];
  for (const entry of manager.getBranch()) {
    const sources = provenance[entry.id] ?? ((entry.type === 'compaction' || entry.type === 'branch_summary') ? inherited : []);
    inferred.set(entry.id, sources); inherited = union(inherited, sources);
  }
  return union(...manager.buildSessionProjection().entries.filter(entry => entry.messages.length > 0 && (!isUser(entry.sourceEntry) || entry.messages.some(message => hasImages(message as any)))).map(entry => inferred.get(entry.sourceEntry.id) ?? []));
}

/** Restore native Pi entries verbatim; no DomainTurn conversion is supported.
 * Permission revocation only appends Pi context edits or creates an authorized branch.
 * It never slices history, estimates tokens, summarizes content or runs a tool.
 */
export async function preparePiHistory(
  state: PiHistoryState | undefined | null, snapshot: AuthoritySnapshot,
  model: Model<any>, attachments: AttachmentPort, pendingInputs: PendingPiInput[] = [],
): Promise<PreparedPiHistory> {
  const manager = createManager(state ?? undefined, snapshot);
  const provenance: Record<string, Capability[]> = structuredClone(state?.provenance ?? {});
  const enabled = snapshot.session.enabled;
  const inputRunIds: Record<string, string> = structuredClone((state as PreparedPiHistory | undefined)?.inputRunIds ?? {});
  const markerType = 'thor-committed-input';
  const markedRuns = new Set(manager.getEntries().filter(entry => entry.type === 'custom' && entry.customType === markerType)
    .map(entry => (entry as any).data?.runId).filter((id): id is string => typeof id === 'string'));
  const markInput = (entryId: string, runId: string) => {
    inputRunIds[entryId] = runId;
    const marker = manager.appendCustomEntry(markerType, { runId, userEntryId: entryId });
    provenance[marker] = []; markedRuns.add(runId);
  };
  const finish = async (result: Omit<PreparedPiHistory, 'inputRunIds'>): Promise<PreparedPiHistory> => {
    let appended = false;
    for (const pending of pendingInputs) {
      if (markedRuns.has(pending.runId)) continue;
      const resolved = await resolveContent(pending.content, attachments, enabled);
      const message = { role: 'user' as const, timestamp: Date.now(), content: resolved.map(block => block.type === 'image' && !model.input.includes('image')
        ? { type: 'text' as const, text: '[Image unavailable: selected model supports text only]' } : block) };
      const entryId = manager.appendMessage(message);
      const sources: Capability[][] = [];
      if (hasImages(message)) for (const content of pending.content) if (content.type === 'attachment') {
        const attachment = await attachments.get(content.attachmentId);
        if (attachment) sources.push(attachment.source.filter(cap => enabled[cap]));
      }
      provenance[entryId] = union(...sources);
      markInput(entryId, pending.runId); appended = true;
    }
    return appended ? { header: manager.getHeader()!, entries: manager.getEntries(), leafId: manager.getLeafId(), provenance,
      source: activeSource(manager, provenance), inputRunIds } : { ...result, inputRunIds };
  };
  if (!state) return finish({ header: manager.getHeader()!, entries: manager.getEntries(), leafId: manager.getLeafId(), provenance, source: [] });

  let imageEdited = false;
  const originalBranch = manager.getBranch();
  const priorEdits = new Map(originalBranch.filter(entry => entry.type === 'context_edit').map(entry => [entry.targetId, entry.replacement]));
  // Include user images behind a compaction boundary: an authorized branch
  // rebuild below may make their original requests visible again.
  for (const entry of originalBranch) {
    if (entry.type !== 'message' || entry.message.role !== 'user' || !(provenance[entry.id] ?? []).some(cap => !enabled[cap])) continue;
    if (priorEdits.has(entry.id) && priorEdits.get(entry.id) === null) continue;
    const contentBefore = priorEdits.get(entry.id)?.content ?? entry.message.content;
    if (!hasImages({ content: contentBefore }) || !Array.isArray(contentBefore)) continue;
    const content = contentBefore.map((block: any) => block.type === 'image'
      ? { type: 'text', text: '[Image unavailable: source permission was revoked]' } : structuredClone(block));
    provenance[manager.appendContextEdit(entry.id, { content })] = [];
    imageEdited = true;
  }
  const branch = manager.getBranch();
  const unsafe = new Set<string>();
  let inherited: Capability[] = [];
  for (const entry of branch) {
    const sources = provenance[entry.id] ?? ((entry.type === 'compaction' || entry.type === 'branch_summary') ? inherited : []);
    if (!isUser(entry) && sources.some(cap => !enabled[cap])) unsafe.add(entry.id);
    inherited = union(inherited, sources);
    // A replacement can derive from revoked content even if the original target did not.
    if (entry.type === 'context_edit' && entry.replacement !== null && sources.some(cap => !enabled[cap])) unsafe.add(entry.targetId);
  }
  // An assistant may contain several calls. Removing any result removes its whole
  // assistant batch and every paired result, so no orphan can reach a provider.
  let changed = true;
  while (changed) {
    changed = false;
    for (const entry of branch) {
      if (entry.type !== 'message' || entry.message.role !== 'assistant') continue;
      const calls = entry.message.content.filter(block => block.type === 'toolCall').map(block => block.id);
      if (!calls.length) continue;
      const results = branch.filter(candidate => candidate.type === 'message' && candidate.message.role === 'toolResult' && calls.includes(candidate.message.toolCallId));
      if (unsafe.has(entry.id) || results.some(result => unsafe.has(result.id))) for (const paired of [entry, ...results]) {
        if (!unsafe.has(paired.id)) { unsafe.add(paired.id); changed = true; }
      }
    }
  }
  // Already omitted raw entries do not require another edit on every restoration.
  const visible = manager.buildSessionProjection().entries.filter(entry => entry.messages.length > 0).map(entry => entry.sourceEntry);
  const visibleUnsafe = visible.filter(entry => unsafe.has(entry.id) && !isUser(entry));
  if (!visibleUnsafe.length) return finish(imageEdited
    ? { header: manager.getHeader()!, entries: manager.getEntries(), leafId: manager.getLeafId(), provenance, source: activeSource(manager, provenance) }
    : { ...structuredClone(state), source: activeSource(manager, provenance) });

  const safeSource = union(...visible.filter(entry => !unsafe.has(entry.id) && !isUser(entry)).map(entry => provenance[entry.id] ?? [])).filter(cap => enabled[cap]);
  if (visibleUnsafe.some(entry => !isEditable(entry))) {
    // Summary entries are not editable upstream. Preserve the entire old tree,
    // detach the active leaf, and append authorized original entries to a fresh
    // Pi branch. No unsafe summary is ever replayed into the new branch.
    const edits = new Map(branch.filter(entry => entry.type === 'context_edit').map(entry => [entry.targetId, entry.replacement]));
    manager.resetLeaf();
    let copiedSources: Capability[] = [];
    for (const entry of branch) {
      if (unsafe.has(entry.id) && !isUser(entry)) continue;
      if (edits.has(entry.id) && edits.get(entry.id) === null) continue;
      let id: string | undefined;
      const replacement = edits.get(entry.id);
      if (entry.type === 'message') {
        const message = structuredClone(entry.message);
        if (replacement && ['user', 'assistant', 'toolResult'].includes(message.role)) (message as any).content = structuredClone(replacement.content);
        id = manager.appendMessage(message as Parameters<SessionManager['appendMessage']>[0]);
      } else if (entry.type === 'custom_message') {
        id = manager.appendCustomMessageEntry(entry.customType, structuredClone(replacement?.content ?? entry.content) as any, entry.display, structuredClone(entry.details));
      }
      if (id) {
        const own = provenance[entry.id] ?? [];
        const copied = manager.getEntry(id);
        provenance[id] = isUser(entry) ? (copied?.type === 'message' && hasImages(copied.message as any) ? own.filter(cap => enabled[cap]) : []) : union(copiedSources, own).filter(cap => enabled[cap]);
        if (!isUser(entry)) copiedSources = union(copiedSources, provenance[id]!);
      }
    }
  } else {
    for (const entry of visibleUnsafe) provenance[manager.appendContextEdit(entry.id, null)] = [...safeSource];
  }
  return finish({ header: manager.getHeader()!, entries: manager.getEntries(), leafId: manager.getLeafId(), provenance,
    source: activeSource(manager, provenance) });
}
