# 第 3 轮键盘组件验证

**结果：通过；耗时 149.781 秒。**

文字和键盘两个阶段全部通过。ENTER 后正式观察、激活 normal 字段并核对 Agent 可读 focused；CTRL+A 独立选区与 ui_tree textSelection 一致，BACKSPACE 实际删除选区。

仅使用本目录 pins.json 对应固定 APK，在隔离 API 36 ARM64 AVD（ADB 5042 / 127.0.0.1:5557）进行正式 Native RPC 组件验证。普通 Activity 接收端独立记录 Android 回调；按键使用真实 Shizuku shell UID 后端，没有用测试注入、ADB input 或 provider 代发键盘操作。未调用模型或云服务，未操作 Thor/Citra。

component-summary.json 保存阶段结果和工具结果；receiver-summary.json 与封口后的 receiver-events.jsonl 保存独立接收端证据。失败轮次不转记为通过。原始私有数据库、凭据、原始会话和完整诊断仅留在忽略的 build 目录。

preservation-summary.json 比较原始 120 个 Run 及会话五表的全部原 payload、选中会话、四份原始偏好字节、授权、无障碍状态和 Shizuku 原停状态。只恢复这些指定设置，不声称整个 files 树不变，也不回写覆盖新版内置 Skill。

按键原始回调共 6 条：ENTER 66 DOWN/UP、CTRL 113 DOWN、A 29 DOWN/UP、CTRL 113 UP。来源均为 257（键盘）、设备为 -1，CTRL 最后 UP 的 metaState 为 0。BACKSPACE 的实际删除由独立 TextWatcher 确认，但 Activity 未观察到 67 的原始 DOWN/UP；IME/InputConnection 转换仅为可能原因，未单独证明，不能声称每个注入键的原始事件均被 Activity 收到。

同一最终产物另执行一次动态内容回归并通过，实际滚动 1376 像素；动态画面点击继续可用，真实控件移动、弹窗及滚动后的旧帧仍会被拒绝。未执行更多模拟器用例。

Skills 的比较基线是上次动态组件最终私有快照，并非本轮开始时另取的即时副本。该基线仅有 3 个内置 Skill，没有用户自建项；skill-creator/skill-manager 的 catalog 项和文件字节均一致，adb-operations 升级为含新键盘说明的版本，符合预期。
