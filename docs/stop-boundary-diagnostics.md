# 显式停止与运行诊断

用户停止任务时，运行层先取消当前执行和平台权限，再等待 Pi 本轮实际结束，保存已有上下文并写入取消终态。provider 事件迭代或最终结果等待不响应取消时，新边界会向 Pi 提供明确的取消事件，并保留已经收到的部分文字。请求体序列化也能响应显式停止。没有增加模型总运行时限，没有提前把仍活跃的 Pi 标成结束，也没有重新执行已经完成或提交状态未知的工具。

本轮新增 6 项测试，完整 Runtime **115/115 通过**，集成 TypeScript 检查通过。测试包括：

- 真实 Pi 的 provider `next()` 或 `result()` 永不结束时，显式取消仍能让 Pi 恢复 idle；部分文字和原用户消息保留，停止后晚到事件不能修改最终消息，同一 Agent 可以继续追问。
- Responses、Chat Completions、Anthropic 三协议的真实 SDK 先执行一次工具，再收到部分文字；底层 SSE 读取和取消均不合作时仍可停止。工具结果和部分文字保留，终态后没有新增事件；同一 Pi Agent 追问时不会重复工具。
- 请求体序列化永不返回时可以停止，Native 网络端口尚未启动；序列化晚到不能恢复已结束任务，同一会话仍可追问。

这些是 Node、真实 Pi／SDK、受控 SSE 与合成平台端口的验证。**它们没有复现或证明 game11 真机停止卡住的具体原因，也不代表 Android WebView、Native Bridge、前台服务或新安装包的设备验收通过。** 普通停滞 SSE 的基线原本就能正常停止；修复的是确定存在的取消边界缺口，下一轮需要利用诊断判断实际等待点。存储写入仍须完成或报告真实失败，不能通过丢弃写入来伪造终态。

调试构建提供两个固定入口：

```javascript
window.__thorRuntimeDiagnostics()       // 同步 JS 安全快照
window.__thorRuntimeNativeDiagnostics() // Promise；Native RPC 最长等待 5 秒
```

JS 快照包含连接状态、准备／执行／待发起数量、最多 64 条待处理 RPC 的固定操作类别与等待时长，以及最多 8 个运行的状态、停止请求标记、请求／事件／工具／效果计数、Pi 是否正在流式执行、待处理工具数和最多 32 个等待阶段。阶段仅使用固定名称：`agent_prompt`、`provider_next`、`provider_result`、`request_body`、`authority`、`persist`、`network_headers`、`network_read`。快照没有对话、思考、工具参数、地址、请求头、凭据或授权令牌。Run／Session ID 仅用于关联当前进程的诊断。

Native 入口要求调试构建、当前连接及空参数，只观察串行队列等待、绑定数量、关闭／存储失败状态、最多 8 个绑定的停止标记和各自最多 64 个 HTTP 状态／响应字节数／固定终止原因回执。没有新增模型工具或设备控制权限。物理长程测试观察器约每 10 秒读取同步快照，异步发起 Native 诊断并缓存结果；同步观察不等待 Native Promise。Native 快照可能是上一次完成的结果，需要结合自身等待信息判断新鲜程度。诊断读取失败不能直接证明 SDK 或 Native 队列死锁。

固定日志、源文件哈希和边界说明见 [停止边界源码测试证据](../tests/release-0.8-evidence/stop-boundary/README.md)。Native 宿主和物理观察器哈希用于固定接口配套时点，未将 Node 测试冒充为它们的 Android 验收。设备复测和最终 APK 证据后续单独追加。
