# Recoverable target rejection page-message software increment

This independent small record extends [the execution-projection software baseline](../software-execution-projection/README.md). The only production change computes the fixed error code before the page-message decision: a `TARGET_CHANGED` from `tool.prepare` returns its original recoverable ToolOutcome without overwriting global `lastError`. It does not clear a pre-existing unrelated error. Other RPCs/codes, begin messages, publication, Native checks, tool errors and submission state remain unchanged. No game/device acceptance is claimed.

The unified build succeeded in10s:506 actionable tasks,33 executed and473 up-to-date. Current JVM XML is **426 tests/64 suites,0 failures,0 errors,0 skipped**. The exact log shows App100 executed; device-core198, bridge-core41, authority13 and Input Lab74 were UP-TO-DATE. Thus this is mixed current/cached coverage, not426 fresh executions. No mirror-if JVM test or new JVM case was added.

Runtime251, Chat69, Bridge13 and relay3 remain same-source retained results; they were not rerun. Source byte comparisons to the baseline are recorded, with the prior provenance limitations preserved. The earlier execution-state projection package remains independent and unchanged.

The Android assertion uses the existing real cross-screen stale-snapshot rejection. It reads global error through serial Native view RPC before and after rejection and compares the prior value, rather than assuming an empty page error. Original stale snapshot/node/frame hard rejections and independent receiver checks remain. This assertion is compiled but not device-accepted in this software record; it contributes no JVM count and adds no input action. New Android evidence is separate.

| Fixed artifact | SHA-256 |
| --- | --- |
| App debug | `03e8534da9acdca9bd78c5bac83cab15cf25ef98b75748563cb4cdbc22281237` |
| App instrumentation | `d5841061d45cb387e997f6ec4af2bd89f17d1cef489e5335d81a227d63ac8d4b` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Actual pinned files were checked against byte sizes and SHA-256 values, and four static artifact-inspection entries were verified against their actual files. No APK binary, database, raw model history or credential is copied here.

`source-hashes.json` inventories73 selected source/test files and confirms exactly two differences: ProductHost and the Presentation instrumentation test. All current JVM XML identities/counts are in `junit-xml-hashes.json`; current build/static artifacts are archived. The manifest hashes every public file except itself. This is not an atomic full source archive. The collector changed only this new directory and one additive public README index, without source modification, build, test execution or device action. Earlier evidence and shared manifest remain unchanged.
