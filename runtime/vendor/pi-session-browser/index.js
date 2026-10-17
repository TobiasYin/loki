import { AgentSession } from './core/agent-session.js';
import { SessionManager } from './core/session-manager.js';
import { SettingsManager } from './core/settings-manager.js';
import { convertToLlm } from './core/messages.js';
import { createExtensionRuntime } from './core/extensions/browser-runtime.js';

export { AgentSession } from './core/agent-session.js';
export { SessionManager, buildSessionContext, buildSessionProjection, CURRENT_SESSION_VERSION } from './core/session-manager.js';
export { SettingsManager } from './core/settings-manager.js';
export { convertToLlm } from './core/messages.js';
export * from './core/compaction/index.js';

/** Inject browser capabilities into the upstream lifecycle; do not duplicate its lifecycle.
 * The same native session entries flow into SessionManager and back out for storage.
 */
export function createPiSession(options) {
  const { agent, sessionId, model, systemPrompt, tools } = options;
  const cwd = options.cwd ?? '/thor';
  const entries = structuredClone(options.header
    ? [options.header, ...(options.entries ?? []).filter(entry => entry.type !== 'session')]
    : options.entries ?? []);
  const header = entries.find(entry => entry.type === 'session');
  if (header && header.id !== sessionId) throw new Error('Pi session identity mismatch');
  const manager = SessionManager.inMemory(cwd, { id: sessionId }, entries);
  if (options.leafId === null) manager.resetLeaf();
  else if (options.leafId !== undefined) manager.branch(options.leafId);

  const settingsManager = SettingsManager.inMemory({ images: { autoResize: false }, ...options.settings });
  const runtime = createExtensionRuntime();
  const resourceLoader = options.resourceLoader ?? {
    getExtensions: () => ({ extensions: [], errors: [], runtime }),
    getSkills: () => ({ skills: [], diagnostics: [] }),
    getPrompts: () => ({ prompts: [], diagnostics: [] }),
    getThemes: () => ({ themes: [], diagnostics: [] }),
    getAgentsFiles: () => ({ agentsFiles: [] }),
    getSystemPrompt: () => systemPrompt,
    getAppendSystemPrompt: () => [],
    getSystemPromptSource: () => undefined,
    getAppendSystemPromptSources: () => [],
    reload: async () => {},
    extendResources: () => { throw new Error('Use Native resources in the browser session'); },
  };
  // Credentials remain Native-owned. The stream function replaces this fixed placeholder
  // at the existing authenticated network boundary for both normal and summary requests.
  const models = options.models ?? [model];
  const modelRuntime = options.modelRuntime ?? {
    getAuth: async () => ({ auth: { apiKey: 'sk-thor-native-placeholder' }, env: {} }),
    checkAuth: async () => ({ configured: true }),
    hasConfiguredAuth: () => true,
    isUsingOAuth: () => false,
    isUsingSubscription: () => false,
    getModels: () => models,
    getAvailableSnapshot: () => models,
    getAvailable: async () => models,
    getModel: (provider, id) => models.find(candidate => candidate.provider === provider && candidate.id === id),
    getPhysicalModel: (provider, id) => models.find(candidate => candidate.provider === provider && candidate.id === id),
    getProvider: provider => ({ id: provider, name: provider }),
    getError: () => undefined,
    getCompatibilityRequestConfig: () => ({ authHeader: true }),
    refresh: async () => {},
  };
  const context = manager.buildSessionContext();
  agent.sessionId = sessionId;
  agent.convertToLlm = convertToLlm;
  agent.state.model = model;
  agent.state.messages = context.messages;
  // Equivalent to the upstream SDK's initial model/thinking entry setup. The caller's
  // selected model wins when continuing a stored conversation with a new configuration.
  if (!context.model || context.model.provider !== model.provider || context.model.modelId !== model.id) manager.appendModelChange(model.provider, model.id);
  if (context.thinkingLevel !== agent.state.thinkingLevel) manager.appendThinkingLevelChange(agent.state.thinkingLevel);
  const session = new AgentSession({
    agent, sessionManager: manager, settingsManager, cwd, resourceLoader, modelRuntime,
    baseToolsOverride: Object.fromEntries(tools.map(tool => [tool.name, tool])),
    initialActiveToolNames: tools.map(tool => tool.name),
  });
  return {
    session, manager, settingsManager,
    snapshot: () => ({ entries: structuredClone([manager.getHeader(), ...manager.getEntries()]), leafId: manager.getLeafId() }),
    dispose: () => session.dispose(),
  };
}
