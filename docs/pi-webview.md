# Pi Agent Core 与 pi-ai 在 Android WebView 的接入

## 已核对的官方基线

访问日期 2026-10-13，源码提交 `7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6`，来源见 [P1–P6](references.md)。本节只记录少量与设计相关的事实，不提供可直接复制的初始化代码。

| 已证实事实 | 设计意义 | 尚不能推出 |
|---|---|---|
| pi-ai README 声明浏览器支持，并区分浏览器不支持的 Bedrock、Node-only OAuth | 仅选择两个目标 API，排除 CLI/OAuth/Bedrock | 所有 Thor WebView 均能打包运行 |
| Agent 接口包含 prepareRequest、transformContext、convertToLlm、beforeToolCall、afterToolCall | 可在每次请求前重建投影并接入工具事件 | 钩子自动解决持久化或权限撤销 |
| AgentOptions 类型要求 streamFn，支持 tools 执行集合 | 显式桥接 models.streamSimple；不要混用旧版默认 provider 示例 | 老包名/老示例能原样使用 |
| 当前 tools 声明与 transcript 系统消息有同步行为 | 每次投影必须检查有效工具和系统段回放 | 仅覆盖 JS 数组即可证明所有历史定义被清除 |
| ProviderRequestOptions 存在 fetch；两个目标 API 源码把它传到 SDK 客户端 | 原生通用 HTTP 转发具有明确接入点 | WebSocket 也会经过 Fetch；凭据占位必然可用 |

本次读取的新源码与原对话的旧包名不同。文档使用“Pi Agent Core / pi-ai”逻辑名称；正式选择依赖时禁止混装新旧 scope。当前提交的 package 版本只是考察对象，没有冻结为产品依赖。

## 请求准备职责

Native 保存领域实体；Runtime 生成规范模型投影。每个 provider request 前，用 prepareRequest 作为集成点取得最新 L0、工具集合、历史消息和能力版本。transformContext 用于预算裁剪，convertToLlm 把 UI/领域消息变成 Pi 接受的消息。具体钩子间执行次序和系统消息合并效果必须由锁定版本的契约测试覆盖，不重复在多个钩子里追加环境块。

L0 用具名替换或每次重建唯一系统段表达；不要把环境快照永久追加成普通用户消息。保留本地 ContextSnapshot 用于审计，不要求每轮把这些快照都发给模型。tools 变更需同时保证可执行集合和最终模型请求的有效声明一致。

浏览器工具调用发生在 JS AgentTool.execute，经受限桥调用 Native。浏览器环境本身不限制“模型能调用工具”，限制来自执行器、Web API、权限和 SDK 兼容性。无文件系统的浏览器不能直接使用 Node fs；文件工具属于 Native 调试执行器。

## WebView 兼容性清单

| 层 | 需验证内容 | 失败处置 |
|---|---|---|
| 打包依赖 | ESM、目标 API lazy imports、TypeBox、SDK、是否意外拉入 node:fs/http/process/Buffer 路径 | 优先缩小入口/选择兼容版本；不加全套 Node 模拟环境掩盖依赖 |
| 浏览器能力 | Promise、AbortController、ReadableStream、TextEncoder/Decoder、Headers/Request/Response、crypto 所需子集 | 必需能力缺失阻止 Run；允许经过审查的最小兼容层 |
| Schema 校验 | schema 编译是否需要动态代码，CSP 是否兼容、两个模型的 JSON Schema 子集 | 不为不明依赖放开远程脚本和任意 eval |
| 资源 | WebView 本地安全 origin、离线资源、禁止 CDN | APK 自带资源可启动；外网不能替换执行代码 |
| 网络 | 所有目标 API 请求进入 nativeFetch；SDK 内部重试/额外请求不逃逸 | 测试探针阻止 JS 原生直连，查明遗漏 |
| 图像工具结果 | Pi toolResult 中图像如何转换为两个协议可接受消息，保持 tool call/result 配对 | 用官方适配/JS 投影；Native 不修正模型 JSON |
| 事件 | 文本/工具开始、增量、结束、abort/error 与终态持久化 | 归一为领域 RunEvent，不把 UI 绑死于上游枚举 |
| 生命周期 | IME、失焦、暂停、renderer 重启、宿主杀进程、长会话 | 中断并恢复只读历史，不恢复副作用 |

## 凭据引用方案（条件性决定）

真实 API Key 保留 Native；JS 只获得随机不透明 credentialRef 和非秘密占位值。由 JS provider 决定请求需要哪些 header；Native 使用预登记的通用 header 模板，在指定 origin/path 的出站请求上替换占位。Native 不按“OpenAI/Anthropic”分支选择 header，也不解析 JSON body。

需验证占位值不会触发 SDK 的 OAuth/特殊 token 判断，不会被发送给服务器，不会导致 SDK 在 headers 之外使用凭据。若 SDK 要求真实密钥参与协议计算，这个方案失败；先评审 JS 适配方案，不能静默把真实 key 存入 WebView。只支持用户自填 API key；OAuth 不在首版范围。

## PoC 与退出条件

执行 [POC-03/04/05](poc-plan.md)，先用固定流式响应与无副作用工具验证，再验证两个真实协议。每个链路都需要“图片工具结果→后续回答”，不能只测用户手动图片输入。

通过条件：无 Node 运行时依赖、无 JS 直连、两类模型闭环、可取消流、权限撤销、受限 origin、图片和长流内存可控。当前全未执行；README 浏览器支持不替代这些结果。若方案失败，ADR-002 重新评估，仍维持 Pi 解释模型协议的边界。
