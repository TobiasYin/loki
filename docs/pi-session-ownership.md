# Pi 会话与上下文职责

2026-10-17 更新。本文件是当前会话执行、上下文及压缩实现的依据，取代早期文档中由 Thor 每轮编译 DomainTurn、裁剪历史或维护另一份模型消息列表的设计。

## 责任边界

底层执行使用 `@earendil-works/pi-agent-core@1.2.0`、`@earendil-works/pi-ai@1.2.0`，会话生命周期使用 `@earendil-works/pi-coding-agent@1.2.0` 的正式 `AgentSession` 与 `SessionManager`。**Pi 的 session entries 是模型会话的唯一主记录。** 页面消息、Native 审计事件和展示用 DomainTurn 都不是第二份模型上下文。

| 责任 | 当前实现 |
|---|---|
| 用户输入、助手消息、思考块、工具调用和结果的原始结构及顺序 | Pi Agent 与 AgentSession |
| 会话 entry 树、父子关系、当前叶节点、模型与思考强度变更、压缩检查点 | Pi SessionManager |
| 模型投影、工具回合续接、追问、重试、上下文溢出恢复 | Pi AgentSession |
| 自动压缩、摘要生成、保留近期上下文及压缩后续接 | Pi 官方 compaction 流程 |
| 产品说明、当前设备观察、Skill 元数据和实际可用工具的注入 | Thor 的薄适配层 |
| 凭据、HTTP 传输、设备工具、危险操作审批、实时授权与目标有效性校验 | Native |
| 原生 session entries 与来源元数据落盘、按需恢复 | Native SQLite 存储 |
| 消息展示、Markdown、思考与工具展开、话题及历史分页 | 页面展示投影 |

## 官方代码与 WebView 平台适配

本地包 `@thor-agent/pi-session-browser` 是正式 npm 包的浏览器平台适配，并非另一个 Agent 或自行实现的会话管理器。入口在 [runtime/vendor/pi-session-browser/index.js](../runtime/vendor/pi-session-browser/index.js)。它将现有 Pi Agent、Native 网络流、工具、模型、产品提示和官方设置注入真正的 `AgentSession`，并使用官方 `SessionManager.inMemory()`。

`AgentSession`、`SessionManager`、消息投影、compaction、分支摘要、设置、用量与扩展执行器等执行代码来自锁定的 `pi-coding-agent@1.2.0` npm 归档。平台移植保留算法正文：机械替换文件系统、路径、终端、CLI 内置工具等导入，并向 `agent-session.js` 注入局部浏览器字节适配。扩展运行时工厂从上游提取，文件系统动态扩展发现由注入的资源加载器替代。

[UPSTREAM.json](../runtime/vendor/pi-session-browser/UPSTREAM.json) 记录归档 integrity、每个原始模块与浏览器模块的 SHA-256 以及导入替换；[生成脚本](../runtime/scripts/vendor-pi-session.mjs) 可从解包的同版本 npm 归档重现这些模块。来源校验测试验证模块摘要及允许的转换。浏览器工厂、平台接口和 Native 适配由本项目维护，不能把它们称为上游直接发布的独立浏览器产品。

CLI 文件操作、终端与内置 shell 工具不在 WebView 中模拟执行。不支持的 CLI 路径明确失败。设备文件、命令、搜索、下载及 Skill 内容仍通过实际注入的 Native 工具访问；Skill 只注入元数据，完整内容由 Agent 主动读取。

## 正常执行与上下文

启动任务时，Native 按需读取这一会话的 Pi header、entries、leafId 和来源旁表。恢复原始 entry 树后，由 Pi 构建当前分支及压缩感知的模型投影，再通过 `AgentSession.prompt()` 发送本次输入。之后模型与工具循环、消息持久化时机和必要的续接由 Pi 会话层驱动。

`buildProductPrompt()` 只构造产品说明及有界 Skill 元数据。`buildRuntimeObservation()` 只构造已授权的当前 L0 事实，L0 正文最多 1 KiB。设备观察通过 Pi 的自定义消息接口按时间追加；刷新快照不重写最前面的产品提示，也不重建旧消息。有效工具集合交给 Pi 的工具接口同步，工具执行时仍由 Native 重新校验授权与目标。

`prepareRequest` 中的 Thor 逻辑只承担授权刷新、工具发布和诊断记录，不用另一份 `requestMessages` 覆盖 Pi 的最终上下文。`convertToLlm`、上下文投影和自动压缩沿用 Pi 安装的流程。旧 `compileContext()`、自有字符 token 估算器及整轮裁剪代码已删除，也不再作为公共接口导出。

`ContextSnapshot` 继续记录本次请求的模型版本、能力版本、entry ID、有效工具、估计用量及内容摘要。这是审计信息，不参与重新拼接下一次模型请求。

