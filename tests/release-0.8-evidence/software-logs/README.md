# 0.8 software validation snapshots

The current [software-checks.json](../software-checks.json) records the **frozen final-10 production build**, including the Runtime post-result receipt correction. It is a time-stamped software snapshot, not a claim that all device suites have rerun on its APP. Prior [final-09](final-09-software-checks.json) and [final-08](final-08-software-checks.json) records remain intact with their own original pins.

This evidence refresh read files only; it ran no tests, Gradle, artifact checker or device commands. Original full logs remain under `build/physical/release-0.8-2026-10-16/`; the JSON separately records full-log and public-excerpt hashes, lengths and times.

## Final-10 checks and reuse

`final-10-gradle.log`: **BUILD SUCCESSFUL in 12s**, **506 actionable tasks: 28 executed, 478 up-to-date**. Actual retained JVM XML totals are **368 tests in 55 suites**, zero failures/errors/skips. **All five test tasks were UP-TO-DATE in this invocation**:

| Module | Retained XML tests | Final-10 task |
| --- | ---: | --- |
| app | 81 | UP-TO-DATE |
| device-core | 161 | UP-TO-DATE |
| core | 39 | UP-TO-DATE |
| device-authority | 13 | UP-TO-DATE |
| input-lab | 74 | UP-TO-DATE |

No claim is made that these were 368 fresh executions. Original per-suite XML fingerprints and mtimes are preserved.

**Runtime freshly passed 82/82** after the receipt fix, including 15 new actual-Pi/controlled-SSE cases for navigation receipts, same-session follow-ups without replay, revocation/stop, binding changes, asynchronous image unpacking, pure read target changes, fresh selection identity and retained preconditions. Integration TypeScript freshly exited **0**, with no diagnostics; this exit code was directly observed by the receipt-fix agent, not inferred from its empty log. The first Runtime invocation passed 81/82; its single test expectation omitted Pi Responses' composite call/item ID. That assertion was corrected to check Native envelope, event and effect-ledger correspondence, and the original failed log is retained separately.

**App JS52/52 and bridge JS13/13 are reused final-08 results**, verified by unchanged source fingerprints through final-09 and final-10; they were not rerun this time. The Runtime and integration source fingerprints now describe the receipt-fix version, rather than claiming those old Runtime67 results cover new code.

Six debug lint XML reports contain **100 warnings and zero reported errors**: app26, device-android10, bridge Android3, bridge host4, Input Lab46, diagnostic11. Most final-10 lint analysis/report tasks were UP-TO-DATE, as shown in the excerpt. Successful lint does not mean warning-free; compiler/Gradle deprecations can also remain in full logs.

## Build artifact pins and component boundaries

- Final-10 production debug APP: `5b5535d2cff41a1c67cecd9dab692961f491272955c409d66f25dd1b2743b9a8`.
- TEST: `d0866d32e310e099f7c71f942ffefaf824942416d2dd2946674d7f807651355c`. Its final-10 assembly was UP-TO-DATE, retaining the prior VD07 test-only fixture build.
- Final-10 unsigned release APP: `29e4ff47ab67fb79d4b00b5ca429c31215837e056c3d16723990f84d2b64f819`.

[final-10-artifacts.json](final-10-artifacts.json) contains successful artifact-check permissions and hashes for Agent/Input Lab debug and unsigned-release packages. Assembly and static checks do not establish instrumentation execution, release signing or distribution.

The earlier 14 emulator cases and dual-screen Native component results use **APP256498... / TESTd0866d...** and cannot be relabeled as full reruns on **APP5b5535...**. The new APP's background suite and physical game11 continuation were still collecting separate evidence at this snapshot; no verdict is asserted here. Cloud, voice, hardware and game completion claims also require their own independently pinned result. This refresh altered none of those component/game records.

Older final-09 **APP256498...**, full-build **TESTcdfb...** and later test-only **TESTd086...** identities remain explained in the archived final-09 record. Final-08 **APP9c03... / TESTc7a9...** and the earlier **INTERIM329** are historical. Old excerpt files remain available, but should not be mistaken for final-10 executions. Initial final-08 `ANDROID_HOME` failures remain classified as environment setup failures, superseded by configured successful retries.
