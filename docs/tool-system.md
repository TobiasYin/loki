# Tool Registry、ToolPack 与动态能力

> 2026-10-13 状态更新：五模块已开发并进入正式软件整合。本文早期“仅规划/未授权/无 APK”等叙述保留其历史背景；当前实现、实际测试与尚未完成的 Thor 验收，以 [中文集成报告](integration-report.md) 为准。来源模块旧测试不等于本次集成结果。
## 核心原则

[需求] 模型只能看到当前 Session 允许并且设备可执行的工具。Prompt 可以解释可用能力，但不是权限机制。Native 执行网关是授权最终判定者，Runtime Registry 不是独立权限事实源。

## 注册契约（Thor 自有设计）

| 字段 | 意义 |
|---|---|
| toolId / schemaVersion | 稳定命名空间与契约版本；模型名用固定映射，避免别名冲突 |
| packId / packVersion | 所属内置 ToolPack；依赖版本和兼容性 |
| description / inputSchema / output policy | 模型描述、参数校验、输出长度/图像预算；结果不等同全部审计详情 |
| requiredCapabilities | 所需 Session 能力集合；组合工具取权限交集，禁止隐式提升 |
| platformPrerequisites | 无障碍、Shizuku、文件授权、搜索配置等系统条件 |
| executor / resources | JS 或 Native 执行器，指定 display/path/network profile 等作用范围 |
| effectClass | read / network / input / write / arbitrary-shell |
| timeout / cancellable / concurrencyKey | 时限、停止边界、同资源互斥 |
| provenance / modelVisibility | 来源、敏感度、是否允许投影给模型 |

ToolPack 只封装已审计工具声明和执行器绑定。首版使用构建时注册的内置集合，不做插件扫描、动态下载、任意脚本执行。注册时校验重名、未知 capability、schema 不兼容、缺失执行器；失败关闭该工具并记录原因。

## 能力矩阵

2026-10-17：增加内置基础工具 `agent_sleep({durationMs: 2000})`（toolId `agent.sleep`）。参数为整数毫秒，范围 1–60000；不需要屏幕、搜索或调试能力，不需要用户批准。Native 使用独立异步定时器，返回请求时长及实际经过时间；等待只暂停当前任务，不休眠设备、不占住界面或设备执行线程。停止任务、取消工具、撤销运行及关闭宿主会清理对应定时器；其他任务的等待不受影响。工具仍经过运行绑定、发布清单、参数及一次性执行校验，配置探测期间不发布。

等待不构成屏幕观察，也不会刷新旧的坐标或组件引用。System Prompt 要求在等待动画或加载后重新观察，再决定是否操作，避免通过密集截图、空模型轮次或 shell sleep 轮询。聊天工具详情显示“等待”和毫秒时长。

| 用户能力 | ToolPack / 典型工具 | 前置条件 |
|---|---|---|
| screen_perception | perception：app_info、window_info、ui_tree、capture_screen、device_info | 工具各自的 Accessibility/包可见性条件 |
| web_search | web：search、fetch_page | 可用后端、网络配置、限额 |
| screen_control | input：tap、swipe、press_button、set_axes、release_inputs | 触摸或受限 Shizuku 输入后端就绪 |
| adb_debug | debug/files：shell、list/read/write、backup、restore、logs | Shizuku/设备路径或 SAF 权限 |

“开启操作但感知关闭”允许执行用户明确指定的动作，但无截图、UI 树和自动应用上下文。Native 可使用不返回给模型的最小 display/目标校验信息保护动作；不能借动作工具返回屏幕内容。调试诊断工具如 logs 归 adb_debug，常规 device_info 只返回普通 SDK 可得信息。

## 意愿、可用、注入三个状态

Session 保存 enabled 布尔值；Availability 记录 ready / permission_missing / backend_missing / unsupported / temporarily_unavailable 及原因；EffectiveTools 取 enabled、平台条件和资源授权的交集。

默认感知 ON/搜索 ON/操作 OFF/调试 OFF。未配置搜索不能伪造一个“可搜索”工具；UI 保持勾选，附“未配置搜索服务”，模型获得精简不可用摘要。工具集合没有每轮随机重命名，方便审计和缓存。

## 注入与权限变化

