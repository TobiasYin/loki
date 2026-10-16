# Execution-state projection: seven fixed-pair emulator cases passed

The fixed execution-state projection package passed **all seven cases executed on this App/Test pair**, with zero failures, skips or retries. This scoped emulator result is separate from all earlier APK groups and does not claim complete release, cloud, physical-controller or game acceptance.

| Fixed artifact | SHA-256 |
| --- | --- |
| Agent | `69a0f0033c16e34dea83681c420b17fbbc285189f22dd7fb8f98159bce518ae3` |
| Instrumentation | `1bea4c10a729d38a4c4e208a59553b24f19ebe4a3dec6b28c4b62ee0c4117c5c` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only fixed copies from `build/emulator/execution-projection-2026-10-17/run-01/` were installed with data preserved. Actual installed package-file hashes matched before and after. The isolated API36 emulator used ADB 5042 / `127.0.0.1:5557`. No Thor operation or cloud call occurred.

## Actual cases and order

| Group | Passed | Duration | Explicit opt-in |
| --- | --- | --- | --- |
| Presentation Native component | 1 | 48.056 seconds | `emulatorAcceptance=true` |
| Short background 2, Modern UI 1, provider settings 2 | 5 | 62.963 seconds | `emulatorUiComponents=true` |
| Real-time long background | 1 | 397.955 seconds | `longBackgroundAcceptance=true` |

Startup-storage's three cases were not rerun or counted here. Their earlier dbed/1bea result remains separately archived in `../expiry-startup/`; the coordinating owner confirmed unchanged storage source.

## Projection and verification boundary

The product change sends only all session IDs/permission epochs and current Run identity/status/stop notices to the execution document. Chat state, authoritative Native checks, Runtime/Pi context and the database retain their existing data. Missing legacy `activeRuns` alone may fall back to `runs`; explicit empty or null active state remains empty. All concurrent Run stop notices and all session epochs survive the projection.

The actual unified-build XML records five projection JVM cases, all passing with zero failure/error/skip. `execution-projection-jvm.json` records the source XML hash and exact case names. These checks cover complete epochs/concurrent stop, empty-state semantics, independent mutable output rows, input preservation, exclusion of body/credential fields and huge history whose serialization throws. They are prior build verification, not Android instrumentation rerun by this collector. This removes repeated execution-page history serialization; no overall latency or speed-up percentage is measured or promised. Full chat rendering and other initialization costs remain outside this change.

## Presentation and short cases

An independent WindowManager type2037 Presentation with accessibility type-1 received selected-screen screenshot/tree, semantic activation and actual coordinate DOWN/UP. Independent receiver evidence confirmed actions. Old cross-screen snapshot, node and frame references were rejected. Actual secure state was confirmed, protected pixels were withheld and safe capture recovered after the protected state ended. The fixture permission was dropped before tools. No model autonomy or physical gamepad injection is claimed.

The bounded tail64 includes remaining0 refresh scheduling, a real full metadata read completed in 110 ms and a stored proof. Fresh hits decreased from 4766 to 2978 ms against the fixed five-second lifetime; cache hits did not renew expiry. This retained tail does not establish the 1,999 ms near-expiry branch was exercised or explain older dbed missing-reader traces. Prior failed groups remain unchanged.

Modern UI used actual trusted touch/keyboard on synthetic state, intercepted Native commands and verified disclosure animation, inline controls and capability-failure rollback. Provider cases used disposable fixture credentials and controlled loopback, not official cloud credentials. Short background cases used real Native/Pi/local SSE, HOME/Activity finish and return, same execution instance and Pi follow-up, actual notification Stop with partial context retained, and idle writer handoff.

## Real long background and context

The long case measured **380,088 ms** after HOME and Activity destruction. All380 live markers persisted across 13 SQLite checkpoints. The last checkpoint was {"elapsedMs": 380057, "latestMarker": 380, "persistedEventCount": 385, "persistedEventBytes": 131487}. During this interval there was no JavaScript evaluation, UI opening, fake clock, visibility override or outer AVD command.

After returning to chat, the same Host, never-attached execution WebView and Pi session remained. Every marker was present in the full durable turn and the next follow-up HTTP context. Four loopback requests, zero cloud calls and zero device input calls were used. Completion and follow-up occurred after UI return. Long-hidden notification Stop is not covered by this long case; the separate short case tests notification cancellation and retained partial context.

## Preservation and publication

Preflight found 102 existing Runs terminal, the exact AgentRuntimeService absent, no real model key, and the original global baseline matching. At exit all 112 accumulated Runs were terminal and the Runtime service exited. Original IDs and run/message/event/attachment payloads were preserved byte-for-byte. One original selected session's `updatedAt` advanced through normal selection restoration; all other original session fields and the original selection matched. Raw model, vault and display-role XML hashes matched. Original accessibility entries, notification denied state and complete flags, and null overlay/immersive/animator settings were restored. Installed fixed hashes matched.

Only bounded counters, flags, fixed error labels and key hashes are published with the instrumentation logs and scoped reports. No private backup, DB/history, raw accessibility/WM dump, credentials, APK, full cloud response, ROM or game save is published. No production/test source, build or Thor operation was performed by the collector. `manifest.json` covers this directory except itself.
