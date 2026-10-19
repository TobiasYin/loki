# 无障碍自恢复修复验证（2026-10-19）

- 核心单元测试：291 项通过。
- App 单元测试：253 项通过。
- Web UI 回归：135 项通过。
- AYN Thor 真机：`AccessibilityRecoveryDeviceTest` 通过。主动调用本应用服务的 `disableSelf()` 后，已授权的 Shizuku 监控自动重新开启服务；必须同时取得 OFF_ACKNOWLEDGED 和实际连接回调才能通过。连接断开时 apps.launch 为 TEMPORARILY_UNAVAILABLE，不是 TARGET_CHANGED；其他已启用无障碍服务保留。诊断结果见 `physical/monitor-recovery.json`。
- 非预期进程退出验证：`physical/recovery.json`。退出后观察到本应用进入 Crashed services，重新打开后恢复实际绑定并记录新的 connected 事件。该场景中 Android 自身也可重建绑定，不单独作为监控执行设置修复的证据。
- 真机原始 Crashed 现场另已验证：只移除本应用 enabled_accessibility_services 条目会清空其 Crashed 记录，重新加入会恢复绑定。因此代码仅对 outstanding Binding 保留重启处理，Crashed 不再跳过设置循环。

服务维护记录独立保存在运行的 serviceRecoveryEvents 中，不占用 Runtime 事件 sequence；本地最多保留 64 条维护诊断。单元测试验证了去重、容量、字段过滤和 Runtime sequence 不变；UI 测试覆盖恢复、超时和连接成功的可读展示。

测试没有发送模型请求或重跑用户的 Google Play 下载任务。原有会话和配置保留。
