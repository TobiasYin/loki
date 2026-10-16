> 2026-10-13 整合状态：已进入根工程正式软件集成；以下独立任务证据保留为历史参考。当前产品接线、复现入口与本次验证见 [中文集成报告](../../docs/integration-report.md)，不能将旧模块通过直接当作集成通过。

# Thor Input Lab 0.3.1

独立 Kotlin / Android View 接收器，applicationId `dev.thor.inputlab`，默认独立进程与 UID。接收器源代码和模块测试位于本目录，通过根工程构建；不依赖 Thor Agent Runtime。APK 无网络权限、Pi、Shizuku、模型配置或输入注入器。只有 debug 变体带签名权限保护的 setup/report runner，release 不包含该 Provider；本轮窗口范围为 5–120 秒。最新实机证据见 [0.6 验收记录](../../docs/release-0.6-acceptance.md)。

已实现可构建第一版与接收器本地验证。**尚未完成 Thor 实机 POC-11 资格认证、上下屏校准、物理手柄基线或 POC-06/07。** `receiverQualificationVersion=unqualified-physical-baseline-pending` 始终随报告保留；本地通过不能推断其他模拟器或游戏兼容。

## 独立构建

需要 JDK 17、Android SDK platform 35 / build-tools 35.0.0、网络下载依赖或现有 Gradle cache。工具版本：Gradle **8.14.3**（wrapper 内固定 SHA-256）、AGP **8.10.1**、Kotlin **2.2.10**、JUnit **4.13.2**；实际传递依赖锁在 `app/gradle.lockfile`。minSdk 30、compile/targetSdk 35。无动态版本。

```sh
cd path/to/thor-agent
# 设置到自己的 JDK/SDK；本项目不修改全局设置。
export JAVA_HOME=path/to/jdk-17
export ANDROID_HOME=path/to/android-sdk
./gradlew :input-lab:assembleDebug :input-lab:testDebugUnitTest :input-lab:lintDebug
# Android 平台解析器测试 APK（不注入任何输入、不创建接收证据）
./gradlew :input-lab:assembleDebugAndroidTest
```

也可在本目录的忽略文件 `local.properties` 设置 `sdk.dir=path/to/android-sdk`。输出：

