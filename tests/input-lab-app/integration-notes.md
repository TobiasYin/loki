> 2026-10-13 整合状态：已进入根工程正式软件集成；以下独立任务证据保留为历史参考。当前产品接线、复现入口与本次验证见 [中文集成报告](../../docs/integration-report.md)，不能将旧模块通过直接当作集成通过。

# Integration notes

本任务只实现独立接收器，不读取或编译其他任务产物。将来注入端以 Android 系统分发作为唯一动作数据通道；没有模拟点击、setPassed、事件回放、可写 provider、广播或 HTTP 入口。正式 APK 只有正常 launcher Activity 对外可启动。

## 当前冻结的局部契约

- 暂定包 `dev.thor.inputlab`，默认独立 UID/进程，无 sharedUserId。debug signing 由标准 Android 工具生成；正式发布身份/签名与受信 runner 关系仍属 Q-18，未对 Agent 作假设。
- report/event schema 1，当前 APK 0.3.0/versionCode 3、scenarioVersion 3，固定种子 1101、固定画面比例靶点。旧 v1/v2 报告保持原判定与版本；重启恢复保留旧 attempt 的 scenarioVersion。seed 当前只标识固定布局，不声明实现随机网格生成器。新增判定必须提高版本并重做相关接收器资格验证。
- v3 是新增沉浸式全屏测试窗口模式：公开 WindowInsetsController 在 resume 时隐藏系统栏，允许 transient-by-swipe；decorFitsSystemWindows=false，但仍按实际 systemBars/IME insets 布局。Arm 前须观察到各系统栏和 IME 已隐藏，冻结完整窗口、各栏可见性和实际insets。观察中可见性/insets 变化产生 sticky `window_insets_changed`，现有 display/layout 故障策略保留。它不忽略任何发送端可访问性事件，不声明修复普通窗口系统栏事件的 TARGET_CHANGED 限制；几何变化后必须重新获取 target。
- 手动 control 的 prepared/armed/recording/finished 通过 UI 和自动 finish。记录前等待准备触摸释放和已观测按钮/轴 neutral；默认 5 秒，quiet 300 ms，长按 600 ms，单击 max 500 ms/slop 12 dp，滑动 100–5000 ms。有界观察结束后不能用迟来的成功输入补救。v1 无外部取消边界，释放须在同一观察窗口内；设计中的独立 1 秒清理观察窗口留待发送端联调。
- debug RunnerProvider 的签名权限及调用包校验、owner UID/testRunId/lease/deadline 约束保留，仅 prepare 新增可选整数 `observationSeconds`（省略为 5，范围 5..30，越界/错误类型拒绝）。lease 自 prepare 起为 `(seconds+10)*1000ms`，因此最多 40 秒；该额外十秒只供准备/完成/读取，不能延长输入 deadline。普通 UI 仍允许原有 5..60 秒。接口只有 prepare/describe/report/release，不提供输入或设置 verdict 的操作。
- debug prepare 另允许可选字符串 `evidenceMode`，仅接受 `agent_e2e` / `manual_platform_api`，缺省 `agent_e2e` 保持兼容。显式 null、非字符串、其他 mode、大小写或空白变体均在分配 lease 前拒绝。该值只选择现有 UI mode 并经原 metadata.mode 写入报告，不改变判定或输入权限；平台自动负例必须显式选择 `manual_platform_api`，不能把无模型实验声明为 agent_e2e。mode 仍是测试方声明，不能独立证明发送链路或真人来源。
- 关联 ID 写在日志窗口头，原始 Android InputEvent 不天然带 Agent runId/toolCallId。`agentRunId/senderTraceRef/senderResult=null`；外部汇总时按同次启动、相同时基和已隔离窗口关联。mode 是人工声明，不证明网关覆盖。`systemInjectionProven=false` 表示接收器无法独立认证发送链路。
- actualWindowDisplayId 是窗口事实；eventDisplayId 明确 null，编译目标公开 InputEvent API 无此字段，未使用隐藏反射。窗口坐标、屏幕位置、arena 与 target bounds、rotation/insets/layoutHash 均保留，不导入发送端的坐标变换算法。
- 在 Activity dispatch 前同步复制事件字段/历史样本，随后进入有界磁盘队列。每一次 Activity 送达记一行；系统兼容层如果另送一组事件也真实记录，不能凭 keyCode/deviceId 静默合并成“同一输入”。子 View 不重复记 raw。
- 轴原值与 device/source-specific ranges 均记录。LAB-07 默认 profile 固定 X/Y/Z/RZ/LTRIGGER/RTRIGGER；prepare 阶段若公开元数据是 AYN Thor、唯一启用的非虚拟 Odin Controller（vendor 8224/product 273），且完整广告 X/Y/Z/RZ/BRAKE/GAS joystick ranges，则选择命名 `ayn_thor_odin_xy_zrz_brake_gas` profile，冻结 axes/deviceId/ranges 并写入 expectations。不会从实际事件结果倒推映射或改变阈值，不允许其他设备补足该 profile。所有观测到的广告轴仍须回到实际 neutral。LAB-08 分为 A+B 和 A+axis 两个独立刺激。
- InputReader DISPLAY_INFO 重配置可能反复通知 changed；v2 不将回调本身视为断连。仅当所有设备公开身份/来源/范围（含 fuzz/resolution）/启用状态/keyboardType/controllerNumber/支持 keyCode 集合、窗口焦点/生命周期/几何/insets、两屏信息均与 arm 快照完全相同，且被 changed 的设备自身没有 pending touch/key/nonneutral axis、attempt 没有既有 stale/fault 时，保留 device_changed 原始诊断并写 `continuity.disposition=diagnostic_only`。其他设备的 held 状态不污染未受影响设备的相同配置通知。added/removed、未知或缺失快照、能力差异、持有中变化、环境不连续均 sticky inconclusive；不伪造释放/neutral。dispatch 也核对冻结设备配置，运行期间不替换 ranges。
- 上述公开配置连续性不能证明厂商 InputReader 的隐藏 display association、扫描码映射或事件路由不变；诊断明确 `routingProven=false`，eventDisplayId 仍不可获取。真实双屏路由仍需外部 dumpsys/logcat 与物理基线证据；本次变化不会升级资格状态，也不回写旧报告。
- active attempt 的 diagnostic_only 设备回调仅追加日志，不再刷新未变化的 header/arena。真正 raw 输入、场景、焦点、布局、不连续或日志失败仍正常刷新；未修改发送端目标新鲜度校验。`emit()` 只增加日志 sequence，不增加 rawCount，header 不显示 deviceRevision/sequence。当前云输入失败报告在观察窗前后 rawCount/sceneRevision 均为 0，因此没有证据把 TARGET_CHANGED 归因于 header 计数变化；去除无用重绘属于接收器优化，是否影响该故障仍需主流程复测。
- 对来源/焦点/时间/布局不明确的证据采用 fail/inconclusive；失焦仍能实际接收 UP 时可证明该接收器观察到释放，stale 标记仍保留。生命周期暂停使普通输入用例不确定，LAB-01/09 可继续观察焦点；旋转重建结束旧 epoch，重启后恢复不确定报告。
- descriptor 保留私有本地日志，对外导出散列；导出前缀 hash 属于本地原文，导出清单验证脱敏文件。意外损坏检测不等于对 shell/OS 的防作弊。

