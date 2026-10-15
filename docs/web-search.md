# 联网搜索工具与后端

> 2026-10-13 状态更新：五模块已开发并进入正式软件整合。本文早期“仅规划/未授权/无 APK”等叙述保留其历史背景；当前实现、实际测试与尚未完成的 Thor 验收，以 [中文集成报告](integration-report.md) 为准。来源模块旧测试不等于本次集成结果。
## 产品语义

搜索默认 ON 是 Session 的能力意愿，不提供免费搜索额度，也不等于模型自身具备联网能力。首版必须有一个经过验证的后端；无密钥、无配置或网络不可达时显示具体原因，不注入假可用的 search 工具。

[决定] 搜索实现为独立 web ToolPack；与两种模型协议无关，不依赖厂商模型内置 web_search。JS 适配后端响应，通过 Native 通用 HTTP 出站；Native 执行网关校验 web_search lease。搜索结果只作为不可信资料，不执行网页里的指令。

## 后端候选

| 候选 | 官方可确认 | 本项目决定/风险 |
|---|---|---|
| Brave Search API | 提供 Web Search HTTP API，见 [官方文档](https://api-dashboard.search.brave.com/app/documentation/web-search) | 首发候选；用户自备 key；Thor 网络环境、中文/游戏结果、配额与价格待配置时验证 |
| Tavily | 提供 Search API，见 [官方接口](https://docs.tavily.com/documentation/api-reference/endpoint/search) | 备选；结果格式与成本另适配；不为可选摘要重复消耗模型费用 |
| 自建 SearXNG | 有 search API，JSON 是否启用取决于实例配置，见 [官方文档](https://docs.searxng.org/dev/search_api.html) | 用户已有实例可选；不依赖无 SLA 的公共实例，不在 APK 内安装后端 |

不写死当前价格、免费额度或国内可达性。候选有文档不代表在用户网络可用。初版只实现选择的一个后端，其他作为未来替换路径。

## 工具契约

| 工具 | 参数 | 模型可见结果 | 上限/错误 |
|---|---|---|---|
| web.search | query、language 可选、freshness 可选、limit 默认 5/最大 10 | title、URL、snippet、来源名、publishedAt（可为空）、retrievedAt | query 最长 512 字符；响应文字初值 12k 字符；AUTH/RATE_LIMIT/NETWORK/NO_RESULTS |
| web.fetch_page | 明确 URL、maxChars 默认 12000/最大 24000 | title、最终 URL、提取正文、检索时间、截断标志 | 下载初值 2 MiB/20 秒；HTML/text 首版，PDF/脚本渲染明确不支持 |

web.fetch_page 只在搜索能力启用且抓取后端可用时注入；搜索密钥缺失时可以单独提供无密钥网页读取（若实现已通过验证），UI 必须区分“能打开已知网页”和“能搜索”。避免把工具组就绪误写成整个后端就绪。

提取网页移除脚本、样式、隐藏垃圾与导航；不运行远程 JS，不访问登录 cookie，不下载或执行程序。结果链接允许模型引用，但本地 file/credentialRef 等标识不得混入搜索请求。

## 控制与隐私

search/fetch 的网络请求携带对应工具执行许可；关搜索立刻取消排队/进行中的搜索，拒绝新请求，历史原始搜索资料默认不再进入后续模型投影。本地已显示结果保留，用户可看；已发出的查询无法撤回。

查询可能包含用户问题，但不自动附带截图、日志或文件内容。Agent 若要将敏感调试信息转为搜索词，应优先取非敏感错误码/应用名称，避免发送完整配置或 token。模型网络关闭问题与搜索开关分开处理。

缓存仅保存规范化查询与有时间戳结果，初值有效期 30 分钟；版本最新性判断优先重新查询；缓存命中仍展示检索时间。缓存随保留策略清理，搜索 key 不缓存进结果。

## POC-09 验收

在实际 Thor 网络下验证中文/英文各类查询、目标模拟器发布信息、5xx/429/无结果、HTML 无正文、网页跳转、公网/内网/回环与代理 DNS 可达性、搜索关闭时调用拒绝。回答需标出来源 URL 和时间，区分抓取失败与“没有信息”。正式后端选择保留为 Q-07。
