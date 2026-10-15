# 主流程实机结果补充 · 2026-10-15

后续根工程主流程已经用此处最新 0.3.0 APK 完成真实云模型的逻辑坐标和截图坐标触摸，两份独立报告 pass；平台 no-input / wrong-target / 上屏应用切换负例，以及 Native 触摸区域边缘、过期快照、换目标零分发控制均另有证据。发送端随后修复了新拍摄资源身份和根布局/可触摸区域分离；以下“发送端未修改”是相应子任务当时的范围记录，不能代表最终根工程。详见 [完整实机报告](../../docs/physical-test-report.md)。实体手指/手柄基线仍属于此前 0.2.0，不回写为 v3 物理资格通过；qualification 仍 pending。

# v3 沉浸式全屏测试窗口 — 2026-10-15

## 同日追加：平台负例的 evidenceMode

debug prepare 新增可选字符串 `evidenceMode`，缺省 `agent_e2e`，显式仅允许 `agent_e2e` / `manual_platform_api`；present null、非字符串、不支持的 mode 或空白/大小写变体均在分配 lease 前拒绝。该值经现有 mode 选择与 metadata.mode 路径记录，不改变输入权限或判定。新增 4 个 RunnerEvidenceModeTest，当前 64 tests 全通过（0 failures/errors/skipped）。根工程 lab test/lint/assemble、签名校验和 diff-check 通过；Lint 0 errors / 40 warnings。

最新 APK 2,783,647 bytes，SHA-256 `4712c6482bf630c0a59cd89cdfdea12e24b2e032ad5138faf93215ec6fd657ae`，仍为 0.3.0/code3/scenario3。本次未操作设备。以下 hash/测试数属于该参数追加前的追溯记录。

主流程 metadata-only target-events.log 已定位普通窗口上屏 SystemUI window11 每约两秒 TYPE_WINDOW_CONTENT_CHANGED，而 Lab raw/sceneRevision 均为 0。发送端保守目标保护保持不变。本次新增标准全屏窗口准备，独立标为 0.3.0/code3/scenario3；不会回写此前 0.2.0 物理手指/A键/轴基线，也不据此宣称修复普通窗口的状态栏事件限制。

resume 通过公开 WindowInsetsController 隐藏系统栏并允许 transient-by-swipe；Arm 前系统栏与 IME 必须已隐藏，窗口环境记录实际 insets 与各栏可见性。观察中 visibility/insets 改变 sticky inconclusive，布局/显示故障判定保留。全屏后的 target 坐标须重新读取。此轮未操作设备，隐藏系统栏能否消除该固件的 SystemUI事件仍交由主流程实机验证。

根工程 lab test/lint/assemble 成功，60 JVM tests 全部通过（0 failures/errors/skipped）；Lint 0 errors / 40 warnings。新增一条静态英文提示的 SetTextI18n；编译还提示 API35 已弃用 setDecorFitsSystemWindows，该公开API仍用于本机 API33，未引入新库或隐藏API。APK `app/build/outputs/apk/debug/input-lab-debug.apk`，2,608,570 bytes，SHA-256 `577975f5aead5c45c573b1c615e8f57a7e74b9fb65dea5bcf976c2e8c9b5be4c`。资格状态仍 pending。

# v2 重配置修复验证 — 2026-10-15

## 同日追加：诊断回调无状态变化时省略重绘

对安全证据 `build/physical/resume-2026-10-15/cloud-input/physical-cloud-logical-report.json` 与 `logical-events.jsonl` 的复核显示：观察窗前后 rawCount/sceneRevision 均为 0，15 条设备回调均 diagnostic_only。`emit()` 仅递增日志 sequence，header 不显示 sequence/deviceRevision；没有证据证明此次 TARGET_CHANGED 来自 header 内容变更。该失败仍须主流程排查，不据此宣称根因修复。

本次仅让 active 且 diagnostic_only、日志完整的设备回调在 emit 后省略 refresh/arena.invalidate；真实输入、场景变化、焦点/布局、不连续或日志失败保持刷新。日志与报告内容不删减，发送端目标校验未修改。根工程 lab test/lint/assemble 重新成功，60 tests 全部通过，0 failures/errors/skipped；Lint 0 errors / 39 warnings。最新 APK 2,772,975 bytes，SHA-256 `41b7242107e4d1743f86852baa15b3e785ed0b555d09185257f418befdca280a`。以下为更早构建的追溯记录。

## 同日追加：debug runner 有界观察窗

prepare 新增可选整数 `observationSeconds`，默认 5、仅允许 5..30；lease 为 `(seconds+10)s`，最多 40 秒。签名权限、调用包/UID、testRunId、lease 与方法校验保留，没有新增输入或 verdict 操作。普通 UI 时长范围不变。新增 4 个 RunnerWindowTest 覆盖默认值、所有有效时长的 ten-second margin/cap、边界/溢出拒绝和错误 Bundle 类型拒绝。