## 明确留到下一阶段

签名级 Binder IPC/调用者 UID/证书/lease、受信 runner 身份拒绝矩阵、无抢焦点的游标读取；合成配置文件读写/恢复；受保护 FLAG_SECURE 画面；随机视觉 fixture、多控件列表/滑块/弹窗与手柄驱动多步菜单；可配置按钮/轴 profile 与机型校准持久化。

LAB-10 的撤销/零业务输入不能靠接收器零事件证明；需要 Native 阻断与释放记录、完整窗口以及前后正向对照。v1 不给 LAB-10/12 判通过，也不宣称产品网关或权限撤销集成完成。对双屏物理路由、同时焦点、IME、系统保留键、Shizuku/厂商注入器、真实模拟器兼容均须 Thor 实机补证。

receiverQualificationVersion 始终是 `unqualified-physical-baseline-pending`；JVM 正反例与 emulator dispatch 不能替代实体手柄/手指和 Thor POC-11。fixture_replay 永远只在离线/解析测试输出中，不与 Android dispatch 的 live 报告混用。

## 官方核对（2026-10-13）

- [Activity dispatch/focus API](https://developer.android.com/reference/android/app/Activity)：本应用窗口接收边界，不是全局监听。
- [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)：pointer、batch history、轴与 uptime 事件时间；平台解析器测试实际覆盖字段复制。
- [Controller actions](https://developer.android.com/games/sdk/game-controller/controller-input)：KeyEvent 与 joystick MotionEvent 分开，来源位掩码与 repeat 检查；不是普通 APK 跨应用注入承诺。
- [SystemClock](https://developer.android.com/reference/android/os/SystemClock)：uptime 与 elapsedRealtime 深睡语义不同。
- [AGP 8.10 release notes](https://developer.android.com/build/releases/agp-8-10-0-release-notes)：固定 AGP 8.10.1，JDK17/SDK35，Gradle8.14.3 实际构建。
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：明确用户选择 export URI，无任意外部文件写入口。
