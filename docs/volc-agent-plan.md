# 火山 AgentPlan：单 Key 接入

2026-10-19

设置 → 模型服务 → 添加模型服务 → 火山 AgentPlan。填写个人版套餐专属 API Key，选择文本模型并保存，再点击“使用此服务”。凭据通过现有 Android Keystore 保存为该服务自己的引用；不会复制到搜索、语音偏好，不回填 WebView。可保存多个账号，当前服务的选择决定使用哪个 AgentPlan Key。

首次初始化也可以直接选择：连接 AI 服务 → 配置火山 AgentPlan → 填写套餐 Key、选择模型 → 保存。保存后自动将该服务设为当前服务，并返回初始化第一页；语音与搜索会显示共享配置状态。空 Key 或停用状态不能作为初始化的模型服务保存。返回可放弃草稿，原有 DeepSeek、OpenCode Go、自定义服务入口继续保留。

| 能力 | 产品入口 | 固定服务地址 / 模型 |
| --- | --- | --- |
| 文本、图片输入与工具调用 | 模型设置与聊天模型选择器 | `https://ark.cn-beijing.volces.com/api/plan/v3/responses`；模型是否接受图片由目录中的 input 决定 |
| 语音识别 | 聊天按住说话 | `wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async`；ASR 2.0，`volc.seedasr.sauc.duration` |
| 语音合成 | 语音设置 → AgentPlan 语音合成 → 合成并播放 | `https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional`；TTS 2.0，`seed-tts-2.0` |
| 豆包搜索 | 搜索设置选择“自动选择”或“豆包搜索”，聊天使用 web.search | `https://open.feedcoopapi.com/search_api/web_search` |

语音默认跟随当前 AgentPlan；可以勾选“始终使用独立语音凭据”恢复独立语音服务。其他供应商仍使用既有独立语音配置。切换账号或停用服务后，下一次录音重新解析当前配置。TTS 是前台显式操作，最多 1000 个 Unicode 字符，PCM 仅在 Native 内存中处理，离开设置页或点击停止会取消请求与播放。

搜索自动选择优先使用当前 AgentPlan，其次为已配置的 DeepSeek、Tavily、Brave。显式选择其他搜索服务或关闭搜索的偏好继续有效。需在火山控制台开启豆包搜索抵扣；Key 正确并不等于已开启此权限。豆包搜索请求使用 `Query`、`Count`、`SearchType`，结果解析 `Result.WebResults`；查询限制为 1–100 个 Unicode 字符。网页 URL 抓取继续走现有公共网络读取策略。

## 模型发现与选择

火山管理面 `ListArkAgentPlanModel` 只支持 AK/SK 鉴权；2026-10-19 使用套餐 Key 请求数据面 `/api/plan/v3/models` 实测 HTTP 404。因此单 Key 模式读取[火山官方 OpenClaw 配置文档](https://ark.volcengine.com/region:cn-beijing/docs/agent-plan-personal-openclaw.md)中的结构化模型配置，不额外索取 AK/SK，也不向文档主机发送 Key。

“发现 / 刷新火山官方模型目录”在线读取官方模型 ID、输入模态、上下文和配置输出上限；验证后缓存小型公开字段。首次离线可用随包官方目录。刷新失败、格式改变、重复 ID、异常输入模态或超出容量限制时保留原缓存。目录当前包含 13 个模型条目，包括 Auto、Doubao、GLM、DeepSeek、Kimi。该目录用于选择，不能证明用户档位、额度和当前模型权限，界面明确提示连接测试另行验证。目录输出上限沿用官方工具配置的保守值，不冒充完整模型理论输出容量。

设置页与聊天选择器使用同一目录。保存选择后由现有 ProviderStore / ProductHost 固定每次 Run 的供应商、版本和 Key。只有精确套餐 HTTPS 地址及已知官方目录可使用官方元数据；自定义服务不能借用火山标签跳过原有验证。

## 验证方式

- JVM 回归覆盖目录解析、离线目录、异常目录拒绝、固定端点、预算、ASR 路由与凭据诊断脱敏、搜索优先级、TTS 完整终态与错误处理。
- Runtime 回归覆盖套餐 Responses 路由、官方元数据边界与现有工具循环。
- `scripts/web-services-test.mjs` 覆盖豆包请求、响应、错误与 Unicode 查询边界。
- `EmulatorAgentPlanTest` 是显式开启的模拟器真实网络验收。Key 只通过 stdin 导入应用私有临时文件，导入 Keystore 后立即删除；使用公开合成 PCM，不使用物理麦克风。报告区分连接测试、完整模型搜索工具循环、ASR、TTS。最终实测结果见本轮验收报告，不以目录刷新或 HTTP 200 替代完整能力验收。

参考：[套餐概览](https://docs.volcengine.com/docs/ark/agent-plan-personal-plan-overview?lang=zh)、[语音模型](https://ark.volcengine.com/region:cn-beijing/docs/agent-plan-personal-voice-model.md)、[豆包搜索](https://docs.volcengine.com/docs/ark/agent-plan-personal-search?lang=zh)、[官方搜索实现](https://github.com/volcengine/mcp-server/tree/main/server/mcp_server_askecho_search_infinity)、[模型列表管理 API](https://docs.volcengine.com/docs/ark/list-ark-agent-plan-model-api?lang=en)。

2026-10-19 初始化补充验收：专用模拟器覆盖空模型配置启动、初始化页直接进入 AgentPlan、空 Key 拦截、填写单 Key 与选择模型、保存后自动设为当前服务、共享语音 / 搜索及完成权限引导。测试使用合成 Key，不请求云端；结束后恢复原有服务及初始化偏好。证据位于 `tests/agentplan-onboarding-evidence`。
