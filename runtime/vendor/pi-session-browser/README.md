# Pinned Pi AgentSession browser platform

This is a platform port of `@earendil-works/pi-coding-agent@1.2.0`, not another
agent implementation. `core/agent-session.js`, `core/session-manager.js`, message
projection, compaction, branch summary, extension runner, settings and usage
algorithms are copied from the official published npm archive. Their bodies are
unchanged. The adjacent MIT license belongs to upstream Mario Zechner.

`UPSTREAM.json` pins the npm integrity, original SHA-256 and browser-port SHA-256
of each executed module, together with its mechanical import substitutions.
`runtime/test/pi-upstream-provenance.test.ts` checks these hashes. Reproduce the
port from an unpacked official archive with:

```
cd runtime
node scripts/vendor-pi-session.mjs path/to/unpacked/package
```

The generator also copies the official transitive declaration files. It does not
overwrite this package's browser factory, entry declarations or platform adapter.

## Ownership

`createPiSession` injects an already constructed Pi `Agent`, its authenticated
Native stream function, native tools, model, prompt and settings into the real
upstream `AgentSession`. It uses the upstream `SessionManager.inMemory` storage
interface. The same official entries are returned by `snapshot` for Native
persistence; restoring them runs upstream's own migration, tree traversal and
compaction-aware projection. The active leaf is saved/restored separately.

Pi owns prompting, tool loops, follow-ups, retry and overflow recovery, automatic
and manual compaction, summary generation and retained context selection. There
is no Thor history compiler, token-selection policy or separate summary loop in
this package. Both normal and summarization requests call `agent.streamFunction`.

Native owns encrypted credentials, HTTP transport, tools and their permissions,
attachment storage, and persistence of official entries. The page subscribes to
Pi events and projects them for display; display events are not model history.

## Browser boundary

`platform.js` supplies deterministic POSIX-style virtual paths, browser UUID/byte
utilities, a presentation-only skill formatter, and Native-preprocessed image
pass-through. The platform port replaces only imports of filesystem, terminal,
CLI exporters, built-in shell tools and Node image processing. Their CLI-only
operations explicitly throw rather than pretending to execute. The official
extension runner is preserved; filesystem/Jiti extension discovery is replaced by
the injected resource loader. Native skill tools remain the lazy-loading path.

`core/extensions/browser-runtime.js` is the complete, unchanged
`createExtensionRuntime` function extracted from the official extension loader,
using the unmodified official `McpServerRegistry`. No dynamic extension loader or
CLI shell tool is shipped to the WebView.

Images must already be bounded and resized by the Native attachment pipeline.
The local byte adapter does not create global `Buffer` or Node polyfills. Browser
builds define `process` as undefined, so optional guarded Node environment fallbacks
are eliminated. The minified browser bundle has no external imports.

Do not replace the upstream-installed `prepareRequest`, `prepareNextTurn`,
`transformContext`, or `convertToLlm` with a separate Thor message list. Additional
tool authorization checks belong in injected tools or a chained hook. Cancel a
session through `session.abort()` so its own compaction/retry work is cancelled as
well as the underlying Agent.
