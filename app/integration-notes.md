# 独立模块假设与后续对接

本模块依据 `docs/architecture.md`、`ui-ux.md`、`settings.md`、`storage.md`、`session-context.md`、`security.md` 和需求 02/14/15/16/17/20 的语义实现第一版。公共文档中之前的“仅文档”状态属于历史，本次没有修改公共文档或其他模块。

## 已实现边界

- `NativeStorePort` 是存储端口；`Coordinator` 为 Session 能力、epoch、设备单 Run、归属与终态提供事实源。桥只接受固定白名单 UI 操作；没有从 JS 直接执行工具/读写凭据的入口。
- `RuntimePort.plan` 目前返回确定性模拟事件列表；实际 Pi 适配应替换计划调度机制为增量事件流。事件仍绑定 Native 签发的 Run、Session、epoch。不要把当前 JSON DOM 投影视为 Pi AgentMessage/schema。
- `DevicePort` 只执行固定本地模拟结果。即使 capability 显示 ON，也没有真实工具可用性；页面明确分别显示意愿、模拟状态与真实服务/授权缺失。
- Native `SqliteStore` 为单一事实源；消息、运行、事件、附件引用独立。附件路径与密钥不出桥；无 LocalStorage/IndexedDB。UI 展开状态只修改 Run 的视图字段，不影响消息或事件。
- 普通模拟读工具不要求批准；模拟写入场景批准固定的 `volume 60 → 70` 对象。批准时再校验当前 epoch；取消/中断不把 pending intent 误报成功。
- 撤销任何能力会保守地结束整个 Run，满足旧授权不可复活。运行中开启能力也增加 epoch；后续旧 epoch 工具会失败。未来可实现仅取消相关执行、重建投影，但不能继续发送已撤销来源或沿用旧工具集。

## 连接资料和秘密

Native 表单保存一份模型与一份搜索资料，包括协议、端点、模型 ID、revision；每次保存使 probe 保持 `untested`。模拟 Run 的 `modelProfileId=demo`，同时记录启动时资料 revision，仅供审计，修改设置不改变已有模拟计划。UI 提示真实模型未配置/未测试且不可使用。

Keystore 保存 AES 密钥，私有偏好保存加密 API 文本；空输入保持已有 Key，删除有显式选项。秘密只在 Native 编辑框与未来 Native 传输中解密，没有 JS 明文 read/write。无法解密时标记重新配置，保留会话。模型/搜索凭据用途不同，AAD 防止密文交换。

未来真实适配必须增加不可变版本化 credentialRef、profile 选择与删除生命周期、预算/可选 header、分项文本/图像/工具/回传探测、URL/网络目的地与逐跳重定向规则、通用 Fetch 流、系统授权、端点可用性事实。当前没有 INTERNET 权限和网络执行，不能声称凭据转发、端点兼容、SSRF 或重定向策略已经通过。多个配置实体、ContextSnapshot、Summary/上下文压缩、模型来源过滤、导出、配置备份、真实图片查看与数据保留设置尚未实现，不能用模拟轨迹证明这些能力。

## 生命周期、性能与设备

宿主串行 executor 跨 Activity 旋转保留，应用离开前台/renderer 退出会标记 interrupted；进程新启动把所有非终态 Run 标记 interrupted，不恢复运行计划。所有时间预算用 monotonic time，审计保留 wall time。Run 10 分钟、模拟工具 30 次、用户输入 8000 字符、Assistant 65536 字符、单附件 64 KiB；真实模型轮次与设备动作预算由未来适配另行校验。

当前全量会话视图由 Native 快照驱动；SQLite 只写改变的行。200 条消息/50 会话有功能测试，但不是性能/功耗基线，没有虚拟列表或 token 聚合批处理。模拟数据不包含真实上屏感知来源；不能声称已实现关闭感知后的真实模型投影清理。

未取得 Thor：没有按 displayId 选择下屏，没有上屏感知、无障碍、Shizuku、触摸、手柄、焦点路由或真机键盘证据。默认启动当前 Android 显示屏，仅为普通单屏模拟器验证入口。Native 容器处理系统栏和 IME insets；实际 Thor 下屏布局、旋转、字体放大及上屏不中断需 POC-01/07/10。无 Root、StrongBox、固件兼容性均未验证。

## 官方资料核对（2026-10-13）

- [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)：APK 资源以本地 HTTPS 来源承载；本实现只映射 `/assets/chat/`。
- [WebViewCompat.addWebMessageListener](https://developer.android.com/reference/androidx/webkit/WebViewCompat)：精确 allowedOriginRules；额外校验 main frame、来源、页面身份和消息大小。功能缺失时显示更新 WebView 提示，不回退到不受限 addJavascriptInterface。
- [WebKit 版本](https://developer.android.com/jetpack/androidx/releases/webkit)：固定 1.12.1，不依据最新动态版本构建。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：Native AES-GCM 加密；未把硬件级别当事实。
- [AGP 8.10](https://developer.android.com/build/releases/agp-8-10-0-release-notes) 与 [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test)：实际锁定并完成构建的版本记录在本目录工程和锁文件。
