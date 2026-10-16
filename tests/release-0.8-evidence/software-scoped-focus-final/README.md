# Scoped Presentation invalidation and fixed focus-recovery software snapshot

This directory identifies the unified software build after the narrow Native Presentation-cache fix and fixed focus-mismatch recovery. It records software verification only. New emulator validation, the ongoing Thor model Run, cloud behavior and game completion belong to separate evidence; no earlier Android result is transferred to these APKs.

The current unified Gradle log reports **BUILD SUCCESSFUL in 22s**, with **506 actionable tasks: 89 executed, 417 up-to-date**. Debug/release packaging, Android test compilation and lint completed in that build. Existing Gradle deprecation messages and **106 lint warnings** remain; no error/fatal lint issue was found in the retained XML reports.

## Current checks and retained results

| Check | Result | This build/check |
| --- | ---: | --- |
| App JVM | 93/93 | `:app:testDebugUnitTest` executed |
| Device core JVM | 188/188 | `:device-core:test` executed |
| Native bridge core JVM | 41/41 | `:core:test` UP-TO-DATE; retained XML |
| Device authority JVM | 13/13 | `:device-authority:test` executed |
| Input Lab JVM | 74/74 | `:input-lab:testDebugUnitTest` UP-TO-DATE; retained XML |
| JVM total | **409 tests, 61 suites** | 0 failures, 0 errors, 0 skipped; mixed executed/cached coverage |
| Runtime | **251/251** | Current full Runtime TypeScript/build and Node check |
| Focus classifier standalone JVM | 3/3 | Also included in App93; not three extra unique tests |
| Integration TypeScript | exit0, owner-reported | Successful compiler log is empty |
| Chat Node | 69/69 | Prior result, source bytes unchanged; not rerun |
| Bridge Node | 13/13 | Prior result, source bytes match prior snapshot; not rerun |
| UI relay Node | 3/3 | Prior result, source bytes unchanged; not rerun |

The JVM total increased from398 to409 through **8 Presentation-cache cases and 3 focus-classifier cases**, already included in the totals above. The test-task execution labels are read from exact task names in the current Gradle log; a `testClasses` preparation task is not counted as execution of its `test` task. All current test XML files have recorded size, hash and counts in `junit-xml-hashes.json`.

Retained Chat/Bridge/relay logs are explicitly named `prior-*`. Their current source hashes were compared to the prior legacy-null software snapshot. Relay also retains the original dual-document source comparison. The original Bridge13 source hashes were not independently archived, so this directory does not invent an original-source equivalence claim or present the retained logs as new executions.

## Narrow semantics verified by software checks

- Structure-event invalidation is scoped to a corroborated framework display/window mapping. Display0 is valid. Unknown, ambiguous or contradictory origins still invalidate every cached proof.
- `AccessibilityRecord.getDisplayId()` is guarded by API33; API30–32 use unique current-window mapping or global invalidation. The platform SDK API-version record identifies the method as introduced in33.
- Same-display or global-reset generations reject late asynchronous proof results. An unrelated display event does not revoke another display's proof or its in-flight worker.
- The absolute5s proof TTL, maximum2 cached displays, owner UID/token/type validation, secure-window checks, exact visible clip, selected role and per-tool target checks remain in place. Cache access does not extend lifetime.
- Diagnostics contain at most64 records of fixed reasons, numeric fields and window-key hashes. The instrumentation report reads them passively; it does not warm proofs or alter targets. No raw dumps, window titles, text or tokens are emitted through this trace.
- Focus recovery uses fixed local mismatch classifications. Runtime continues the same Run through a fresh observation, without replaying old inputs, changing system focus, or treating arbitrary error strings as recovery authority.
- Native successful `error:null` handling and the explicit historical tool-null projection remain covered by the full Runtime251 suite.

The two Presentation failures recorded under prior App396c remain failures. Their precise invalidation event was not captured; the scoped-invalidation defect is established from the old code path and isolated regression tests, not retroactively asserted as the proven cause of both device failures. New Android execution must inspect the new bounded trace and independent receiver evidence.

## Fixed APK identity

These three files were hashed directly from `build/emulator/scoped-focus-2026-10-17/run-01/` at collection. `debug-artifact-pins.json` preserves the owner's pin list; `summary.json` records the byte checks. No APK binary or credential is copied into this public evidence directory.

| Artifact | SHA-256 |
| --- | --- |
| App debug | `ed91699051410de9e8eb0d8b51275f04fc312e69b5fc87d7e69befa6aaa6968e` |
| App instrumentation | `cf42f277259c2da143f7f8880ed0cabeb5eef3b4cdb274d6e7e84937830c99ce` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

The four static artifact inspection entries, including release packages, were also checked against their actual local files. Six direct chat assets match the source bytes; the compiled Runtime bundle has its own archived hash and is not equated byte-for-byte with TypeScript source.

## Evidence integrity and limits

`source-hashes.json` records selected Native/UI/integration files plus all current Runtime source and test files: **62 files**, none modified after the unified Gradle log. This is a selected-file inventory, not an atomic full source archive. `junit-xml-hashes.json` records all61 suite XML identities; `summary.json` includes lint report hashes, copied-log provenance and execution boundaries. `manifest.json` hashes every public file in this directory except itself.

The collector ran no build, test, Android command or cloud request and changed no production source. Independent AVD acceptance of the new pins is separate; this directory makes no Android, physical Thor, model autonomy or game-goal success claim. Older directories and the shared evidence manifest were not modified.
