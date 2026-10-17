import { McpServerRegistry } from "../mcp-servers.js";
export function createExtensionRuntime() {
    const notInitialized = () => {
        throw new Error("Extension runtime not initialized. Action methods cannot be called during extension loading.");
    };
    const state = {};
    const eventBusUnsubscribers = new Set();
    const assertActive = () => {
        if (state.staleMessage) {
            throw new Error(state.staleMessage);
        }
    };
    const runtime = {
        sendMessage: notInitialized,
        sendUserMessage: notInitialized,
        appendEntry: notInitialized,
        setSessionName: notInitialized,
        getSessionName: notInitialized,
        setLabel: notInitialized,
        getActiveTools: notInitialized,
        getAllTools: notInitialized,
        getSettings: notInitialized,
        setActiveTools: notInitialized,
        // registerTool() is valid during extension load; refresh is only needed post-bind.
        refreshTools: () => { },
        getCommands: notInitialized,
        setModel: () => Promise.reject(new Error("Extension runtime not initialized")),
        getThinkingLevel: notInitialized,
        setThinkingLevel: notInitialized,
        flagValues: new Map(),
        pendingProviderRegistrations: [],
        pendingNativeProviderRegistrations: [],
        mcpServers: new McpServerRegistry(),
        pendingVirtualModelRegistrations: [],
        createContext: notInitialized,
        assertActive,
        invalidate: (message) => {
            if (state.staleMessage)
                return;
            state.staleMessage =
                message ??
                    "This extension ctx is stale after session replacement or reload. Do not use a captured pi or command ctx after ctx.newSession(), ctx.fork(), ctx.switchSession(), or ctx.reload(). For newSession, fork, and switchSession, move post-replacement work into withSession and use the ctx passed to withSession. For reload, do not use the old ctx after await ctx.reload().";
            for (const unsubscribe of eventBusUnsubscribers)
                unsubscribe();
            eventBusUnsubscribers.clear();
        },
        trackEventBusSubscription: (unsubscribe) => {
            let active = true;
            const trackedUnsubscribe = () => {
                if (!active)
                    return;
                active = false;
                eventBusUnsubscribers.delete(trackedUnsubscribe);
                unsubscribe();
            };
            eventBusUnsubscribers.add(trackedUnsubscribe);
            return trackedUnsubscribe;
        },
        // Pre-bind: queue registrations so bindCore() can flush them once the
        // model registry is available. bindCore() replaces both with direct calls.
        registerProvider: (name, config, extensionPath = "<unknown>") => {
            runtime.pendingProviderRegistrations.push({ name, config, extensionPath });
        },
        registerNativeProvider: (provider, extensionPath = "<unknown>") => {
            runtime.pendingNativeProviderRegistrations.push({ provider, extensionPath });
        },
        unregisterProvider: (name) => {
            runtime.pendingProviderRegistrations = runtime.pendingProviderRegistrations.filter((r) => r.name !== name);
            runtime.pendingNativeProviderRegistrations = runtime.pendingNativeProviderRegistrations.filter((r) => r.provider.id !== name);
        },
        registerVirtualModel: (definition, extensionPath = "<unknown>") => {
            runtime.pendingVirtualModelRegistrations.push({ definition, extensionPath });
        },
        unregisterVirtualModel: (provider, id) => {
            runtime.pendingVirtualModelRegistrations = runtime.pendingVirtualModelRegistrations.filter(({ definition }) => definition.provider !== provider || definition.id !== id);
        },
    };
    return runtime;
}