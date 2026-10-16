# 页面恢复／停止边界集成：统一软件验证

本目录是独立软件证据快照，不覆盖旧 `software-checks.json`、旧哈希或任何共享清单。收录父任务已完成的 Gradle02、静态产物检查、实际 JVM XML 和软件脚本结果；本次证据整理没有运行测试、Gradle、模拟器或真机。

| 检查 | 实际结果 | 范围与复用边界 |
| --- | --- | --- |
| 统一 Gradle02 | 成功，19 秒；506 个任务：96 执行、410 UP-TO-DATE | 编译／JVM／lint／打包；不等于运行 Android instrumentation |
| JVM XML | 56 个套件、381 项，0 failure／error／skip | App 81、device-core 174、core 39、device-authority 13、Input Lab 74；逐 XML 哈希、时间和计数见 `junit-xml-hashes.json` |
| Runtime Node | 115/115，0 失败／取消／跳过／todo | 保留停止边界补丁的成功日志；真实 Pi／SDK 和受控 SSE、合成平台端口；源码指纹与该次冻结点一致，本目录没有重跑 |
| App JS Node | 58/58，0 失败／取消／跳过／todo | 最终 UI 状态检查的成功日志；JSDOM 不替代真实 WebView 验收 |
| integration TypeScript | 退出码 0 | 复用最新停止边界类型检查；成功时日志为空，相关源码指纹一致 |
| 产物检查 | 成功 | 调试／未签名 release 的静态产物和权限检查；没有安装／发布 |
| lint | 102 个 Warning，0 Error／Fatal | 来自实际六份 lint XML，保留警告；部分报告复用缓存，没有声称零警告 |

Gradle02 中 App 与 Input Lab JVM 测试任务实际执行；device-core、core、device-authority 测试为 **UP-TO-DATE**。381 是实际保留 XML 的总数，不能描述成该轮全部重新运行。弃用 Gradle 特性警告也保留在构建日志中。

首轮 `initial-gradle-failed.log` 因 Input Lab 调试 fixture 的 `MotionEvent.displayId` 引用编译失败，保留失败原样；成功结果仅归属修正后的 `gradle-02.log`，没有把首轮记为成功。

## 固定构建时点

完整身份和文件大小见 `gradle-02-artifact-pins.json`、`artifacts.log` 与 `summary.json`。

| 文件 | Gradle02 时点 SHA-256 |
| --- | --- |
| App debug | `429ffa7de34897990f10a0b448454a4482af17cef1c8ab22b298cf7da3ce2b13` |
| Input Lab debug | `ab0de9aded3cdd11a0b9b87cda3526b0ddc03c568067a9bbcfbbfb30d292b25f` |
| Android 测试 APK | `0d6604db98d0450a5733d95c9b467574a7ca7bc4058d6c00ff49b39bdb7d207e` |

测试 APK 是父任务在 Gradle02 成功后固定的**构建时身份**。后续仅测试 harness 清理和重编可能改变该文件；本目录刻意不采集或套用新的 TEST 哈希。后续设备运行应记录实际安装的 APP／TEST／Lab 身份，不能将新测试包倒填为本轮整体验证，也不能将旧模拟器或真机结果改记为当前 App 已运行通过。

`source-hashes.json` 固定九组路径和内容聚合指纹，以及十八份关键源文件的字节数、时间和 SHA-256；Runtime／provider-stop／integration／新测试／依赖锁和配套 Native 诊断、物理观察器与已通过 115 项的独立停止边界快照逐项比对一致。Kotlin 观察器指纹用于接口来源，不声称它已执行；源文件收集是逐文件时点快照，不是完整构建输入的原子归档。

本目录日志仅含软件命令、编译信息、测试名和结果、静态产物权限信息，不包含凭据、模型思考／对话、原始云端 SSE 或设备数据库。`manifest.json` 只列本目录除自身以外的文件。页面恢复和停止边界的具体行为分别见相邻 `target-recovery/`、`stop-boundary/`；普通停滞 SSE 测试不能证明 game11 真机停止卡住的具体原因。云端、Android、双屏、Citra、语音和硬件验收仍须各自独立记录。
