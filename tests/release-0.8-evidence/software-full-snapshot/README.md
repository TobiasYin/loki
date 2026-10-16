# Full snapshot save memory software increment

This independent small snapshot extends [the recovery-UI software baseline](../software-recovery-ui/README.md). The single production change is `Storage.save`. The observed game19 OOM occurred when the Run had already reached completed and host interrupt/cleanup performed a full save. It does not establish an interruption during model execution or successful completion of the user's game goal.

The old full save materialized committed payloads into a second Java history map. The new path reads only ID sets and compares each bound new payload inside SQLite (`id=? AND payload<>?`). Unchanged rows do not UPDATE or trigger; parents are not REPLACEd. Delete-children-first/insert-parents-first order and the existing transaction remain. Current IDs are reread after deletions so parent cascades cannot make desired reparented children disappear. Selected metadata commits or rolls back with the snapshot.

`load()` still reconstructs complete history in memory. The current input state also remains complete history; new payloads are serialized one row at a time. This patch avoids one specific duplicate-old-payload save cost, not every OOM, large-history or CursorWindow limit. No real-device quantified speed/heap improvement is claimed.

The unified build succeeded in10s:506 actionable tasks,33 executed and473 up-to-date. JVM XML totals **426 tests/64 suites,0 failures,0 errors,0 skipped**: App100 task executed, the other326 cases are from UP-TO-DATE tasks. No new JVM case was added. Runtime251, Chat69, Bridge13 and relay3 source bytes match the prior snapshot; their results are retained, not rerun.

## New SQLite instrumentation scope

`FullSnapshotStorageTest` contains four real Android SQLite cases, compiled and identified in the pinned test DEX, but **not device-accepted in this software record** and not part of JVM426. They cover an old single row larger than3MiB plus12x128KiB history, repeated full saves after reopen, unchanged-row triggers and rowid retention, changed terminal rows plus append, late FK rollback, final metadata rollback, and correct desired-child reinsertion after parent cascade. Large old payloads are compared through bound SQL scalar counts, not read back into CursorWindow. Only random disposable databases are used. Actual AVD execution is reported separately.

| Fixed artifact | SHA-256 |
| --- | --- |
| App debug | `485c1db024fff391ad576a2645a7522312a1c633a1f84e47684725cffffd7ef1` |
| App instrumentation | `fd6e6e5362e1fc28dd1b8ff90c3533caf71fca43486897ab5a3cde17333f7358` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Actual fixed files were verified by size and SHA-256, as were four static artifact-inspection entries. `source-hashes.json` inventories74 selected files and confirms only Storage plus the new SQLite test differ from the baseline. Current JVM XML hashes/counts and build/static artifacts are archived. The manifest hashes every public file except itself. This is not an atomic full source archive. The collector changed only this new directory and an additive public README index, without source change, build, test execution or device action. Older snapshots/shared manifest remain unchanged.
