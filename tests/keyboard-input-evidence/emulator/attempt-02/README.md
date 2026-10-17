# 第 2 轮键盘组件验证

**结果：失败，保留原始失败；耗时 163.053 秒。**

文字阶段全部通过；ENTER 和 CTRL+A 原始键事件到达，但 CTRL+A 未选中预期 normal 字段，整体失败。后续第三轮独立焦点记录确认 ENTER 会将焦点移到 password 字段，随后改用正式观察和激活流程重新聚焦。

仅使用本目录 pins.json 对应固定 APK，在隔离 API 36 ARM64 AVD（ADB 5042 / 127.0.0.1:5557）进行正式 Native RPC 组件验证。普通 Activity 接收端独立记录 Android 回调；按键使用真实 Shizuku shell UID 后端，没有用测试注入、ADB input 或 provider 代发键盘操作。未调用模型或云服务，未操作 Thor/Citra。

component-summary.json 保存阶段结果和工具结果；receiver-summary.json 与封口后的 receiver-events.jsonl 保存独立接收端证据。失败轮次不转记为通过。原始私有数据库、凭据、原始会话和完整诊断仅留在忽略的 build 目录。

preservation-summary.json 比较原始 120 个 Run 及会话五表的全部原 payload、选中会话、四份原始偏好字节、授权、无障碍状态和 Shizuku 原停状态。只恢复这些指定设置，不声称整个 files 树不变，也不回写覆盖新版内置 Skill。
