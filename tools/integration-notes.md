> 2026-10-13 整合状态：已进入根工程正式软件集成；以下独立任务证据保留为历史参考。当前产品接线、复现入口与本次验证见 [中文集成报告](../docs/integration-report.md)，不能将旧模块通过直接当作集成通过。

# 独立模块集成说明

此模块不读取或编译 `native-bridge/`、`app/`、`runtime/`、`tests/input-lab-app/`。本版能力契约是局部 v1 接口和 APK assets 中的固定 schema。独立构建与 JVM 测试已经完成；跨模块集成、Android 设备冒烟和 Thor 实机均未完成。

## 原生授权端口

`core/Authorization.kt` 提供 `AuthorizationPort.validate/claim/commit`。`ManualTestAuthority` 是名称和界面均明确标注的进程内 TEST 实现：固定 manual session、单 Run、Native 签发 UUID、单调 epoch、有效工具集合、deadline、取消和调用去重/预算。默认操作/调试关闭。

正式集成须用 Native 的单一 Capability Store/Run lease 事实源替代 TEST authority，接入可信桥身份、真实 Session/Run 绑定、持久化 epoch、tool event 和附件存储。不能将模型 JSON 中的 enabled 或 capabilityEpoch 视作凭据。当前诊断 APK 没有对其他应用开放 tool IPC 或 WebView 桥。

`Gateway` 获取设备互斥锁后再次检查授权、快照与后端；副作用提交用 `Checkpoint.effect` 调用 `AuthorizationPort.commit`，仅在短暂系统提交/rename 上持有授权锁，不在完整手势回调、Shell 等待上持锁。撤销取消整个旧 lease；旧读结果丢弃。一个已提交输入可以改变自己的布局，结果交付仍检查授权，避免把动作自己造成的布局变化误判为尚未提交的拒绝。OS 已接收的单次手势不能撤回。

每个 Envelope 含 Session/Run/toolCall/tool/schema/epoch/request/deadline/snapshot；内部时限用单调 elapsed clock。对外 JSON 的 observedAt 为 UTC ISO 时间；备份的创建时间使用 wall clock。Core 中观测时刻数字只供执行审计，不应直接投影为 wall timestamp。

## 显示与感知差异

- 使用 DisplayManager 的当前 name/ID/尺寸/旋转/density；公开 name 不是稳定物理唯一 ID。因此本版选择每次进程启动重新人工确认，不持久化 display 角色，也不承诺自动把宿主移动到 Thor 下屏。
- 目标来自选定 display 的 Accessibility 可观察应用窗口；多包/null root 标 unknown/ambiguous。目标 revision 包含窗口边界/层/焦点和该屏内容事件计数，60s TTL；DisplayListener 使显示变化失效。仍不能把窗口观察与 Android 分发原子绑定，OEM 竞态需实机验证。
- 真实触摸只有 Accessibility 指定 display API，没有 `input` shell 逃生口；手势固定上限 5s。unknown 的待完成手势使该宿主后续触摸 unavailable，直到真实回调。若整个宿主/后端进程死亡，不能证明释放；重启需人工确认目标状态，不重放。
- app_info 输入用可选 `package` 表示明确包或 current_upper；fields/install filtering 尚未实现，返回有界固定版本/安装字段。显式包查询也要求已校准上屏和当前 Accessibility 条件。本版未申请 QUERY_ALL_PACKAGES，包不可见与卸载在 SDK 返回中不能可靠细分。
- 截图 JPEG only，region `[left,top,right,bottom]` 采用当前显示逻辑坐标；frameId 与同一 snapshot 绑定。显示与 screenshot 实际几何不一致时拒绝，不猜测变换。无 MediaProjection 自动回退。
- L0 不自动注入模型；诊断按钮展示 ≤1KiB 的最小信息供未来 Native snapshot port 复用。当前能力摘要是“该能力至少一个工具 ready”，实际有效工具仍逐个计算。搜索在本模块 unavailable。

## Shell / 文件差异

