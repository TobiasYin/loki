# Final legacy-history software build

This directory fixes the final **software** identity for legacy null/missing tool-result history recovery, on top of nullable Native success, typed target recovery and separate chat/execution documents. “Final” does not mean Android acceptance, cloud-model acceptance or completion of the real game task. The prior 213-case nullable-success snapshot and every earlier attempt remain unchanged.

The fixed local package copies were independently read and hashed:

| Artifact | SHA-256 |
| --- | --- |
| Agent debug | `396c98cd7a1e0ed2f3bbb38cab5917370e31e783b639c5c1ce3c19c456431ae0` |
| Instrumentation | `570ad02b0d5d2138d82d3d5cd6383333bfa99d60ca37b0d8ee199f3ba008d116` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

These are the copies under local `build/emulator/legacy-null-2026-10-17/run-01/`, not an inferred association with an installed device. `artifacts.json` also preserves the owner's static identity/permissions check for both unsigned release packages. Each listed build-output APK hash and byte length was checked against its actual local file. APK binaries are not published here.

`gradle.log` reports **BUILD SUCCESSFUL in 6 seconds**, with **506 tasks: 19 executed, 487 UP-TO-DATE**. All five JVM test tasks were UP-TO-DATE. The actual retained XML has **398 tests across 59 suites**, with no failures, errors or skips: App90, device-core180, core41, device-authority13 and Input Lab74. `junit-xml-hashes.json` records every XML hash, timestamp, count and cached task identity. This is retained coverage, not 398 fresh test executions. Six retained lint reports contain **106 warnings**, with no Error/Fatal issue; the Gradle deprecation warning is also retained.

The current Runtime log contains TypeScript compilation, build and **235/235 passing tests**, including **22 new legacy-history cases**. The 22 are part of 235. The actual Pi/protocol-serialization regressions reproduce the old cold-history failure before HTTP, then verify one real controlled HTTP request after projection, zero old tool executions, unchanged original DomainTurn JSON, correct pairing/failure flags/effect ledger, and preservation of unrelated valid history. Strict current user/tool content, non-null malformed historical text and revoked derived history remain rejected or filtered. The previous nullable-success, typed-target, permission, safety, stop and stale-source regressions remain in this total. Focused before/after and setup-only records are in the separate `../legacy-null-history/` directory.

`integration-tsc.log` is the **current** successful integration TypeScript check supplied by the owner, with reported exit code 0; an empty successful log is expected. Runtime TypeScript compilation is separately visible in `runtime-node.log`.

**Chat69, Bridge13 and relay3 are retained prior checks, not rerun here.** Their actual logs are copied with `prior-` names and current source hashes match the preceding `software-native-null-final/` selected-file snapshot. The relay source/test also match the original dual-document snapshot. That original snapshot did not independently archive the Bridge13 source files, so this record does not invent an original bridge-source hash or pretend a new bridge execution occurred. `reusedSourceEquivalence` in `summary.json` makes these boundaries explicit.

`source-hashes.json` identifies **43 selected source/test files**, including context projection, session code, the exact Runtime and new 22-case regression. No selected source modification time was later than the final Gradle log when collected. This is a selected-file capture, not a complete atomic archive of build inputs. The fixed debug APK's six direct chat assets match their source bytes; its built Runtime bundle is independently hashed, with no claim that bundled JavaScript is byte-identical to TypeScript.

No device, build or test was operated by this collector. The independent new AVD validation and Root's Thor game-15 continuation are separate executions with their own fixed evidence. This software directory does not import their results, any older package's passes, or a future game-completion claim. It contains no database, credentials, raw cloud response, ROM, game save or APK. No model wall-clock deadline was introduced.

`manifest.json` covers this directory's public files and excludes itself. Older evidence directories and shared manifests were not overwritten; the overall evidence index receives only an additive entry for this snapshot.
