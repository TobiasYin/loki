# Recovery UI: one UI pass, one Presentation failure

The final fixed pair executed **two targeted cases**: Modern UI passed and Presentation failed on its first semantic activation. There were no skips or retries. The new global-error assertion was **not reached**, so this pair does not yet prove that assertion in Android. Earlier App69a0 seven-case passes remain independently pinned in `../execution-projection/`; no result transfers to this pair.

| Artifact | SHA-256 |
| --- | --- |
| Agent | `03e8534da9acdca9bd78c5bac83cab15cf25ef98b75748563cb4cdbc22281237` |
| Instrumentation | `d5841061d45cb387e997f6ec4af2bd89f17d1cef489e5335d81a227d63ac8d4b` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only fixed copies from `build/emulator/recovery-ui-2026-10-17/run-01/` were installed with data preserved. Actual installed package-file hashes matched before and after on isolated API36 ADB5042 / `127.0.0.1:5557`. No Thor/cloud operation, build or source edit was performed by the collector.

## Failed Presentation attempt

With explicit `emulatorAcceptance=true`, the single attempt failed in 44.071s at `EmulatorPresentationControlTest.kt:271`: first `input.activate` returned PERMISSION_MISSING with observed outcome. Earlier background/select/window-info/screenshot/tree calls succeeded. The coordinate, cross-screen and secure-ABA stages, including the new stale-prepare global-error comparison at lines289–293, were not reached. Cleanup reported zero errors. There was no blind retry.

The retained tail64 proves full refresh was scheduled and invoked at remaining1912,1401,978,589 and310ms. Each received fixed remote UNSUPPORTED; the last read lasted271ms. The same-key proof then expired at age5011ms. This establishes that the near-expiry trigger ran, rather than being skipped. It does not explain the specific remote rejection stage or prove a user permission was revoked.

Read-only source analysis: `DebugConnection.kt:26–36` sees a remote JSON error and records its fixed enum before rejecting unavailable metadata. `DebugUserService.kt:29–41` maps inspect failures to bounded fixed error codes. `NativePresentationEvidence.kt:13–36` can reject incomplete dumps; `PresentationWindowEvidence.kt:21–77` can reject unsupported/missing/ambiguous metadata. These causes are not differentiated by the current diagnostic, so the failure cannot be labeled a proven transient WM failure or parser defect. After proof expiry, `ThorAccessibilityService.windowEvidence` withholds an unknown window's unqualified root and `Displays.kt:40–43` requires a valid unique lower application. That path is consistent with the final PERMISSION_MISSING, but the originating exception stack is not persisted. The narrow `ProductHost.rpc` page-error change does not alter this Native reader/qualification path. No TTL/owner/clip/security check was relaxed.

## Independent UI pass

After the failed group was fully restored and its private backups/report sealed, Modern UI ran once with explicit `emulatorUiComponents=true`, passing in 18.396s. It uses actual trusted touch/keyboard on synthetic chat state and intercepts Native commands, so it does not depend on Presentation/WM qualification. It verifies disclosure animation and settling, inline model/effort choices and capability rollback. It is not official-cloud or device-control acceptance. No source changed between the two attempts.

## Preservation and remaining scope

Preflight found 112 existing Runs terminal, no exact RuntimeService, no model vault key and the original baseline matching. At exit all 113 accumulated Runs were terminal and Runtime exited. Original history IDs and run/message/event/attachment payloads remained byte-identical. Every original session payload and selected ID also remained byte-identical; this group had zero session updatedAt changes. Raw model/vault/display-role XML hashes, original accessibility, notification denied state and complete flags, null overlay/immersive/animator settings, and installed hashes matched/restored. Separate failure-stage and final preservation summaries are retained.

No380-second/provider/notification/storage test was rerun on this pair. The stale-prepare global-error assertion remains unverified here. This is **not all-pass or complete-release acceptance**. Earlier groups remain unchanged, including the seven-case69a0 result. No cloud/Thor/game success is claimed. Published material is bounded counters/fixed labels/key hashes plus test logs, with no private DB/history, raw dumps, credentials, APK, ROM or save. `manifest.json` covers this directory except itself.
