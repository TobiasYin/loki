# Voice-empty final pair: Modern UI regression passed

The fixed final voice-empty pair passed **one Modern UI emulator case** in **23.356 seconds**, with zero failure, skip or retry. Explicit `emulatorUiComponents=true` was used. This is an existing chat UI integration regression, not execution of the voice-specific empty event, microphone, actual long-hold or cloud ASR path.

| Artifact | SHA-256 |
| --- | --- |
| Agent | `006554347a6012671f238110458dafa00b70ccbfb14e70b78e2013f4dd73a953` |
| Instrumentation | `1c6bf788b8ae8dc22ff7667506bee9d2e50786cfaedbd4ede6263c01b8743d89` |
| Input Lab | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

Only fixed copies from `build/voice-empty/pinned/` were installed with data preserved on isolated API36 ADB5042 / `127.0.0.1:5557`. Installed package-file hashes matched before and after. The actual WebView test uses trusted touch and keyboard on synthetic chat state with Native commands intercepted, covering disclosure animation, inline model/effort choices and optimistic-capability rollback. It does not contact cloud services, start a model task or issue device-control input.

The coordinator's19 Native voice checks,73 JS checks and physical generated-silence cloud result are separate sibling evidence. They were not rerun by this collector and are not counted as this emulator case. Voice-empty behavior is covered by its dedicated software/cloud layers, rather than falsely attributed to this Modern UI test. No other emulator case, Presentation or380-second background stream ran in this group. No Thor operation occurred.

Preflight found 117 existing Runs terminal, no exact AgentRuntimeService, no model key and original global baseline matching. At exit all 117 Runs were terminal and Runtime exited. Existing history IDs and original run/message/event/attachment payloads were byte-identical. Original session updatedAt changes: 0; all other session fields and original selection matched. Raw model/vault/display-role XML hashes, original accessibility, complete notification denied flags, null overlay/immersive/animator settings and installed hashes matched/restored. Notification permission was never granted for this UI-only group.

No private DB/history/backups/credentials, raw dumps, APKs, cloud bodies, ROMs or saves are published. This child directory and its own manifest are independent of prior release evidence; no older group was modified. The collector did not change source or run a build. `manifest.json` covers this directory except itself.
