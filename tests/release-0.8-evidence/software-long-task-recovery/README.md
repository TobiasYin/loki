# Long-task recovery software snapshot

**Historical pre-null-normalization snapshot; not final release acceptance.** A separate real Native success receipt with `error:null` later exposed a Runtime classification bug. That fix and its new tests/package are outside this record.

This directory is an independent point-in-time software record for the typed target-availability and bounded display-projection fixes, on top of the split execution/chat architecture. It leaves the earlier `software-dual-document/` files and pins unchanged. It records no Android execution or cloud/game success for this new package.

The fixed debug artifacts are:

| Artifact | SHA-256 |
| --- | --- |
| Agent | `3afa8f9181b39345c5a253ad496ff655d696dc9921ab9230f4b5d400a11c4370` |
| Instrumentation | `570ad02b0d5d2138d82d3d5cd6383333bfa99d60ca37b0d8ee199f3ba008d116` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

`debug-artifact-pins.json` is copied from the owner's fixed local package set and all three files were independently hashed at collection. `artifacts.json` is the static APK identity/permissions check, including unsigned release artifacts; it is not an installation or runtime test.

`gradle.log` records BUILD SUCCESSFUL in 24 seconds: 506 tasks, 89 executed and 417 UP-TO-DATE. The actual retained 59 JUnit XML suites contain **398 tests, 0 failures, 0 errors and 0 skips**: App90, device-core180, core41, device-authority13 and Input Lab74. App, device-core and device-authority test tasks executed; core and Input Lab test tasks were UP-TO-DATE. `junit-xml-hashes.json` identifies every contributing XML, counts and task status. Lint retained 106 warnings across six reports, with no Error/Fatal entries; this is not a zero-warning or clean-build claim. Gradle deprecation warnings remain in the log.

The current Runtime suite passed **182/182**, including the actual Pi/controlled-SSE target-availability cases, and Chat/JS passed **69/69**, including bounded thinking/text display and truncation indicators. Integration TypeScript succeeded with empty output (exit0 reported by the build owner). Bridge13 and relay3 are retained **prior split checks**, explicitly not rerun. Relay source/test hashes match the prior split source record. Current bridge source/test hashes and mtimes are recorded; the owner reports unchanged bridge implementation, without claiming a separate prior bridge source archive. The standalone projection compile/JUnit11 logs are supporting pre-unified checks; their tests overlap the unified App suite and must not be added again to the 398 total.

The display regression crosses 65,536 UTF-16 units cumulatively and with a legal large individual delta, preserves complete event fragments and DomainTurn context, and permits a normal completed commit. It verifies surrogate-safe bounded tails and that malformed incoming data cannot append an event or mutate prior message/run state before validation. Only chat display projections are bounded, with explicit truncation metadata. The real Pi/DomainTurn/event ledger is retained. A fixed Native target reason can request re-observation but cannot authorize input, bypass authority or convert secure/permission refusals into recoverable success.

`source-hashes.json` captures selected production/test sources, including the Runtime, integration relay, service/bridge, Native event projection, UI and regression files. It is per-file capture, not an atomic full source archive. `summary.json` also hashes the compiled chat assets in the fixed APK and checks six direct assets against the corresponding sources. The bundled Runtime is identified by its compiled asset hash; no bundled-JavaScript/TypeScript byte equality claim is made.

The old split package's short/380-second Android results belong to its original package. The new package's emulator/Thor runs, cloud continuation and game progress are separate acceptance work. The software collector performed no Gradle build, device operation, installation or test run; it inspected and preserved results produced by the owning agents. No model wall-clock deadline was added. Process/renderer termination still interrupts and durable context does not authorize effect replay.

`manifest.json` hashes the public files in this directory and excludes itself. No shared manifest, previous evidence, APK, database, raw cloud response, user credential, ROM or game save is included.
