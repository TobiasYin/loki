# Scoped-proof and focus-hint emulator result

**Six cases passed; the Presentation case failed once at the absolute proof-expiry boundary. This package is not fully accepted.** No Presentation rerun was attempted. Later proactive proof-refresh and startup-storage changes are outside this installed package.

| Fixed artifact | SHA-256 |
| --- | --- |
| Agent | `ed91699051410de9e8eb0d8b51275f04fc312e69b5fc87d7e69befa6aaa6968e` |
| Instrumentation | `cf42f277259c2da143f7f8880ed0cabeb5eef3b4cdb274d6e7e84937830c99ce` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only the fixed files under `build/emulator/scoped-focus-2026-10-17/run-01/` were installed. Package-file hashes matched before and after testing. The isolated API36 emulator used ADB 5042 and serial `127.0.0.1:5557`; no Thor or cloud operation occurred.

The explicit five-case group passed in **53.541 seconds**, with zero failures/skips/retries: background lifecycle/notification stop 2, Modern UI 1 and provider settings 2. The real Modern case ran with `emulatorUiComponents=true`. Its display fixtures are synthetic and Native commands intercepted; provider requests use controlled loopback, not the official cloud. The short background tests use actual Native/Pi and a local SSE provider.

The opt-in long case passed in **395.074 seconds**, measuring **380,101 ms** after HOME and Activity destruction. All 380 live markers persisted; the last checkpoint recorded marker 380, 385 events and 131,481 bytes. The test observed SQLite only during that interval, without evaluating JavaScript, opening chat, changing visibility or using a fake clock. The outer collector also issued no device command during the interval. After UI return, the same Host, never-attached execution document and Pi session continued; complete durable context and the follow-up HTTP body contained every marker. Four loopback requests were used, with no cloud or device-input calls. Completion and follow-up occur after UI return, while notification stop is covered separately by the short case.

## Retained Presentation failure and new evidence

Presentation ran first with explicit `emulatorAcceptance=true`. It failed in **31.793 seconds** after real lower-screen selection, window information, screenshot, first component tree and semantic activation all succeeded. The independent receiver confirmed semantic count 1. The second component-tree read returned `PERMISSION_MISSING`: “Lower target must be a unique non-assistant application.” Fixture cleanup succeeded with no cleanup error and released its temporary display.

The newly added passive trace records the last lower-display proof stored at elapsed 20654266 with generation 47. At elapsed 20659126 it was removed as `EXPIRED`, with absolute age 5006 ms. The stored/new/expired key hashes are equal, and there is no structure event between that final store and expiry. The start time precedes store completion because the 5-second lifetime correctly begins at the complete system metadata read's start. The refused read therefore encountered a proof-expiry boundary without a recorded intervening identity/structure change. This is more specific evidence than the prior App396 failures; it does not retroactively prove their cause.

No TTL, owner, secure-window, clipping or authorization check was relaxed. There was no repeat of a potentially submitted action. The current package contains scoped structural invalidation and focus-hint metadata, but not the subsequent proactive refresh of near-expiry proof. A next package must independently demonstrate that behavior. This attempt does not prove the scoped change has fully solved Presentation reliability, nor does this local manual-platform component establish model autonomy, physical gamepad injection or game completion.

## Preserved state

At entry, every prior Run was terminal, the exact Runtime service was absent, the original global accessibility/notification settings matched, and there was no real model key. At exit, all 91 accumulated Runs were terminal and the Runtime service had exited. Original history IDs remained; original run/message/event/attachment payloads were byte-identical. One original selected session's `updatedAt` advanced through normal selection restoration; all other original session content was unchanged, and the original selection was restored. Raw model/vault/display-role XML hashes matched. Accessibility, notification denial with all original permission flags, and the `null` overlay/immersive/animator settings were restored to the initial global baseline.

`presentation-failure.json` publishes only bounded error labels, counters and hashed-key diagnostics. Private DB/history, system dumps, credentials, full cloud responses, APKs, ROMs and game saves remain local. `frozen-scoped-patch-source.json` is the selected four-file source freeze record for this build; later mutable sources are not substituted for it. Logs and JSON retain their separate fixed-package scope. `manifest.json` covers this directory and excludes itself.
