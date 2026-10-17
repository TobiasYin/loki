# Second dynamic-input attempt: different failure retained

The justified fixture-height correction kept the **same App33f0** and changed only Test/Lab. This second pair failed its one dynamic component after **37.053s**, before the new scroll-range precondition. ModernUI was not run, and this pair was not retried. The first no-scroll-range failure remains unchanged in `../attempt-01/`.

| Artifact | SHA-256 |
| --- | --- |
| Agent | `33f084ad42b2436669f5e3ea4a4594290a4061953add561f3d34121b8c28b36c` |
| Instrumentation | `d4d4f09c09809434fbc23d90d4939271ce7dc969910f82775ebc577db71aef5c` |
| Input Lab | `4cd028e29e8ae4b8fb40217f70eaa0304c21b2a28a7f43fd9b313dc536f36689` |

Fixed files from `build/dynamic-input/pinned-fixture-fix/` were installed with data preserved and actual hashes verified on API36 emulator ADB5042 / `127.0.0.1:5557`. Explicit `dynamicContentAcceptance=true` was used. No production/source change, build, cloud, model or Thor operation was performed by this collector.

Dynamic old-frame and logical taps, stable-node semantic activation and moved-node refusal were reached. Capture metadata also records contentChangedDuringCapture=true while inputTargetChangedDuringCapture=false and frameInputUsable=true. This is observed revision compatibility, not a guarantee of pixel equality. Those partial stages do not make the complete case pass.

At the real-dialog old-frame negative stage, Native returned **INVALID_ARGUMENT, observed**, while the assertion expected TARGET_CHANGED. The test recorded original failureType=ComparisonFailure; its final cleanup then recorded selection AssertionError and the final JUnit stack shows the cleanup assertion, masking the first stack. No swipe or new scroll-range assertion ran. The independent receiver journal sealed normally:4 motion samples from the two earlier taps,3 click callbacks including semantic activation,0 scroll events. It contains no new dispatched touch for the failing dialog call.

Read-only analysis found the current publication gate uses a bare require for a tool removed from its published set; an explicit Native target-admission refusal can consequently become INVALID_ARGUMENT. This is a concrete boundary worth fixing, but this attempt did not persist the exception origin/authority details, so it does not alone prove that was the only possible cause. A post-mutation view/publish error could also explain a failure response after state changed. Follow-up diagnostics must retain the first failure and typed availability without relaxing authorization or accepting arbitrary error codes.

The cleanup assertion did **not** mean selected state remained wrong: the independent post-test private DB comparison found the original selected ID and all original session payloads unchanged. No manual DB repair, UI selection or retry was needed. All119 Runs were terminal and Runtime exited. Every original table's IDs and payloads matched, session updatedAt changes0, and rawprefs/vault/roles/accessibility/complete notification deniedflags/nullsettings/actual installed hashes matched/restored. Notification permission remained denied throughout.

Raw DB/history/backups, detailed Native trees/private dumps, credentials/APKs/ROMs are excluded. `component.json` intentionally selects bounded counters, fixed result labels and capture compatibility; the full original report remains ignored locally. No fullcomponent/scroll/ModernUI success is claimed for this pair. `manifest.json` covers this directory except itself.
