# Presentation trace package: seven emulator cases passed

The fixed trace package passed all **seven cases actually executed on this App/Test pair**, with zero failures, skips or retries. This is scoped emulator acceptance, not complete release, cloud, physical controller or game acceptance. Earlier failed APK groups remain unchanged.

| Fixed artifact | SHA-256 |
| --- | --- |
| Agent | `387472aab1c12d735253879288f895a98548ad0964ed06eb69c94837e3317c46` |
| Instrumentation | `1bea4c10a729d38a4c4e208a59553b24f19ebe4a3dec6b28c4b62ee0c4117c5c` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only the fixed files from `build/emulator/presentation-trace-2026-10-17/run-01/` were installed. Installed package-file hashes matched before and after. The isolated API36 emulator used ADB 5042 / `127.0.0.1:5557`. No Thor operation or cloud call occurred in this group.

## Actual cases and order

| Group | Passed cases | Instrumentation duration | Explicit opt-in |
| --- | --- | --- | --- |
| Presentation Native component | 1 | 58.969 seconds | `emulatorAcceptance=true` |
| Short background 2, Modern UI 1, provider settings 2 | 5 | 54.116 seconds | `emulatorUiComponents=true` |
| Real-time long background | 1 | 395.558 seconds | `longBackgroundAcceptance=true` |

The three isolated startup-storage cases were not rerun here: their source was unchanged according to the coordinating build owner, and their earlier dbed/1bea result remains in `../expiry-startup/`. They are not added to the seven-case count or claimed as freshly executed on this App.

The Presentation fixture exercises an independent real WindowManager 2037 Presentation whose accessibility type remains -1. Native selected-screen screenshot/tree, semantic activation and actual coordinate DOWN/UP reached that receiver. Its independent report confirmed the actions. Old cross-screen snapshots, node references and frame references were rejected. Actual secure-window state was verified, protected pixels were withheld by the screenshot refusal path, and capture recovered after returning to the earlier safe state. The temporary fixture permission was dropped before tool execution. This is manual-platform Native component verification, with no autonomous model or physical gamepad-injection claim.

The bounded trace includes a real full metadata-read chain: refresh required with remaining 0, queued, worker begin, reader complete in 301 ms, and a new proof stored. The retained tail also includes fixed reader failures during the protected-state/recovery exercise. Subsequent fresh-hit remaining values count down from the new full-read start; cache hits do not renew the original 5-second deadline. The tail 64 does **not** prove the earlier 1,999 ms near-expiry branch was observed. A single current pass does not establish the missing scheduling/reader cause of the older dbed failure, or replace that failed record with success.

Modern UI used trusted touch/keyboard on synthetic display state, with Native commands intercepted. It verified disclosure animation, inline controls and optimistic-capability rollback. Provider settings used disposable fixture credentials and controlled loopback responses, not the official cloud. The short background pair used actual Native/Pi and local SSE, including real configuration probing, HOME/Activity finish, the same execution Host/WebView and Pi follow-up, the actual notification Stop action with partial context retained, and asynchronous idle-writer handoff.

## Long background and retained context

The long case measured **380,260 ms** after HOME and Activity destruction. It persisted all 380 live markers across 13 checkpoints. The final checkpoint contained marker 380, 385 events and 131,481 bytes. During that interval the test observed SQLite only: no JavaScript evaluation, chat opening, fake clock or visibility override. The outer collector also sent no AVD command during the measured interval.

After UI return, the same Host, never-attached execution WebView and Pi session remained. The full durable turn and the next follow-up HTTP context contained every marker. Four loopback requests were used; cloud requests and device input calls were zero. Completion and follow-up occurred after UI return. Long-hidden notification-stop is a separate coverage boundary; the short pair independently validates notification cancellation and retained partial context.

## Preservation

Before installation, all 92 original Runs were terminal, the exact Runtime service was absent, there was no real model key, and the original global accessibility/notification baseline matched. At exit, all 102 accumulated Runs were terminal and the Runtime service had exited. Fixed installed hashes were unchanged.

Original history IDs were preserved, with original run/message/event/attachment payloads byte-identical. One original selected session's `updatedAt` advanced through normal selection restoration; every other original session field and original selection remained unchanged. Raw model, vault and display-role XML hashes matched. The initial global accessibility entries, notification denied state with all original permission flags, and the original `null` overlay/immersive/animator settings were restored. Per-test restoration reports include only this group's two short cases and long case, excluding earlier appended reports.

`summary.json`, bounded fixture reports and the three successful instrumentation logs identify exactly this pair. `presentation.json` excludes raw dumps, window titles, body text and full receiver events, retaining only safe counters/flags/error labels/hash diagnostics. Private backups, DB/history, credentials, APKs, full cloud responses, ROMs and game saves remain local. No source or build was changed by the evidence collector. `manifest.json` covers this directory and excludes itself.
