# Keyboard input — software verification

This frozen bundle records software checks for guarded Unicode text editing and fixed keyboard keys/modifiers. Device receiver tests are recorded separately; no physical Thor, cloud/model or game result is claimed here.

- **474 JVM tests**, 0 failures/errors/skips, across 68 XML suites: device-core 229, bridge-core 41, authority 13, App 117, Input Lab 74. In the final build, device-core, authority and App tests executed; bridge-core and Input Lab tests were `UP-TO-DATE`. The initial build is provenance and is not counted again.
- **254 Runtime tests** and **73 chat tests** passed in separate current runs, with no failures/cancellations/skips. These are separate from the JVM total. Integration types produced an empty log; Root confirmed its exit code was 0.
- Initial build: success in 26 s, 414 tasks, 112 executed / 302 up-to-date. Final build: success in 20 s, 414 tasks, 77 executed / 337 up-to-date. Task-level distinctions and full log hashes are in [summary.json](summary.json); short excerpts are in [log-excerpts.txt](log-excerpts.txt).
- All four entries in [artifacts.json](artifacts.json) and all three final [pins.json](pins.json) were independently checked against actual file bytes. The debug App and Input Lab hashes match their artifact entries. APK binaries are not copied into this public evidence folder.
- Four debug lint report hashes and severity counts are retained; none has an Error or Fatal issue. Full reports and other full test/log outputs remain in ignored build directories.

[Source hashes](source-hashes.json) inventory the current modified/untracked production, test, contract and built-in keyboard manual sources. This is a selected changed-file inventory rather than a full atomic source snapshot. DEBUG fixture sources only support the separate Android test.

[All JVM XML hashes and counts](junit-xml-hashes.json) are retained. Only the four focused suites are published under `junit/`: keyboard parsing/release, Unicode text editing, gateway target handling and semantic tree behavior. The full XML inventory is counted once. [manifest.json](manifest.json) hashes every published file in this directory except itself.
