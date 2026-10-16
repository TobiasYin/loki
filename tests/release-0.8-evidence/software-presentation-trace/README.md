# Presentation trace software increment

This small snapshot extends [the expiry/startup software baseline](../software-expiry-startup-final/README.md). It records bounded scheduling, worker, reader-result and wait diagnostics plus equivalent fixed-hex SHA formatting. TTL,2000ms refresh reserve, owner/secure/clip guards and input replay policy are unchanged. It contains no Android/cloud/Thor/game completion claim.

The unified Gradle build succeeded in15s:506 actionable tasks,77 executed and429 up-to-date. Current XML totals **421 tests in63 suites,0 failures,0 errors,0 skipped**. App95, device-core198 and authority13 tasks executed; bridge-core41 and Input Lab74 tasks were UP-TO-DATE. The two new trace cases are included in421. The known SHA256 `abc` vector was added to an existing case, without increasing the case count.

Runtime251, Chat69, Bridge13 and relay3 remain retained results from the baseline, with source bytes verified unchanged; they were not rerun. Baseline source-provenance limitations still apply, including the missing independent original Bridge13 source archive. The three SQLite Android cases are not JVM tests and are not accepted by this software record.

Actual pinned files were hashed and byte-size checked:

| Artifact | SHA-256 |
| --- | --- |
| App debug | `387472aab1c12d735253879288f895a98548ad0964ed06eb69c94837e3317c46` |
| App instrumentation | `1bea4c10a729d38a4c4e208a59553b24f19ebe4a3dec6b28c4b62ee0c4117c5c` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

`summary.json` records the task states, fixed pins and changed-source list. `source-hashes.json` records71 selected files, comparing available hashes to the baseline; `junit-xml-hashes.json` records all63 XML identities. The current Gradle and static artifact inspection are copied here. `manifest.json` hashes every public file except itself. DebugConnection was not in the prior selected inventory; its current edited bytes are archived without claiming a baseline hash comparison. This is not an atomic complete source archive. No source/build/test/device action was performed by this collector; new AVD diagnostics and independent Thor attempts are reported separately. Prior evidence directories and shared manifest were not changed.