## 自动压缩与输出预算

接入的是完整官方会话压缩流程，包括根据用量判断阈值、生成摘要、选择保留内容、保存原生 `compaction` entry、从检查点恢复，以及上下文溢出后的压缩与续接。它不会以固定轮数截断旧会话，也不会在 Thor 中再运行一套“删最旧回合”的算法。

上下文用量使用官方 `estimateContextTokens` 等流程。最近一次有效助手用量包括缓存 token；之后新增消息使用 Pi 的估计。压缩或上下文编辑后的投影也按官方规则处理，避免继续使用压缩前的旧用量重复触发压缩。官方 `SettingsManager` 的 compaction 设置生效；默认阈值与保留量来自锁定版本，而不是 Thor 另设的压缩策略。

摘要请求使用同一条 Pi `streamFunction` 和 Native 模型网络边界。因此它属于当前任务的请求与用量，使用该任务冻结的模型配置、凭据和取消信号。生成摘要不会通过绕开工具审批的另一条执行路径操作设备。

Provider 的输出上限仍需适配实际请求。自动输出预算受模型上限、Pi 用量估计及 4096 token 安全余量约束，至少保留 16 token 输出；不足时不能以强行设置 16 的方式越过上下文预算。这个传输约束不负责选择、裁剪或摘要历史，历史处理交给 Pi。

真正压缩时会改变模型请求的历史前缀，缓存命中暂时下降属于预期行为。普通追问和新的屏幕观察保持已有消息不变，通过追加内容继续会话。

## 原生存储与内存

SQLite schema v2 以新增表方式保存 Pi 会话：

- `pi_sessions` 保存原始 header 与当前 leafId。
- `pi_session_entries` 增量保存原始 entry JSON、父 ID、写入顺序、所属运行及来源能力。
- 保存整批尚未提交的 entries 与最终 leaf 在同一事务中完成，不能逐条形成不完整的新 Pi 会话。重复投递幂等，已存在 entry 不能被改写；重试也不能缩窄其来源标记。
- 使用原始消息的 usage、思考签名、内容块边界、图片块及未知 metadata，不通过页面文本重建。
- 分支与压缩解释由 Pi 完成，SQLite 不自行展开树、选择保留回合或合成摘要。

按用户明确要求，之前的会话均属于可删除的测试数据，本次不兼容、也不迁移它们。产品 `begin` 只读取原生 Pi 会话，不再读取或返回 `legacyHistory`，不会从 DomainTurn 或展示事件重建模型历史。清理通过显式授权的测试维护入口执行，删除旧会话及其级联运行、消息、事件、附件和 Pi entries，保留 Provider、API Key、Skill 和设置，并留下一个空白新会话。

启动只读取话题元数据、选中项以及需要标记为中断的运行，不读取所有 Pi entries。只有运行中的会话创建 Pi 实例并加载完整会话。任务完成、失败或取消并结束 Native 生命周期后，释放 AgentSession、Agent、Pi 快照、输入与网络桥引用；闲置会话保留在磁盘。下一次追问按相同 sessionId、原始 entry 树、leafId 和压缩状态恢复，不要求闲置 Agent 一直驻留内存。

为覆盖 Native 已保存新用户输入、Pi user entry 尚未提交时进程退出的窗口，原始用户 entry 与对应 Run 的 `piInputCommitted` 标记在同一事务提交。恢复已有 Pi 会话时，仅补读终态、未标记且领域 turn 只有用户输入的 `pendingInputs`，将该原文交给 Pi 保存；不从这些记录读取助手消息、工具事件或副作用，也不自动发起任务。本轮输入和补回的用户 entry 通过显式 `inputRunIds` 映射标记各自原始 Run（保留旧单项 `inputRunId` 兼容），每条来源通过 `entrySources` 映射保存。整批输入确认与全部 entries、leaf 同一事务提交，任意一个确认失败都会回滚整批；不能把旧输入误认为本轮输入已提交。

Native 的 Run、消息和审计事件仍用于展示、状态及设备执行记录。页面可以分页读取这些投影，展开工具或查看历史不会把投影反向变成模型上下文。应用重启时未完成运行标记为中断；不会自动恢复旧工具副作用。

## 权限撤销、恢复与停止

设备授权、当前屏幕目标及命令策略仍是 Native 的执行边界，Pi 管理上下文不会赋予工具更多权限。每个新增 entry 另存来源能力；摘要继承生成它所依赖的历史来源，不能因为变成摘要而绕过撤销。

恢复时的来源适配只处理授权约束，不负责 token 选择或压缩：通过官方 `context_edit` 省略已撤销的派生内容，并保持工具调用与结果合法配对；不能安全编辑的旧摘要改由 Pi 分支接口建立授权后的活动分支。原始 entry 与旧分支继续留在磁盘，不直接篡改其中的思考、签名或正文。用户文字保留；受撤销来源约束的历史截图，包括附在用户消息里的截图，通过 Pi 上下文编辑变为不可用说明，不能依靠“用户消息”角色泄露旧画面。独立手动图片按自身来源处理。

