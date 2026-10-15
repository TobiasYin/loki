# 模型接入与通用 Native Fetch

> 2026-10-13 状态更新：五模块已开发并进入正式软件整合。本文早期“仅规划/未授权/无 APK”等叙述保留其历史背景；当前实现、实际测试与尚未完成的 Thor 验收，以 [中文集成报告](integration-report.md) 为准。来源模块旧测试不等于本次集成结果。
## 责任边界

[需求] 用户自填 OpenAI Responses 兼容 API 或 Anthropic Messages API，必须支持图片输入与工具调用。Pi/JS 负责 provider 选择、URL 路径构造、JSON、图片编码、鉴权 header 名称、SSE、tool call/result、停止原因、token usage 和协议兼容。Native 仅收发通用 HTTP。

[事实] 官方两类协议都有图片与工具交互文档：[OpenAI 图片](https://developers.openai.com/api/docs/guides/images-vision)、[OpenAI 工具](https://developers.openai.com/api/docs/guides/function-calling)、[Anthropic 图片](https://platform.claude.com/docs/en/build-with-claude/vision)、[Anthropic 工具](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)。第三方端点仍须单独验证，不从“兼容”名称推断能力。

## 模型配置

| 字段 | 规则 |
|---|---|
| profileId / revision / label | 本地配置标识与版本，Run 开始时固定；修改影响新 Run |
| protocol | openai-responses 或 anthropic-messages；不自动降级 Chat Completions |
| baseUrl | 用户填写基础地址；具体 /responses、/messages 等路径由锁定 Pi provider 构造；配置测试展示最终 URL，防重复 /v1 |
| modelId | 用户真实模型名；不依赖内置目录猜别名，不强制 models 列表接口 |
| credentialRef | Native 私有存储引用，JS 和数据库普通表不保存明文 key |
| contextWindow / maxOutput | 手动配置或经过验证的元数据；未知时保守预算，不编造价格/token 上限 |
| customHeaders | 明确允许的通用 header 配置；秘密值另存 credentialRef；拒绝 Host 等危险覆盖 |
| capabilities / lastProbe | textStream、imageInput、toolCalling、imageToolRoundTrip 的探测状态、时间和端点修订 |

首版使用 HTTP 流式路径；若 provider 有 SSE/WebSocket 选择，限定已核对的 SSE/HTTP 方案。自定义 Fetch 不接管 WebSocket，禁止 auto 意外切换出 Native 通道。

## 已实现的 Native wire v1 与 Runtime 内部 v0.1 适配

信封统一包含 bridgeVersion、requestId、runId、purpose、网络 profileRef、epoch/lease。purpose 区分 model、web_search、web_fetch、用户发起的配置测试；不是 JS 自报后就生效，Native 对照已登记授权上下文验证。

| 消息 | 方向 | 内容/保证 |
|---|---|---|
| request.start | JS → Native | URL、method、headers、已序列化 body 引用/字节；deadline、credentialRef、限制 |
| response.head | Native → JS | status、statusText（可为空）、规范化 headers、最终 URL；收到头即可返回 JS Response |
| response.chunk | Native → JS | 原始字节＋单调 sequence；块不是字符/JSON/SSE 事件 |
| response.end | Native → JS | 正常完成，一次终态 |
| response.error | Native → JS | 网络/TLS/取消/桥故障；HTTP 4xx/5xx 仍是 Response，不当连接错误吞掉 body |
| stream.credit | JS → Native | 消费确认/剩余窗口，驱动背压 |
| request.abort | JS → Native | 取消底层 call、停止发送，幂等；不再交付迟到数据 |

首版必要 Fetch 子集：string/URL/Request 输入、method/header 合并、JSON/string/Uint8Array/ArrayBuffer 请求 body、AbortSignal、Response status/headers/url、ReadableStream body，以及 SDK 实际调用的 text/json/arrayBuffer/bodyUsed/clone 语义。逐项由依赖源码和契约测试确定；未支持的表单/流式上传等明确拒绝，不声称完全符合浏览器 Fetch 标准。

Native 可以实现通用流式压缩解码、TLS、连接取消和 HTTP redirect 策略，但不能识别 SSE data/event、[DONE]、response.output_item、content_block_delta 或图片 JSON。SDK/Pi 对 UTF-8 跨块和多行 SSE 的处理由 JS 完成。以通用 HTTP 压缩解码后的 body 为桥字节层时，响应头须一致，避免 JS 二次解压。

## 流量与内存预算（待 PoC 调整）

单 chunk 初值 32 KiB；每请求未消费缓冲上限 256 KiB；单模型请求 body 上限 16 MiB；累计响应上限 32 MiB；连接超时 15 秒、读空闲 60 秒、单次请求总时限 180 秒。与 provider 最大输出配置联动；超限返回明确错误，不静默丢块。截图解码的原始缓冲另行计量，不能只按 JPEG 大小估算内存。

优先特性探测后的 ArrayBuffer 消息；不支持时用有上限的 base64 分块，并计入编码开销。AndroidX 提供消息桥/消息数据类型，但具体 Thor WebView 支持由 feature probe 与真实传输测量确认。[WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat)、[WebMessageCompat](https://developer.android.com/reference/androidx/webkit/WebMessageCompat)。

## 凭据与网络边界

Native credentialRef 绑定精确 scheme/host/port、允许路径前缀和 header 模板。JS provider 决定协议鉴权格式，Native 只做通用安全替换；不按模型品牌写条件分支。请求头/错误体/诊断日志中遮蔽秘密值；凭据不得出现在 URL、tool 参数和模型上下文。

2026-10-15 实机接入补充：OpenCode Go 的 Responses 已用 `deepseek-v4.1-flash` 实测通过，基础 URL 为 `https://opencode.ai/zen/go/v1`，最终路径 `/responses`。该精确 HTTPS 端点另需 `x-opencode-session` 路由头；Native 从当前授权的 Session ID 安装并覆盖调用方同名值，不能借 JS 请求路由到其他会话。这是端点路由适配，Native 仍不解析模型 JSON/SSE；其他端点不添加此头。模型图片、工具与图片工具回传及真实 Brave 搜索结果见 [实机报告](physical-test-report.md)。

默认 HTTPS，正常证书验证，不忽略证书错误。首版拒绝自动重定向带凭据请求；可向用户显示新地址并要求重新配置。网页抓取重定向每一跳重新检查公共地址范围，避免 localhost、私有地址、link-local 和 DNS 重绑定；本地模型服务只通过用户显式登记的独立配置使用，不向任意搜索 URL开放。

模型网络是聊天必需，不等于联网搜索能力。搜索 OFF 不切断模型请求；反之模型 profile 不能成为任意 URL fetch 工具。外部网页在无 Native 桥的外部浏览器打开。

## 重试、错误与取消

只由 JS/Pi 决定 provider 语义重试；Native 不根据模型错误 body 重试，不自动重发已上传的模型 POST。配置中限制 Pi/SDK 语义重试初值最多 2 次并设置总时限，准确 API 支持在锁定版本验证。避免 SDK＋Pi＋Native 叠加重试。

流在第一个完整响应之前断开、部分文本后断开、工具调用参数未闭合等都要有不同状态。未验证完整的工具参数绝不能执行；已执行副作用后断流，重新请求模型不得自动重复同一 toolCallId。重复/冲突 id 由 Run Coordinator 拒绝并留审计。

## 配置验证流程

用户点击测试才对选定端点发请求：连接/鉴权 → 文本增量 → 应用内非敏感测试图片 → 无副作用 echo 工具 → 图片工具结果后续回答。报告各项单独通过/失败；支持模型不代表 endpoint 兼容，不能只收到 HTTP 200 就显示“可用”。测试会调用用户服务，设置页应说明可能计费；本地受控协议服务和上文指定的真实 OpenCode Go 配置均已测试，不能据此推断所有模型或第三方端点兼容。

POC-04 用随机分块、半个中文字符、多行事件、空闲心跳、4xx/5xx、取消和背压验证。最终请求中的工具声明与有效权限以 POC-05 审查。
