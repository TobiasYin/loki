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
