# First dynamic-input attempt: fixture-scroll failure retained

The first fixed pair **failed its one dynamic component case** after 88.146s, with no skip or retry. Modern UI was not run. This is not an all-pass result.

| Artifact | SHA-256 |
| --- | --- |
| Agent | `33f084ad42b2436669f5e3ea4a4594290a4061953add561f3d34121b8c28b36c` |
| Instrumentation | `01be29c6fc73e7e2b07bd8fecccf6de6d524a6647f947e43bb8767bae05b6108` |
| Input Lab | `9d4f952028368a4490be17a9a81ae1c2335ad8ba5c9c65ec1732ef9821902aa6` |

Only fixed copies from `build/dynamic-input/pinned/` were installed and actual package-file hashes matched. API36 emulator ADB5042 / `127.0.0.1:5557` used explicit `dynamicContentAcceptance=true`. This is real Native RPC with an independent ordinary Activity receiver and no cloud/model/game operation.

Two screenshots actually differed while the ticker advanced25 ticks. An older captured frame still reached the stable button after27 further ticks. A logical-coordinate tap and stable semantic-node activation also reached it. Moving the node caused refusal, and a real modal window rejected the old frame without additional receiver touch. Those stages are recorded before the later failure; they do not turn the complete test into a pass.

The failure at test line203 was `Committed swipe did not actually scroll receiver`. Native returned submitted, and the receiver journal independently recorded the complete DOWN(540,1920),16 MOVE samples,UP(540,720) sequence over500ms, touchscreen source4098/device-1, with no CANCEL. Thus input actually reached the receiver. It recorded zero scroll callbacks. There were22 touch samples overall, including the earlier two80ms taps.

Read-only display facts confirm1080x2400 and density160dpi (density1). The fixture used22 rows of90dp; its base content height2264dp, or2336dp after the72dp button move, still fit the2400px viewport. The stored fixture screenshot shows all22 rows visible. The coordinating root and fixture reviewer also observed ScrollView.scrollable=false in the saved tree. The page therefore had no scroll range: the failing assertion could not test production scrolling-feedback behavior. This is a concrete fixture-size/precondition issue, not a failed dispatch or a reason to relax Native guards. The subsequent stale-frame-after-scroll assertion was not reached.

Cleanup completed with no errors. Original baseline was restored: all118 Runs terminal, Runtime exited, original IDs and non-session payloads preserved, original session updatedAt changes0 with all other fields preserved, selected/rawprefs/roles/vault/accessibility/complete notificationflags/nullsettings matched. Actual installed hashes matched. Notification permission was never granted by this collector. No Thor operation, production change or build occurred.

A justified later attempt may correct fixture rows to exceed actual display height and assert positive scroll range before swiping, using separately fixed Test/Lab hashes. This attempt's failure remains unchanged. The published screenshot is only the synthetic fixture; raw DB/history/credentials/private dumps/APKs/ROMs are excluded. `manifest.json` covers this directory except itself.
