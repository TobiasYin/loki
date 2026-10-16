# Final-null-recovery emulator verification

**Six requested cases passed; the separate Presentation case remains unresolved after two retained failures. This is not an all-passed acceptance record.**

All attempts here used the same installed and independently hashed fixed artifacts:

| Artifact | SHA-256 |
| --- | --- |
| Agent | `396c98cd7a1e0ed2f3bbb38cab5917370e31e783b639c5c1ce3c19c456431ae0` |
| Instrumentation | `570ad02b0d5d2138d82d3d5cd6383333bfa99d60ca37b0d8ee199f3ba008d116` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

The copies came only from `build/emulator/legacy-null-2026-10-17/run-01/`; installed package-file hashes matched before and after. They include the later legacy historical-null projection fix, rather than the earlier App28cf or App3afa. The incorrect earlier service precondition/old-App run is outside this directory and contributes no acceptance here.

## Passed cases

The explicit five-case group passed in **68.143 seconds**, with no skip, failure or retry: short background lifecycle/notification stop 2, Modern UI 1 and provider settings 2. `emulatorUiComponents=true` was supplied, so the Modern case actually executed. It verified real trusted touch/keyboard interaction, disclosure animation, inline controls and optimistic-capability rollback against synthetic UI data. Native commands in that UI fixture were intercepted. Provider settings used controlled loopback responses and disposable test credentials; this is not official cloud acceptance.

The independent opt-in long-background case passed in **405.389 seconds**. Its measured hidden interval was **380,343 ms**, with all 380 live incremental markers persisted across 13 checkpoints. The last measured checkpoint contained marker 380, 385 persisted events and 131,480 event bytes. During that interval the test used SQLite-only observation, with no JavaScript evaluation, UI launch, fake clock or visibility override; the outer collector also issued no device command during the interval. After returning to chat, the same Host, never-attached execution document and Pi session remained, and both the complete durable turn and the follow-up HTTP context included every marker. Four loopback requests were used; cloud requests and device input calls were zero. Completion and follow-up occurred after UI return; long-hidden notification-stop is not covered by this case. The short group separately verifies the actual notification stop and partial-context preservation.

## Unresolved Presentation case

Both attempts explicitly supplied `emulatorAcceptance=true` and kept the same production/test binaries and guards. The first failed in **55.358 seconds**: moving chat to background, selecting lower, reading window information and capturing a usable lower-screen frame succeeded, but the next lower UI tree was refused with `PERMISSION_MISSING`: “Lower target must be a unique non-assistant application.”

One independent same-binary rerun was allowed after complete fixture cleanup, fresh terminal-run/service checks and removal of only this app's accessibility entry before instrumentation startup. It failed in **76.050 seconds**. This time the lower screenshot, first tree and semantic `input.activate` all succeeded, with the independent receiver confirming semantic count 1. After re-observing target stability, the next tree received the same refusal. Neither run reached a complete Presentation verdict. No guard, TTL, production source or test source was changed, and no further blind retry was performed.

The refusal is retained as an unresolved observation/qualification boundary. A previous successful screenshot or activation does not prove that the current window proof remained valid. Post-cleanup accessibility had no crashed service, but no proof/cache metadata was recorded at the exact rejection, so these records do not establish a specific invalidation cause. Follow-up Native investigation belongs to a new source/package snapshot. Earlier Presentation passes on another App are not transferred here. This is a manual-platform Native component with an independent synthetic receiver, not a cloud-model game-control or physical gamepad-injection result.

## Preservation and limits

Both failed Presentation attempts completed their fixture cleanup, with no cleanup errors and the temporary display released. The outer collector then independently confirmed:

- All original history IDs remained. Original run/message/event/attachment payloads matched the pre-test copies byte for byte.
- One original selected session's `updatedAt` changed through the normal session-selection restoration command. All other original session fields were unchanged; raw session payload byte equality is deliberately not claimed.
- Original selected session and raw `profiles`, `native-credentials`, `display-roles` preference XML hashes matched. No real model key was present or used.
- The initial global accessibility list and `accessibility_enabled` value were restored, preserving the third-party service. Notification permission returned to its original denied state with the complete original flags. The handoff had inherited this app's accessibility entry and granted notifications from earlier work; those inherited states were not mistaken for the original baseline.
- Overlay-display, immersive-policy and animator settings stayed at their original `null` values. All 81 accumulated runs were terminal and the Runtime service had exited. Installed hashes still matched the fixed pair.

Public JSON contains only bounded fixture results, counters, error labels and preservation checks. Private backups, system dumps, raw DB/history, full cloud responses, credentials, APKs, ROMs and game saves remain outside this directory. No Thor or cloud operation occurred in this acceptance. `manifest.json` covers this directory and excludes itself; shared manifests are left to the coordinating agent.
