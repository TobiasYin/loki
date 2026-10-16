# Expiry-refresh and startup-storage emulator result

**Three isolated storage cases passed; the Presentation case failed once. The further five-case group and 380-second long test were not executed on this package.** No Presentation retry was made and no earlier package's passes are transferred here.

| Fixed package | SHA-256 |
| --- | --- |
| Agent | `dbedacdcc6f3f9e86803399b7e61e6b45d851ff9b2cc0650198eb664bc8e36fe` |
| Instrumentation | `1bea4c10a729d38a4c4e208a59553b24f19ebe4a3dec6b28c4b62ee0c4117c5c` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only the fixed files from `build/emulator/expiry-startup-2026-10-17/run-01/` were installed on the isolated API36 emulator at ADB 5042 / `127.0.0.1:5557`. Installed package-file hashes matched before and after the attempts. There was no cloud request or Thor operation in this group.

`StartupRecoveryStorageTest` passed all 3 cases in **1.173 seconds**, using random disposable SQLite databases that were deleted afterward. Triggers prohibit writes to existing child history or terminal runs; the test verifies targeted recovery/session metadata without those writes. A metadata failure must roll back session title, creation, selection and recovered-run changes together. Unknown or terminal recovery IDs must fail without partial writes. These cases do not open the product database or settings, and they are not a claim about cloud model or full UI startup performance.

Presentation then ran with explicit `emulatorAcceptance=true` and failed in **30.034 seconds** at the second tree read. Chat backgrounding, lower selection, window information, screenshot, first tree and semantic activation succeeded; the independent receiver confirmed semantic count 1. The second tree returned `PERMISSION_MISSING`: “Lower target must be a unique non-assistant application.” The receiver and Host cleanup succeeded without cleanup errors, and the temporary display was released.

The passive trace records lower-display 20, generation 47, with its final store at elapsed 21462897 and expiry at elapsed 21467923, absolute age 5138 ms. The key hash is unchanged and there is no intervening structure event or additional successful store. The new near-expiry symbols are present in the fixed APK's DEX files, but symbol presence does not prove that the refresh reader ran or succeeded. The current trace does not record scheduling, reader exceptions or discarded reader/key results; absence of a new store cannot distinguish those paths. No claim is made that a 2-second refresh threshold is intrinsically sufficient or insufficient. Further narrow diagnostic work belongs to a separate package and result.

The coordinator paused further AVD testing after this failure. The earlier ed916 package's six passing cases remain in `../scoped-focus/`; none is attributed to dbed/1bea. No TTL, owner, clipping, secure-window or authorization check was relaxed and no potentially submitted input was repeated. The Presentation component is independent manual-platform Native testing, not model autonomy, a cloud game result or physical gamepad-delivery proof.

At exit, all 92 accumulated product Runs were terminal and the Runtime service had exited. All original history IDs remained, with original run/message/event/attachment payloads byte-identical. One original selected session's `updatedAt` advanced through normal selection restoration; all other original session fields were unchanged and the original selection was restored. Model/vault/display-role raw XML hashes matched. The initial global accessibility list, notification denied state and complete permission flags, and original `null` overlay/immersive/animator settings were restored. No real model key was present or used.

Public files contain bounded numeric/hash/enumerated diagnostics and test outcomes. Private DB/history/backups, raw system dumps, cloud responses, credentials, APKs, ROMs and game saves are excluded. `manifest.json` hashes this directory and excludes itself. The evidence collector did not change source or run a build.