- 安装包：`app/build/outputs/apk/debug/app-debug.apk`。
- JVM 测试：`app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint：`app/build/reports/lint-results-debug.html`。
- 解析器测试包：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

```sh
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE shell am start -n dev.thor.inputlab/.LabActivity
# 在操作者已经确认可启动到该显示的情况下，作为实验记录启动参数；不承诺 Thor 支持。
adb -s DEVICE shell am start --display CONFIRMED_DISPLAY -n dev.thor.inputlab/.LabActivity
```

应用不硬编码上/下屏 ID，也不改变系统多屏设置。可用 Thor 启动器移动窗口；Environment 展示实际显示、窗口位置/尺寸、旋转、insets、API/固件、UID/PID、设备来源和运动范围。`I confirm this window is upper` 是每次冻结进报告的人工校准声明，不是自动证明。

0.3.0/scenarioVersion 3 新增沉浸式全屏测试窗口：使用公开 WindowInsetsController 隐藏系统栏，支持系统边缘滑动临时显示；Arm 前必须确认系统栏和键盘已经隐藏，冻结实际全屏状态、insets 与几何。观察中系统栏可见性或 insets 变化仍产生 sticky inconclusive。新模式的 target 坐标必须重新读取；此前普通窗口物理基线不改写。此模式不代表已经解决普通窗口中系统状态栏事件导致的发送端 TARGET_CHANGED 限制，发送端目标校验保持原样。

## 人工验收流程

1. 选择 case 和证据 mode。默认 `physical_baseline`；ADB/平台直调选 `manual_platform_api`；仅实际经过相应链路时选择 `manual_product_gateway` 或 `agent_e2e`。mode 是操作者声明，接收器不能认证输入发送者。
2. 确认显示，设置观察时长 5–60 秒。先放开触摸/按键并使已有轴回到实际 neutral。
3. 点 Arm：500 ms 后检查最后观测的持有状态；收起设置区但保留其尺寸，冻结窗口靶点和阈值。不要在录制期间操作设置区域。
4. 在观察窗口内施加输入。自动 finish 等待窗口结束和 300 ms 静默；截止后输入不能补救通过，持续输入造成不完整/不确定。自动结束避免 Finish 按钮自身触摸污染实验。
5. 查看 `pass/fail/inconclusive`、命中/错误、手势轨迹、手柄灯/摇杆/扳机与最新 RAW 摘要。导出 ZIP 由系统文档选择器明确选本地目的地。报告和事件区分 `android_dispatch`、`semantic_callback`、`receiver_diagnostic`。

Activity 的三个 dispatch 入口各记录一次 raw。Arena 不再次记录 raw；原生按钮只记录语义回调和触发 raw sequence，未伴随 dispatch 的 ACTION_CLICK 为 `semantic_only`。自绘区域的无障碍语义动作另记 `semantic_arena`，不能命中裸输入靶点。

| Case | 当前可运行刺激与判定 |
|---|---|
| LAB-01 | 人工确认上屏并制造真实焦点变化；显示/焦点时间线完整。只证明本接收窗口。 |
| LAB-02 / 03 / 14 | START 单击恰好一次，完整同 device/downTime 的 DOWN/UP、≤500 ms、轨迹不超 12 dp。错误靶点、不动作、semantic-only 失败。03 使用当前冻结窗口几何；实际旋转/裁剪发送端矩阵仍待 Thor。 |
| LAB-04-LONG | START 持续 ≥600 ms 且轨迹在 12 dp 内，然后实际 UP。 |
| LAB-04-SWIPE | START → END，100–5000 ms，必须观测到 MOVE；历史样本逐批保留。 |
| LAB-04-CANCEL | 真实 DOWN/CANCEL；取消不能当正常 UP/命中。 |
| LAB-05 | 原生语义按钮＋无细粒度节点自绘色块/文字，要求语义回调；报告 sceneRevision 可检验截图/UI 树新鲜度。 |
| LAB-06 | controller A（96）恰好一对 DOWN/UP；repeat 不新增按住，键盘和错键失败。 |
| LAB-07 | prepare 阶段冻结命名轴 profile。标准 X/Y/Z/RZ/LTRIGGER/RTRIGGER；符合明确物理元数据及广告范围的 AYN Thor Odin Controller 使用 X/Y/Z/RZ/BRAKE/GAS（axes 0/1/11/14/23/22），并冻结 deviceId。逐轴达到 50% 运动范围，再实际 neutral；容差 max(flat, 5% range)。缺轴失败，任何额外广告轴亦须实际 neutral。 |
| LAB-08 | 同一设备 A+B 持有区间重叠，实际释放；顺序点击失败。任何实际观测轴须 neutral。 |
| LAB-08-AXIS | A 持有期间有实际轴 excursion，并观测 A UP 和轴 neutral。 |
| LAB-09 | 确认上屏、焦点变化并接收 A DOWN/UP；不推断未收到的输入发去了哪屏。 |
| LAB-11 | 简单两步任务，按顺序真实点击 START → END；模型宣称成功不计入判定。 |
| LAB-13 | Fault probe 选择 budget/layout/restart；日志满、布局失效或 kill/reopen 后必须 inconclusive。 |

失焦/设备断开不会伪造 UP 或轴值；保留最后真实观测并标记 stale/unknown。尚未看到释放不等同于证明系统仍持续按住。错误 source 与设备能力来源通过位掩码区分；source/deviceId 不认证真人/Agent。

v2 将 `InputDeviceListener.changed` 与断连区分：所有冻结公开设备能力、窗口与显示环境完全一致、被变更设备没有持有输入且 attempt 无既有不确定性时，回调保留为诊断（`continuity.disposition=diagnostic_only`）。持有中重配置、added/removed、缺失/未知设备或环境变化仍 inconclusive。公开 API 无法认证隐藏输入路由，因此 `routingProven=false`、资格状态仍 pending。详见 [integration-notes.md](integration-notes.md)。历史 v1 inconclusive 报告保持原样。

## 日志、完整性与导出

每 attempt 独立保存 `files/reports/<attemptId>/`：`events.jsonl` 追加式 raw/场景/诊断、`report.json`、`summary.md`。录制前先同步 `active.json`；重启将其保存为 `interrupted-metadata.json` 并新增 `recovery.jsonl`、inconclusive 报告，新 receiverEpoch 不延续旧 attempt。已封存报告不被 reset 覆盖。

事件公共头含 schemaVersion、testRunId/caseId/attemptId、receiverEpoch、sequence、sceneRevision/layoutEpoch/deviceRevision、接收 uptime ms / elapsedRealtime ns / wall time、实际窗口 display/layout。事件自身 displayId 在此公开 API 不可获取，明确 null+reason。历史 raw 坐标不可得也写 null+reason；历史 local/pointer/axes 不省略。事件时钟与 elapsedRealtime 含深睡的时钟不能相减。

串行写盘队列 256 行，每行 fd.sync；每 attempt 最多 100,000 事件/样本或 32 MiB。配额/队列/磁盘失败是 sticky，drop 数明确，不会覆盖旧行或产出完整 pass。finish 取得串行写盘快照，保存稳定前缀 byte length/hash 与截止 sequence；截止后到下一次 arm 的送达继续追加 `late=true`，不改变封存场景。UI 的有限轨迹缓存只影响画面，不抽样原始日志。

最近报告预算 50 份 / 200 MiB，满额拒绝新 attempt；不会自动删除失败证据。导出后需要操作者明确清理本应用数据才能继续，v1 不做自动保留策略。ZIP 包含可读摘要、结构化 JSON、原始 JSONL 和每个**导出文件**的 SHA-256 清单。对外 descriptor 默认替换为散列；本地稳定前缀 hash 对应未脱敏本地文件，脱敏导出使用 `export-manifest.json` 的 hash 验证，不能混用。hash 不是防篡改签名。

## 本地验证与复现

```sh
# 已启动的模拟器：实际 ADB input → OS → Activity；脚本只用可见人工控件准备，无 IPC。
python3 scripts/emulator-smoke.py --serial emulator-5554 --adb path/to/adb
# 如使用隔离 ADB server，可另传 --port 5041。

adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w dev.thor.inputlab.test/dev.thor.inputlab.SnapshotInstrumentation
```

脚本安装 debug APK，在 emulator 上做正反例并读取本应用私有报告作为测试证据。解析器 instrumentation 只构造 MotionEvent/KeyEvent 验证复制与字段，不调用 dispatch、不写 raw journal；其 origin 是 `fixture_replay`，不能证明系统注入。

实际结果及限制见 [verification.md](verification.md)、[integration-notes.md](integration-notes.md)。规格来源：[Input Lab](../../docs/input-lab.md)、[POC-11](../../docs/poc-plan.md)、[ADR-011](../../docs/adr/011-input-lab.md)。父文档中的“仅文档阶段”已由本任务开发授权覆盖；不修改公共文档的历史记录。
