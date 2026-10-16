# Minimal execution-state projection software increment

This small record extends [the Presentation-trace software baseline](../software-presentation-trace/README.md). Only the Engine push to its execution document uses a new pure projection. Full state for chat, `lastState`, Host RPC results, Native authorization and database behavior remain unchanged. No real-device timing or game-goal result is claimed.

The current unified Gradle build succeeded in11s:506 actionable tasks,34 executed and472 up-to-date. Current JVM XML totals **426 tests across64 suites,0 failures,0 errors,0 skipped**. App100 executed; device-core198, bridge-core41, authority13 and Input Lab74 tasks were UP-TO-DATE. Five new projection cases are included in426; the other326 cases are retained current XML rather than claimed fresh executions. Android instrumentation cases are excluded.

The current Runtime251, Chat69, Bridge13 and relay3 source bytes are unchanged and their previous results are retained, not rerun. Log provenance and original-source limitations are linked through the baselines.

## Contract and regression facts

The execution push contains every session's id/capabilityEpoch and every active Run's id/sessionId/status/stopReason. It does not truncate to the selected chat, so concurrent authorization changes and stop notices remain visible. Only an absent activeRuns field permits the legacy runs fallback; explicit empty/null activeRuns never revives historical Runs. New projected rows do not share writable JSON objects with the full input.

Runtime consumer bytes were compared directly to the baseline. `currentState` has its declaration plus two other occurrences: update assignment and preparation Run-membership read. The update path consumes session id/epoch, Run id and stopReason; preparation checks Run-id membership. These fields remain complete. The Engine execution DEBUG byte count now measures the exact projected JSON.

The synthetic regression attaches a giant history payload whose `toString()` throws. Projection succeeds without traversing or serializing that history, equals the small-input result and is less than256 UTF-8 bytes. This is a software regression fact, not a measured Thor speedup or actual-device payload benchmark. Messages/events/attachments/turns/contexts/credentials/titles/model-profile bodies are excluded from this push; normal authorized task RPC/context remains unchanged.

## Fixed packages and integrity

| Artifact | SHA-256 |
| --- | --- |
| App debug | `69a0f0033c16e34dea83681c420b17fbbc285189f22dd7fb8f98159bce518ae3` |
| App instrumentation | `1bea4c10a729d38a4c4e208a59553b24f19ebe4a3dec6b28c4b62ee0c4117c5c` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

All three fixed files were hashed and byte-size checked directly, as were four static artifact-inspection entries. `source-hashes.json` inventories73 selected source/test files and compares available hashes to the prior snapshot, including the new helper/test, changed Engine and unchanged Runtime consumers. It is not an atomic complete source archive. `junit-xml-hashes.json` records all64 suite hashes/counts; `summary.json` includes the exact currentState audit, contracts and limits. The manifest hashes every public file except itself.

The collector changed only this new evidence directory, without source modification, build, test execution, device access or Thor installation. Prior snapshots/shared manifest are unchanged. New AVD and independent Thor acceptance remain separate.
