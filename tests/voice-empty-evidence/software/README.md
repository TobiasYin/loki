# Voice empty-result software evidence

This software-only package records the Native ASR silence/empty-result repair and chat empty-to-idle behavior. It does not claim official cloud recognition, microphone capture, full on-device UI or emulator acceptance; those independent checks belong outside this directory.

The final Gradle build succeeded in7s, with279 actionable tasks:19 executed and260 up-to-date. `:app:testDebugUnitTest` actually executed: **112 tests in23 suites,0 failures,0 errors,0 skipped**. The two voice suites contain **19 cases:11 protocol +8 transport**, included in112 rather than added to it. Their complete final XML files are included; all App XML identities/counts are listed in `app-junit-xml-hashes.json`. Current Chat JavaScript **73/73** passes are in `ui-js-tests.log`; they are software component checks, not device end-to-end acceptance.

## Exact behavior and boundaries

Only `20000003` and `1013` are silence terminal codes. Binary type15 is fully validated before classification: header/flags/reserved byte, complete size, supported compression/serialization, UTF-8 and declared JSON. JSON-code messages follow the same classification. Truncated or malformed silence-shaped packets remain errors. `45000002` is a genuine missing-recognizable-audio/input error, not silently converted to normal empty. Timeout/format/auth/busy/unknown codes remain errors using fixed safe messages and numeric codes, without raw provider messages.

Silence clears stale partial text. Successful final text empty/null/missing also clears partial, while non-string text is a protocol failure rather than a literal null/object transcript. Release and terminal are both required; empty emits once without a submit value, and no empty chat message is generated. The first terminal remains immutable, late duplicate/close/failure cannot overwrite it, preterminal transport failures remain errors, and cancellation never submits. Synchronized result acceptance/phase publication prevents a recording phase after terminal empty. UI software checks cover return to idle and stale-text cleanup.

## Preserved initial fixture failure

The first build genuinely failed one transport test. The test called `cancel()` on MockWebServer's server-side `RealWebSocket`, which has no client `Call` and threw NPE. The original failed Gradle log and both initial voice XML files are preserved as `initial-*`; the one failure is not overwritten or reclassified as a production failure.

Only that test injection changed: shutdown closes actual server TCP connections, the server listener observes a real IOException through a latch, and cleanup attempts shutdown once. Existing no-early-submit/no-late-error/empty-once/no-submit assertions remain. The final pass does not hide the first failure. The test directly proves server I/O failure, not the exact arrival time of the client's suppressed late callback. Transport cases use actual local OkHttp WebSockets with generated PCM and controlled responses, not the official ASR cloud.

## Fixed artifacts and integrity

| Artifact | SHA-256 |
| --- | --- |
| App debug | `006554347a6012671f238110458dafa00b70ccbfb14e70b78e2013f4dd73a953` |
| App instrumentation | `1c6bf788b8ae8dc22ff7667506bee9d2e50786cfaedbd4ede6263c01b8743d89` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

The actual pinned files were verified for SHA-256 and byte size, as were four static artifact-inspection entries. The pinned App UI asset matches its source bytes. `source-hashes.json` records selected Native/UI/test/build-source identities; inclusion of the real-cloud test source is not proof that it ran. This is not an atomic complete source archive. `summary.json` preserves log provenance and limits, and `manifest.json` hashes every file except itself.

The collector wrote only this software directory and performed no source edit, build, test execution, device operation or cloud request. The parent evidence manifest is owned separately and was not changed. No APK binary, credential, database or raw official response is included.
