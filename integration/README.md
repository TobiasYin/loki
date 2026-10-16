# 正式跨模块适配

- `device-authority/`：工具授权端口委托 Native `Authority`，共用 Session/Run/lease/epoch；发布集合、去重、短提交、撤销和断连契约测试。
- `device-android/`：把原诊断工程的真实平台实现编译为 Android library，排除诊断 Activity；正式 App 与独立诊断 APK复用相同执行器。
- `runtime-host.ts`：锁定 Pi 的正式端口实现。Fetch placeholder 验证后移除，Native 安装通用模板；Request、abort、Response 字节流保持 SDK 行为；模型协议仅在 JS。
- 数据与 UI 的唯一持久化入口为 `app/ProductHost` + `SqliteStore(product.db)`。原 app Coordinator 和 tools ManualTestAuthority 仅是旧测试/独立诊断路径。

JS 内部 Runtime v0.1 信封在适配边界转换成 Native wire v1；不直接透传自报 lease、授权或 deadline。Native bootstrap 签发单调截止时间；工具墙钟剩余时限被转换/夹紧至 Native elapsedRealtime 租约。观测审计另外使用 UTC/墙钟，Lab InputEvent 使用 uptime。

设备复杂 schema 由同一 bundled contract 在 Runtime 和 Native `InputSchemas` 双重校验。产品经过 `tools.Gateway` + `DeviceAuthority`，不绕过 scalar-only 的旧 bridge ToolGateway；该旧网关保留供 bridge 诊断宿主测试。

手柄按钮与六轴采用固定 evdev 后端，核对真实 Odin Controller 节点、固件映射、初始未按下/中立状态和上屏物理焦点；不把 InputManager 注入返回的身份当作接收端事实。Shizuku 手柄调用分离 prepare/start/poll，短 start 在 Native 原子授权内提交，元数据查询与持有等待在锁外；服务检查 shell UID、caller、token、deadline 和 owner binder。终止直接子进程不证明所有后代已结束。真实 Thor 路由、清理边界和验收见 [0.6手柄说明](../docs/release-0.6-gamepad.md)。