Shizuku API/provider 固定 13.1.5，使用 UserService，而非旧 newProcess。连接同时检查 Shizuku 授权、binder 和 UID，绑定回调再检查实际 UserService UID；服务每次执行检查自身 UID 2000、绑定调用者 UID、随机 token 和 lease。Root 被拒绝，binder 死亡和 host binder 失联执行取消，独立看门狗不依赖 WebView。

任意 Shell 只能走 adb_debug 的独立方法。审批绑定确切命令 UTF-8 hash；命令最长 8192 UTF-8 字节、timeout 默认 15s/最多 60s，输出合计最多 64KiB。cwd、Unix signal 识别、process group 终止和 debug.logs/device_diagnostics 本版未实现。非零退出返回错误和退出码；取消/超时返回错误与 unknown，不伪报整个进程树结束。stdout/stderr 非零结果保留；已撤销 lease 的迟到输出丢弃。

files.list/read 可显式选 `synthetic` 或 `shizuku`；Shizuku 路径固定共享 diagnostic 根，只 read/list，offset 首版仅 0，结果 source 明确反映真正后端。目录必须已存在；访问失败如实返回，不能从 Shell 身份推出其他应用私有目录可读。

结构化事务只针对 **宿主自身明确批准的合成配置目录**，不开放会话数据库、凭据、截图、备份 vault 或其他应用私有目录。`ConfigFilePort` 预留以后经验证的 SAF/受限服务适配。没有把共享目录检查包装成“安全远程事务写入”。

本地事务保存完整原始内容、未知字段与换行，写前验证 JSON object，审批绑定 path/旧 hash/新 hash，备份必须匹配原始 path/hash。保存原权限、同目录临时写＋force＋原子 move、写后读回/hash/格式验证；restore 先保存恢复前版本。AES-GCM 备份持久化在私有区，读取认证并验证内容 hash，满额拒绝。

hash/CAS 和符号链接复查可发现正常并发修改及测试中的替换；纯 Java 的最后 hash→rename 之间仍有外部写入竞态，也没有目录 fd 的完整 hostile-directory 防护和 directory fsync/power-loss 持久性证明。因此 **跨 UID/恶意共享目录写入不开放**，实际模拟器配置写入保持未支持。SAF grant、nonAtomic provider、编码保留策略、模拟器覆写及真实文件权限必须单独集成与验证。

当前 TEST approval 是宿主内存字段，并由手动 UI 对具体操作签发；它不是可被模型自报的 input 参数。完整差异超出 64KiB 审阅预算拒绝，不将被截断差异标成已批准。每目标 10 份、100MiB 备份预算满额拒绝；清理、导出恢复点和批准记录持久化属于后续宿主对接。

## 手柄结论与官方依据

原生 gamepad 工具保留固定契约，availability/error 明确 unsupported；实际按钮和轴后端为空，不制造 gamepad source 的“成功”。

已核对 [Android 控制器接收指南](https://developer.android.com/games/sdk/game-controller/controller-input)、[Android 15 InputManager 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/hardware/input/InputManager.java) 与 [InputShellCommand 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/input/InputShellCommand.java)：接收控制器的公开 API 不赋予跨应用注入；InputManager 注入要求 INJECT_EVENTS 并涉及非 SDK 接口；shell 中 gamepad/joystick source 名存在不能证明设备注册、完整轴或模拟器过滤兼容。审查源码在官方 AOSP GitHub mirror 对应 android15-release 副本读取，不能当成 Thor 固件证据。

没有设备/固件/接收端证据时，不实现隐藏 API 或 uinput/VirtualDevice 猜测路径。后续需要按本模块 thor-validation.md 记录 source、deviceId、按钮 down/up、所有轴、display/焦点、组合输入和故障释放；如无 Root 可行性失败仍保留 unsupported。

其他核对资料：[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[GestureDescription display selection](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder#setDisplayId(int))、[Shizuku 官方 API/UserService/身份指南](https://github.com/RikkaApps/Shizuku-API)、[AGP 8.10](https://developer.android.com/build/releases/agp-8-10-0-release-notes)、[Kotlin/AGP 兼容表](https://developer.android.com/build/kotlin-support)。公开 API 存在、源码实现、编译通过、Thor 验收是四种不同证据。