1. 用户改开关，Native 以事务更新 enabled 和单调递增 capabilityEpoch，立即封禁旧 epoch 的待执行请求。
2. 通知 Runtime 更新工具集合；下一次模型请求只声明最新工具。必要时取消进行中的模型请求，避免重复产出失效调用。
3. 已产出的调用仍逐一走 Native 网关；未知名、旧 epoch、错误 Session/Run、未注入名一律拒绝。
4. 关闭→重新开启仍使用新 epoch。旧调用不重试、不复活；模型必须基于新工具集合重新规划。
5. 若关闭感知/搜索/调试，后续模型投影默认不再带这些来源的旧原始结果及其摘要；本地历史可看。已发送给远端的信息不能撤回。若当前请求已含被撤销来源，则取消请求，不能宣称远端已经忘记。

用户主动粘贴的消息不被当作工具来源自动删除。Assistant 历史中引用被撤销来源的消息需作来源标记；无法安全细分时从后续模型投影整体排除相关 Run 的派生摘要/回答，并保留说明，避免把旧设备数据重新送出。

## 执行信封与最终检查

Native 签发 Run lease；执行信封包含 sessionId、runId、toolCallId、toolId、schemaVersion、capabilityEpoch、requestId、deadline、参数和目标 snapshotId。来自模型/JS 的值只用于匹配，不作为授权凭证。授权绑定由 Native 内存和持久化状态确认，不能接受 JS 自报 enabled=true。

校验顺序：可信桥连接 → 活跃 Run lease → 当前 epoch → 允许的工具集合 → 参数 schema → 资源范围与快照新鲜度 → 系统权限/后端 → 获取资源锁 → 在副作用前复核。

原生工具不可通过另一个通用 bridge method 绕过这一路径。JS 搜索工具的网络请求需同样绑定 purpose=web_search 和对应 lease；模型网络有独立 purpose=model，不受搜索开关控制，但其目标/方法/路径固定。

## 撤销、停止与竞态

| 时点 | 必须发生 |
|---|---|
| 已注入但模型尚未调用 | 下一次请求移除；必要时取消当前模型请求 |
| 已排队未开始 | 旧 epoch 拒绝，不允许仅靠入队时检查 |
| 截图/UI 读取中 | 停止进一步读取，结果交付前再次校验；不向模型交付新结果 |
| HTTP 流中 | 按 purpose 取消连接并拒绝迟到 chunk；已上行的数据无法收回 |
| 触摸/按键序列中 | 阻止新动作，取消可取消段，执行本 Run 已持有输入的释放 |
| 配置提交中 | 原子提交前拒绝；已提交则如实记录，不自动假装回滚成功 |
| Shell 中 | 停止继续启动进程、尝试结束进程组，报告可能逃逸/已执行副作用 |

释放输入是安全清理，不允许借此发送新的业务动作；它仅可释放本 Run 已登记的按键/轴。输入后端需有独立于 WebView 的短租约看门狗；Shizuku UserService 自行计时，在宿主失联时释放。若后端失效导致无法确认释放，标记 outcome=unknown，并禁止继续控制；不承诺所有进程被杀情况下都能发出 key-up。OS 已接收的单次手势没有承诺可即时撤回，取消语义不得夸大。

## 并发与错误

首版每设备一个 Run；设备输入全串行，配置写同路径互斥，Shell 默认串行。只读工具未来可有界并行，不能使用 Pi 默认并行代替资源调度。

统一错误：CAPABILITY_DISABLED、STALE_AUTHORIZATION、PERMISSION_MISSING、BACKEND_UNAVAILABLE、TARGET_CHANGED、UNSUPPORTED、TIMEOUT、CANCELLED、LIMIT_EXCEEDED、IO_ERROR。所有结果有 observedAt、source、truncated 和可选 evidenceRef；副作用结果区分 submitted、observed、unknown。错误映射遵循锁定 Pi 版本的工具错误通道，不把失败当成功文本。

## MCP 与后续扩展

MCP adapter 将外部 tools/list、tools/call 转成同一描述与执行契约；按服务器信任配置映射 capability，未知远程工具不默认归“搜索”。远程工具描述和返回内容视为不可信数据，不能修改本地权限。参照 [MCP 工具规范](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)，本次只预留接口，不承诺实现版本、传输、认证或动态热加载。
