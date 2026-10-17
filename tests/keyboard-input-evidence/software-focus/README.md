# Keyboard input — observed input focus

This incremental software bundle records actual input-focus exposure and Agent instructions to activate/re-observe the intended editor before keyboard shortcuts. The DEBUG fixture now passively records focus, and its component test restores editor focus through the real `input.activate` tool after ENTER. Device acceptance is documented separately.

- **475 JVM tests**, 0 failures/errors/skips, across **68 XML suites**: device-core 230, bridge-core 41, authority 13, App 117 and Input Lab 74. One meaningful semantic-focus test raises the earlier 474 total by one. In the 22-second build, bridge-core was `UP-TO-DATE`; the other four test tasks executed.
- Focus build passed in **22 s**, 414 tasks: **92 executed / 322 up-to-date**. The later shortened-prompt packaging build passed in **5 s**, 274 tasks: **13 executed / 261 up-to-date**, without additional JVM test execution.
- The first focus Runtime run was **253/254 passed, 1 failed**. The existing compression/history constraint test rejected the longer system instructions with `LIMIT_EXCEEDED`. The added keyboard paragraph was shortened while retaining its rules; budgets and assertions were not increased or loosened. The final Runtime run passed **254/254**. Both complete log hashes and the failed test are preserved in [summary.json](summary.json).
- Chat's **73 passing tests** are retained from this task's earlier run. Its UI and test source byte hashes are unchanged; this is not a fresh post-focus chat execution.
- All four [artifact entries](artifacts.json) and all three final [pinned APKs](pins.json) were checked against their actual bytes. Four current lint reports contain no Error/Fatal issues. Full outputs remain in ignored build folders; summary contains their hashes and counts.

[Source hashes](source-hashes.json) cover 32 current modified/new source, test, contract and keyboard-documentation files. Comparisons against both earlier frozen inventories identify each changed/added source. Differences from the initial software snapshot are:

- `app/src/androidTest/java/dev/thor/agent/EmulatorKeyboardInputTest.kt`
- `app/src/main/assets/skills/adb-operations/references/keyboard-input.md`
- `docs/keyboard-input.md`
- `runtime/src/context.ts`
- `tests/input-lab-app/app/src/debug/java/dev/thor/inputlab/KeyboardFixtureActivity.kt`
- `tools/core/src/main/kotlin/dev/thor/tools/SemanticTree.kt`
- `tools/core/src/test/kotlin/dev/thor/tools/SemanticTreeTest.kt`
- `tools/diagnostic/src/main/kotlin/dev/thor/tools/diagnostic/AndroidBackend.kt`

The compression test source still equals Git HEAD, and context code outside the one system-prompt source line also equals Git HEAD. These checks and full current JVM XML hashes are in the summary.

No new emulator or physical-device acceptance, cloud/model run or game completion is claimed here. The earlier receiver failure remains a failure: ENTER and CTRL+A arrived, but ENTER changed editor focus; injection acceptance alone is insufficient. The original [software](../software/) and [fixture correction](../software-fixture-fix/) bundles remain frozen. [manifest.json](manifest.json) covers every file published in this directory except itself.