根工程同一 lab test/lint/assemble 命令重新成功：当前共 60 tests，全部 0 failures/errors/skipped；Lint 0 errors / 39 warnings（相较前次增加一条数值 EditText 的 SetTextI18n 提示）。签名校验成功。最新 APK 2,772,903 bytes，SHA-256 `1ad423ec66d239820a4ce5b40752d33ebf0314b4b6a09a87bb4d72e1ed27df2f`；仍为 0.2.0/code2/scenario2。未执行设备操作。本段以下 hash/测试数属于 runner 扩展前的重配置修复产物，保留追溯。

## 重配置修复首次构建

从根工程运行 `JAVA_HOME=path/to/jdk-17 ANDROID_HOME=path/to/android-sdk ./gradlew :input-lab:testDebugUnitTest :input-lab:lintDebug :input-lab:assembleDebug --console=plain` 成功。仅构建 Input Lab；未运行会重置 Native 配置的 fixture runner，未安装/启动 APK、注入输入或修改设备设置。

当前 56 个 JVM 测试全部通过：AttemptTest 37、JournalTest 7、DeviceContinuityTest 7、AxisProfileTest 5；0 failures/errors/skipped。新增测试覆盖相同空闲回调、真实 tap/A 键完成后的回调、增删/未知/缺失设备、身份/来源/range/fuzz/能力变化、焦点/生命周期/布局/显示变化、既有不确定性、被变更设备的持有状态、其他设备持有不污染、Thor BRAKE/GAS profile 冻结、键盘/虚拟/不明确设备拒绝、跨设备借用与缺轴/未归零拒绝。结果位于 `app/build/test-results/testDebugUnitTest/`。Lint 0 errors / 38 warnings（英文 UI 本地化、固定 target35、较新版 Gradle 提示），位于 `app/build/reports/lint-results-debug.html`。

根工程 APK：`app/build/outputs/apk/debug/input-lab-debug.apk`，2,770,419 bytes，versionName 0.2.0 / versionCode 2 / scenarioVersion 2；SHA-256 `9db59d901fddc8915844092ea5c2c0b54d7209b0bdae41e01d85746291be14a6`。此轮未执行实机复测，不能将 JVM 与构建成功当作物理基线通过。旧 v1 基线仍为 inconclusive，`receiverQualificationVersion=unqualified-physical-baseline-pending` 不变。

主流程最小实机复测：15 秒观察窗内，分别完成 LAB-02 真实手指单击、LAB-06 实体 A DOWN/UP、LAB-07 两摇杆及两扳机逐轴充分 excursion 后实际 neutral；三个正例均需捕获重复 changed，核对其 `continuity.disposition=diagnostic_only`、完整冻结 profile、窗口显示信息和 Android raw 来源。LAB-14 无动作应 fail（重复 changed 不能产生成功）。再于 LAB-04-LONG 中持有上屏触摸跨越该触屏自身 changed 后释放，应因 `input_device_changed_while_input_held` sticky inconclusive。并用 dumpsys/logcat核对两屏路由，公开能力一致本身不证明隐藏 InputReader association。

以下为历史 v1 记录，保留原结果与 hash。

# 第一版验证记录 — 2026-10-13

产物仅位于 `tests/input-lab-app/`。结论：独立 debug APK 已构建、签名校验、安装和运行；接收器判定/日志本地测试与 API36 emulator 回归完成。**Thor 实机 POC-11、物理手柄基线、上下屏/IME 与真实游戏兼容未验证；资格版本仍为 unqualified-physical-baseline-pending。**

## 实测构建和包

- JDK17（Microsoft 17）、Gradle8.14.3、AGP8.10.1、Kotlin2.2.10、compile/target35、min30。
- `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug` 成功，完整结果在 `evidence/build-and-test.txt`。
- 44 JVM 测试：37 判定正/反例，7 追加日志、样本/byte预算、确定性队列溢出、磁盘失败、原子报告和稳定前缀快照。JUnit XML 在 `evidence/TEST-*.xml`，全部 0 failure / 0 error。
- 4 Android 平台解析检查通过，覆盖历史样本复制并 recycle、多指 pointerId、批次扳机/摇杆值、KeyEvent composite source/scan/repeat。只构造样例，未调用 Activity dispatch 或注入系统。结果在 `evidence/android-parser-fixtures.txt`，origin=fixture_replay。
- Lint：0 errors / 38 warnings；主要是英文实验室文本未本地化、固定 target35 和已有较新版 Gradle 的提示。未通过忽略所有 Lint 来掩盖错误。
- `apksigner verify --print-certs` 成功，debug 证书信息在 `evidence/apk-signature.txt`；`aapt dump permissions` 无申请权限，见 `evidence/apk-permissions.txt`。
- applicationId `dev.thor.inputlab`，普通独立应用 UID/进程；不嵌入注入器、网络/模型、Pi、Shizuku、sharedUserId 或测试 IPC。
- 最终 APK SHA-256：`68865439ba06fc2b04a422a7b3397e0cca10b300b80b5b9e02227549fdda79fd`。回归脚本记录的 APK hash 与最终输出一致。

