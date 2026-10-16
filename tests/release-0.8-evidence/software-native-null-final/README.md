# Final nullable-result software build

This directory seals the Native nullable-success software supplement. “Final” identifies the final software build for this patch; it is **not a final release or Android acceptance claim**. The earlier split-document and historical pre-null long-task software directories remain unchanged.

The fixed package copies are:

| Artifact | SHA-256 |
| --- | --- |
| Agent debug | `28cf1a11f41df24b75273570617077624848363be1e4276e2fe3e8b1ed81f61e` |
| Instrumentation | `570ad02b0d5d2138d82d3d5cd6383333bfa99d60ca37b0d8ee199f3ba008d116` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

All three fixed copies were hashed independently at collection. `artifacts.json` contains the owner's static APK identity/permissions check, including unsigned release packages. APKs themselves are not published here.

`gradle.log` records **BUILD SUCCESSFUL in 8 seconds**, with 506 tasks: 19 executed and 487 UP-TO-DATE. All five JVM test tasks were UP-TO-DATE in this final build. The retained actual 59 XML suites contain **398 tests, 0 failures, 0 errors and 0 skips**: App90, device-core180, core41, device-authority13 and Input Lab74. These are retained results, not 398 freshly executed tests. `junit-xml-hashes.json` identifies every contributing XML and task status. Six lint reports retain 106 warnings and no Error/Fatal entries; cached reports and Gradle deprecation warnings are not hidden.

The current Runtime command compiles its TypeScript, builds the Runtime and passes **213/213 tests**, including **31 new nullable-success/error-code cases**. Those 31 are part of 213, not an additional total. The actual Pi/controlled-SSE regressions use Native-shaped JSON outcomes across OpenAI Responses, OpenAI Completions and Anthropic Messages. They verify that `error:null` succeeds, screen selection still requires a fresh observation before input, the capture attachment reaches the next provider request, and successful current-screen observation clears only the observation gate. Stale-source and stop checks still withhold rejected bodies. Unknown non-null errors fail as fixed `PROTOCOL_ERROR`, while already-submitted receipt evidence remains available without replay. `RuntimeError` also normalizes unknown or null bridge codes, preventing an empty or arbitrary `run.error`.

Chat69 and integration TypeScript are retained prior checks for unchanged UI/integration source, **not rerun after the final Runtime patch**. Current Runtime TypeScript compilation is separately visible at the beginning of `runtime-node.log`. Bridge13 and relay3 are also prior results, not new tests. Current source equality against the preceding long-task snapshot is recorded for the UI, integration relay and bridge files; relay source/test hashes were also checked against the original split snapshot in that earlier record. No separate prior bridge source archive is invented.

`source-hashes.json` contains 39 selected source/test file hashes captured after the final build, including the exact Runtime contracts, implementation and 31-case test file. None had modification time after the final Gradle log when captured. This is a per-file snapshot, not an atomic complete source archive. `summary.json` identifies the compiled chat assets in the fixed APK and checks six direct assets against their source bytes. The Runtime bundle is hashed as a built asset; bundled JavaScript is not claimed byte-identical to TypeScript.

No device was operated or test/build launched by this evidence collector. An AVD setup attempt rejected a service guard before installation, and a later instrumented run inadvertently used the old App3afa; those logs belong to their separate attempts and are **not acceptance of App28cf**. New App28cf background, UI, cloud and Thor original-session game continuation require their own fixed-package results. No older App's passes transfer to this one. No model wall-clock deadline or optional nullable `outcome`/`source`/`truncated` contract was introduced.

`manifest.json` covers this directory's public files and excludes itself. No old evidence or shared manifest was overwritten. No APK, database, raw cloud response, credential, ROM or game save is included.
