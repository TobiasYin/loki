# Full snapshot storage: seventeen targeted cases passed

The fixed storage-repair pair passed **all17 targeted emulator cases**, with zero failure, skip or retry. This group verifies the storage change and short execution cleanup. It does not rerun or replace earlier Presentation failures, the380-second cases or physical-game acceptance.

| Artifact | SHA-256 |
| --- | --- |
| Agent | `485c1db024fff391ad576a2645a7522312a1c633a1f84e47684725cffffd7ef1` |
| Instrumentation | `fd6e6e5362e1fc28dd1b8ff90c3533caf71fca43486897ab5a3cde17333f7358` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only fixed copies from `build/emulator/full-snapshot-2026-10-17/run-01/` were installed with data preserved. Actual installed hashes matched before/after on isolated API36 ADB5042 / `127.0.0.1:5557`. No Thor operation or cloud request occurred during this acceptance.

## Repair and boundary

The real game19 run had already completed with1253 durable events at442880ms. Its later interrupt/full-save cleanup failed while `Storage.kt:52` copied all old committed payload strings through CursorWindow into a second Java map beside the full in-memory state. This is a cleanup OOM, not evidence of an unterminated model run. The original private suite log remains local; no cloud thought/context is reproduced here.

`SqliteStore.save` now queries IDs only for reverse-order deletion, then current IDs for parent-first writing. Each serialized new payload is compared using a bound SQL `id=? AND payload<>?` predicate. Unchanged rows do not execute UPDATE triggers; parents are never replaced. Existing transactions, foreign keys, full desired history and metadata commit/rollback remain intact. Re-reading current IDs after deletion accounts for cascaded rows that the snapshot legitimately retains under a surviving parent. No schema migration, data clearing, history truncation, largeHeap or permission relaxation was introduced.

This removes the full retained copy of old payload bodies. It does not make initial load/full JSON state or a single serialized/bound row memory-free, and no total heap-reduction or performance ratio is claimed. `source.json` pins the two changed source files. The independent compile log is only source compilation; actual SQLite execution is separately recorded below.

## Actual execution order

| Group | Cases | Duration |
| --- | --- | --- |
| New FullSnapshotStorageTest | 4 | 3.008s |
| SessionUpdate3, ScopedSession2, StartupRecovery3, PhysicalStorageRegression2 | 10 | 27.735s |
| Short background2, ModernUI1 | 3 | 44.249s |

All14 storage cases use random disposable SQLite databases and do not open product.db or credentials. The new large-body case commits a3MiB event and12 historical128KiB bodies, closes/reopens the store, repeats full saves, then persists terminal Run/message changes and an appended event. Exact full payload equality is checked by SQLite scalar counts rather than retrieving old bodies into a CursorWindow. Triggers reject unchanged-row UPDATEs, and every existing rowid is preserved. The other new cases verify late foreign-key failure and final metadata failure roll back deletions, updates, insertions and selection, and that parent-cascaded wanted children are restored under a valid surviving parent.

Adjacent cases retain session-only rename/selection rollback, screenshot append uniqueness and Run/session ownership, incremental Run/session scope, startup recovery boundaries, and full-save retention following10,000 historical events/200 streamed commits. The two PhysicalStorageRegression class names identify isolated database tests; they perform no physical-device operation.

The short pair verifies HOME/Activity finish and return with the same execution/Pi session, real notification Stop retaining partial context, and idle writer handoff. It uses real Native/Pi with controlled loopback SSE. ModernUI uses explicit `emulatorUiComponents=true`, actual trusted touch/keyboard and synthetic state with intercepted Native commands. These are not official-cloud/game/control results. NoPresentation or380-second long-background case ran on this pair.

## Preservation

Before install 113 existing Runs were terminal, exact RuntimeService absent, model vault key absent and original global baseline matched. At exit all 117 accumulated Runs were terminal and Runtime exited. Existing history IDs and run/message/event/attachment payloads were byte-identical. The exact session updatedAt change count is 0; every other session field and original selected ID matched. Raw model/vault/roles XML hashes, original accessibility, notification denied state with complete flags, null overlay/immersive/animator settings and fixed installed hashes matched/restored. Per-test preservation contains only this group's two short cases.

Private DB/history/backups/credentials, raw dumps, APKs, cloud responses, ROMs and game saves are excluded. Earlier69a0 seven-pass and03e853 Presentation-failure groups remain unchanged, and no complete-release claim is made. The collector changed the two storage source files before coordinator build; no source/build mutation occurred during acceptance. `manifest.json` covers this directory except itself.
