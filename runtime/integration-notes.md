# Runtime 局部契约与未来接入

本模块遵循仓库的四能力、L0、lease/epoch、工具及 Session/Run/Event/Context 分层语义。这里定义的 TypeScript 端口是独立任务的局部接口，不依赖其他任务产物。没有完成 Native/UI/ToolPack 跨模块集成。

## 调用方职责

| 端口 | 必须由受信调用方实现的保证 |
|---|---|
| AuthorityPort.read | 返回 Native Capability Store 的最新镜像；lease 必须是真正登记、活跃且属于该 Session/Run；不能接受 JS 自报能力。应用中每设备只创建一个 ThorRuntime。授权变化立即调用 authorizationChanged；端口返回的快照不是新授权。 |
| ToolPort.execute | 校验桥身份、Native Run 绑定、epoch、tool/schema、参数、资源/权限、目标快照；排队/锁后和副作用前原子复核。按 deadline/AbortSignal 停止新动作，清理本 Run 持有资源；如实报告 submitted/observed/unknown。不能用 runtime 早期检查代替这些步骤。 |
| FetchPort.fetch | 提供 SDK 需要的标准 Request/Response、Headers、AbortSignal 和字节 ReadableStream 行为。核对已登记 profile/purpose/lease，通用凭据模板替换，HTTPS/显式本地端点策略、逐跳重定向禁止、chunk/队列/背压、连接及空闲超时。禁止 WebView 直连旁路。Native 不解析任何模型 SSE/JSON。 |
| AttachmentPort | 管理本地附件引用，限制存储/解码大小，返回 MIME/base64、observedAt/expiresAt 和来源标签。用户自选图片 source=[]；工具图片必须覆盖对应工具 requiredCapabilities。不得返回任意路径给模型冒充图片。 |
| PersistencePort | appendEvent 按 Run sequence 写入，commitRun 在一个事务内提交状态及其事件；saveTurn 按 sessionId+turnId upsert，saveContext 只存 manifest，saveRun 供恢复。原始历史不因投影压缩删除。不要把失败的持久化应答当成功；不完整 intent 的副作用保持 unknown。 |

Runtime 初始保存 complete=false 的用户回合，成功时用相同 turnId 补全领域记录；失败/取消保留用户输入及动作 ledger。文本增量与工具轨迹由领域事件恢复。高频 token 首版逐事件提交，尚未做 250ms/4KiB 合并；宿主可在保证 intent/状态即时提交的前提下实现事务批量。

网络信封固定 bridgeVersion=0.1、purpose=model、credentialRef/profileRef/profileRevision、Session/Run/lease、epoch、requestId 与 deadline。此处没有 web_search/web_fetch 实现；真实工具网络仍须走 Native 相应 purpose/授权入口。Native 可使用通用 endpoint allowlist 与 header 模板，无需按 OpenAI/Anthropic 判断协议。

工具信封使用稳定 toolId/schemaVersion、opaque toolCallId 和 targetSnapshotId。本次 targetSnapshotId 取 AuthoritySnapshot.l0.snapshotId；实际输入工具可能需要 displaySnapshotId/target app/transform revision 等更多资源字段，必须在正式 Native adapter 中扩展并验证，不能把此局部最小字段当作已完成输入防护。OpenAI 的 Pi toolCallId 可能为 `call_id|item_id`，Native 应按不透明关联 ID 保存，不要只保留前半段而破坏去重。

## 上下文与压缩差异

持久化 DomainMessage 不是 Pi transcript；不保存 reasoning 或图片字节。模型 reasoning 固定 off，持久化的 assistant text/tool calls 在后续请求被转成目标协议消息。模型配置在 Run 开始固定；下一轮请求仍读取最新 L0/availability，模型修订变更影响新 Run。

每个请求只有一个新系统段，L0 不含截图、版本、UI 树或日志；感知 OFF 时移除设备/应用信息。历史系统消息从不回放。整个 Run 的工具/L0 派生来源被保守传播到历史 assistant 和结果；撤销来源时去掉完整派生回合，但保留用户输入。

首版采用文档允许的确定性完整回合裁剪 fallback，不发起额外摘要模型请求。maxHistoryTurns 默认 50；保留当前用户目标、当前已配对工具回合、调用方显式标记 constraints、来源允许的 effects ledger。ledger 保留 toolCallId/toolId/effectClass、副作用 outcome、evidenceRef；Native 应提供备份等证据引用。所需内容本身超预算则报 LIMIT_EXCEEDED，不悄悄丢弃约束/动作记录。当前 Run 内的图片每次重读引用；未来准确 tokenizer/provider 图像估价、语义摘要与大规模附件索引另行接入。

Runtime 不自动从自然语言抽取跨轮长期约束；需要保留的约束应由 Session/领域层提供 HistoryTurn.constraints 或在当前目标中明确。history 必须是该 Session 的完整领域回合，由存储查询过滤归属；模型投影不是可任意传入的数据库内容。未完成回合只投影用户输入，禁止孤立 toolResult。

## 已知限制与待验

- Runtime 早拒绝并不能证明 Native/OS 已阻止副作用。端口忽略 signal 时 JS 会在超时后结束等待并丢弃迟到结果，实际动作必须由 Native watchdog 负责清理。
- await_user 状态留作未来危险操作确认/信息补充；首版没有暂停后恢复执行入口。新工具默认不能靠“用户已聊天授权”直接越过 Native 要求的确认。
- 明文 key 完全不由本模块接收。SDK 占位 header 与本地 fake 验证通过；Native 的替换/脱敏/重定向及来源安全尚未联调。HTTP 错误体不上持久化事件，只返回有界错误类别。
- 每请求 body 16MiB、累计 response 32MiB、Run 总时限 180秒、最多12轮请求；Native chunk/256KiB 信用窗口、15秒连接/60秒空闲、压缩解码及 stream credit 不属于这个 FetchPort 的实现，仍需桥契约测试。
- 真实 OpenAI/Anthropic 服务、第三方兼容端点、HTTP 本地模型服务、Android WebView、renderer/宿主退出、Origin/MainFrame 安全和 Thor 设备功能全部未验证。
- 模型 capabilities/lastProbe 是调用方提交的每修订探测证据；模块校验四项与修订一致，不执行配置测试、不从内置 model catalog 猜支持。fixtures 的 probe 只对应本地合成端点。
- Pi 类型要求 cost 数值，内部填零仅为结构占位，不输出价格估计，也不宣称模型免费。