点击停止或授权失效会取消当前 Native 网络及工具，同时调用 `AgentSession.abort()`，覆盖正常生成、重试等待和正在生成的摘要。被取消或截断的摘要不能成为有效压缩检查点，旧历史保留。取消发生后，已经结束的计时器或 SDK 回调不能再启动真实后续请求。

失败与取消后的原始 Pi 记录保留，包括已确认的工具结果和终态助手消息。追问恢复上下文不等于重放工具；副作用仍须依据当前观察和 Native 记录判断。

## 页面执行时间线

思考片段、工具调用、工具结果、后续思考和正文按原始事件顺序混排。工具从请求到结果更新同一行，完成后留在原位置；不会将整轮思考合并到一个区域、再将全部工具放到另一个区域。当前执行项显示扫光，已完成的思考和工具可原位动画展开收起，正文继续使用 Markdown。

Native 与网页共享稳定的片段序号及分段标记，分页、二次投影及持续流式更新不改变历史位置。Pi 自动压缩开始与结束也使用这一时间线，显示自然语言进展，不展示原始 JSON 或错误码。

## 已知边界与验证状态

单条 Pi entry 的原生图片块直接保存，不另做会话图片结构转译。`persist / pi_session` 可使用现有桥的 16 MiB 请求限制，其余 Host RPC 保持 3 MiB。超过 16 MiB 的待提交整批安全失败，保留原始存储；不会为了通过限额拆成可能半途提交的存储批次。SQLite 对大 entry 使用 64 KiB 分块读取。

**目前 `begin` 的 Native → JS 恢复回复仍作为完整 `host.result` 字符串发送，没有会话恢复分片协议。** 大量图片历史可能形成较大的单次回复及短时内存分配；SQLite 分块读取不等于桥回复分片。这是尚未消除的边界，不能以按需加载已经实现推断任意大小会话都没有恢复峰值。

本轮验证通过 Runtime 350 项、网页 89 项、Android 单测 214 项，构建与 lint 通过。模拟器与 Thor 实机存储验收各 31 项覆盖原生 Pi entries、数据库升级、大图片、事务、pending 输入恢复及闲置会话按需加载。浏览器验收直接运行官方自动压缩流程，保存摘要、从 7610 token 历史压缩到估计 1141 token，保留图片，未出现页面错误或外部导入。产品浏览器另完成 6 项场景，其中 15695 token 的原生历史触发官方自动压缩、保存检查点后成功续答；发送预算使用官方投影估算，防止沿用压缩前 usage 阻塞续答。

Thor 实机已安装本轮 APK，真实 OpenCode Go 模型完成三轮连续对话、工具调用、磁盘恢复及停止等待验收；已有历史与 Provider 配置保持一致。热请求的缓存命中率为 95.38%–97.96%；这是本次合成对话的实测值，不代表任意任务的缓存命中保证。模型与停止结果见 [physical-cache.json](../tests/pi-session-evidence/physical-cache.json) 和 [physical-sleep.json](../tests/pi-session-evidence/physical-sleep.json)。官方自动压缩与溢出恢复由浏览器和 Runtime 测试覆盖，本轮没有在真机上构造临界长度的真实模型压缩测试。

## 显式清理旧测试会话

`CleanupTestConversationsTest` 默认跳过，只有明确传入 instrumentation 参数 `deleteAllTestConversations=true` 才执行。清理先确认没有活动任务，再调用真实 ProductHost 的新建和删除会话 UI 命令；删除后验证数据库级联完整、历史和 Pi 表为空、仅剩一个空白会话，并核对所有共享偏好及 Skill 文件未变。不会清空应用数据或重置 Provider/API Key。已删除会话的图片正文随数据库记录移除，剩余派生截图缓存也在无活动任务时清理。验收报告只包含数量与配置保持标记，不包含旧对话、密钥或截图。

本节描述显式维护入口，不代表已在任何设备执行；实际清理结果以对应设备的测试输出与 `cleanup-test-conversations.json` 为准。

用户授权的旧测试数据清理已执行：Thor 删除 197 个原会话，模拟器删除 51 个；关联消息、运行、事件、附件和 Pi entries 已清空，派生截图缓存已清理。两边均只保留一个空白会话。Provider、API Key、Skill 和设置文件摘要前后相同。清理后的真实模型测试再通过，测试创建的临时会话已删除，最终空白状态再次从数据库核对。数量与保留检查见 [acceptance.json](../tests/pi-session-evidence/acceptance.json) 及同目录 cleanup 证据。