## 实测 Android 分发回归

独立 AVD `input_lab_isolated`，API36 / Android16 / arm64，Google SDK image `BP22.250325.006/13344233`，emulator35.5.10，单 display。为避免本机磁盘空间不足，使用本任务目录内的空白 ext4 userdata，并由 emulator 扩展；不是 Thor 固件或实体设备。AVD 与数据在忽略的 `.tooling/` 内；以专用 ADB port5042、emulator5660 操作。初始其他实例存在前台干扰的结果未作为通过证据。

`python3 scripts/emulator-smoke.py --adb path/to/adb --port 5042 --serial emulator-5660` 完整成功。仅用屏幕可见的 case/mode/Arm 操作准备，再由 ADB shell input 经 Android 分发送达。mode=manual_platform_api；不经过产品网关，不冒充真人基线。

| 刺激 | case | 接收器实际判定 | raw 数 |
|---|---|---|---|
| single-tap | LAB-02 | pass | 2 |
| no-action | LAB-14 | fail | 0 |
| wrong-target | LAB-02 | fail | 2 |
| long-press | LAB-04-LONG | pass | 47 |
| swipe | LAB-04-SWIPE | pass | 29 |
| keyboard-button-a | LAB-06 | fail | 4 |
| sequential-gamepad-buttons | LAB-08 | fail | 4 |
| two-step-task | LAB-11 | pass | 4 |
| log-budget | LAB-13 | inconclusive | 2 |
| process-restart | LAB-13 | inconclusive | 0 |

正例和负例的**判定都符合预期**。single-tap 恰好 2 条 Activity raw DOWN/UP 和 1 次命中；long/swipe 的 MOVE 按实际分发完整保留，没有 UI 抽样写入。keyboard-button-a 观察到平台兼容层另发的两条键盘/D-pad 事件，4 条 raw 都据实际送达保留，未伪装成游戏手柄或误归为子 View 重复。顺序 gamepad A/B 不满足持有区间重叠。

每份正常 attempt 的稳定前缀 hash 在脱敏前由脚本重算匹配；sequence 连续；配额报告明确 incomplete/drop；重启报告保留旧 attempt 元数据引用，新增 epoch 和 restart 诊断，结果 inconclusive。数据在 `evidence/emulator-smoke/`，脚本输出在 `evidence/emulator-smoke-output.txt`。对外证据的 descriptor 已散列，原本的 receiver prefix hash 指私有未脱敏前缀；脱敏证据文件由 `evidence-manifest.json` 单独校验。

## 本地导出实测

通过应用内 Export ZIP → 选择重启报告 → 系统 ACTION_CREATE_DOCUMENT → 本地 Downloads URI 完成实际导出。产物 `evidence/export-restart.zip`：结构化 report.json、原始 events.jsonl、summary.md、recovery.jsonl、interrupted-metadata.json、export-manifest.json。每个导出文件 SHA-256 与清单一致；没有明文 descriptor；重启新/旧 epoch 不同，报告仍为 inconclusive。自动检查结果在 `evidence/export-verification.json`。

## 待实机/后续范围

- Thor 实体手指/手柄 POC-11：ABXY/D-pad 完整矩阵、摇杆/扳机真实范围、历史样本、组合及故障释放；当前轴/组合判定只由确定性样例验证，不能用其证明真实轴注入成功。
- Thor 上下屏角色、跨屏投递、两个显示的焦点、IME/休眠/旋转/固件差异；emulator 只测试单显示，source/deviceId 不能认证输入来源。
- 真实游戏/模拟器过滤和兼容、POC-06/07、发送端 submitted/error 与 Native 阻断/cleanup trace；接收器的 pass 只表示本 attempt 的本窗口断言成立。
- v1 释放须在同一观察窗口完成；无外部取消边界，尚未对接设计中的独立 1 秒清理观察窗口。
- 合成配置 LAB-12、签名/UID/lease IPC、完整 Agent 联调 LAB-10/11（当前 LAB-11 只验证两步触摸任务）、安全窗口及丰富感知场景留到下一阶段，详见 integration-notes.md。

没有推送、发布、合并 main 或修改公共 docs/根工程。APK 构建输出按局部 .gitignore 忽略；源代码、固定依赖锁和验证证据纳入本地提交。
