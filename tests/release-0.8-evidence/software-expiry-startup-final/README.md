# Near-expiry proof reacquisition and startup recovery software snapshot

This directory records the new unified software identity after near-expiry full Presentation-proof reacquisition and the minimal startup persistence/debug phase changes. Independent Thor attempts and new emulator acceptance belong to separate evidence. No Android, cloud, model autonomy or game-goal completion is claimed here.

The current Gradle log reports **BUILD SUCCESSFUL in18s**, with **506 actionable tasks:85 executed,421 up-to-date**. Debug/release packaging, instrumentation compilation and lint completed. Current XML contains **106 lint warnings**, with no Error or Fatal; Gradle deprecation warnings are retained.

## Current JVM evidence

| Module | Tests | Current Gradle task |
| --- | ---: | --- |
| App | 95 | `:app:testDebugUnitTest` executed |
| Device core | 196 | `:device-core:test` executed |
| Native bridge core | 41 | `:core:test` UP-TO-DATE; retained XML |
| Device authority | 13 | `:device-authority:test` executed |
| Input Lab | 74 | `:input-lab:testDebugUnitTest` UP-TO-DATE; retained XML |
| Total | **419 across63 suites** | **0 failures,0 errors,0 skipped**; mixed executed/cached coverage |

The increase from409 to419 consists of **8 proof-refresh cases and2 debug startup-diagnostic cases**, already included in this total. The standalone owner diagnostic2 log is also retained; it is not counted as two additional unique cases. Exact task labels come from the task itself, rather than its `testClasses` preparation task. All current XML files have hashes, sizes and counts in `junit-xml-hashes.json`.

**Three real-SQLite Android instrumentation cases are not part of JVM419.** `StartupRecoveryStorageTest` was compiled and its class name verified in the pinned instrumentation DEX, but this software collector did not execute or accept it. The independent AVD owner will report its runtime results separately.

## Retained same-source checks

| Check | Prior result | This snapshot |
| --- | ---: | --- |
| Runtime | 251/251 | Retained full build/Node log; not rerun |
| Chat Node | 69/69 | Retained, not rerun |
| Bridge Node | 13/13 | Retained, not rerun |
| UI relay Node | 3/3 | Retained, not rerun |
| Integration TypeScript | owner-reported exit0 | Retained empty successful log; not a new check |

All23 Runtime source/test files recorded in the previous scoped-focus snapshot remain byte-identical; `retained-runtime-source-equivalence.json` provides that comparison. The pinned compiled Runtime bundle also matches the prior scoped-focus APK bundle. Prior Chat/Bridge/relay source bytes were checked against the preceding snapshot. Relay retains its original dual-document comparison. Original Bridge13 source hashes were not independently archived, so no new original-check source equivalence is invented. The `prior-*` filenames and `rerun:false` fields distinguish these checks from current JVM execution.

## Narrow behavior covered

- Read-only preflight requests a complete new accessibility–WindowManager–accessibility proof plus package-owner verification when fewer than2000ms remain. It does not renew an entry through cache access.
- A still-valid previous proof remains usable only to its original deadline. New evidence starts its own absolute5s lifetime before the full new reader begins, and must pass reader identity, current window-key, window-set and generation checks before replacing it.
- Failed or late refreshes cannot extend or resurrect old evidence. Target, main-thread and commit checks remain cache-only. Owner/secure/clip checks, bounded waiting and cancellation remain in place; old input is never automatically replayed.
- The2000ms reserve is a scheduling trigger, not a guarantee every tool finishes before expiry. Slow tools or failed proof acquisition can still be refused safely.
- Startup persistence uses a single transaction for session rows, explicitly recovered nonterminal Run rows and selected metadata. It does not delete/rewrite historical messages, events or attachments, alter schema, or change ordinary full-save semantics. Unknown/terminal Run IDs and failure rollback have dedicated Android instrumentation source, whose device acceptance is separate.
- Startup diagnostics use a closed phase enum, bounded four-attempt recorder, timings/counts/UTF-8 sizes only. Release has a separate state-free no-op implementation. Test timeout reports contain booleans/numbers/fixed screen enums, without key/body/title/URL/arguments and without extending45s readiness.

The previous Apped916 Presentation failure remains recorded as an observed expiry at age5006ms, unchanged window-key hash and no intermediate structure/key change. This new snapshot does not erase that failure or transfer any earlier Android pass to the new binaries.

## Fixed package identities

Actual files in `build/emulator/expiry-startup-2026-10-17/run-01/` were read and SHA-256/byte sizes checked against `pins.json`:

| Artifact | SHA-256 |
| --- | --- |
| App debug | `dbedacdcc6f3f9e86803399b7e61e6b45d851ff9b2cc0650198eb664bc8e36fe` |
| App instrumentation | `1bea4c10a729d38a4c4e208a59553b24f19ebe4a3dec6b28c4b62ee0c4117c5c` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Four static artifact inspection entries, including release packages, were also checked against their actual files. Six direct chat assets match source bytes; the Runtime bundle has its own hash. This directory contains no APK binary, raw history or credential.

## Integrity and scope

`source-hashes.json` inventories **70 selected files**, including debug recorder, release no-op, startup phase, Storage, ProductHost, Engine/chat bridge, test Support, three-case SQLite instrumentation source and all current Runtime source/tests. None was modified after the unified Gradle log at collection. This is a selected-file inventory, not an atomic complete build-input archive.

`summary.json` records execution/cache status, log provenance, lint XML identities and explicit acceptance boundaries. `manifest.json` hashes each public file except itself. The collector changed only this new evidence directory, with no source change, build, test execution or device command. Older evidence directories and the shared manifest remain unchanged. New AVD results are intentionally excluded.
