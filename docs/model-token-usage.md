# 模型 token 用量与缓存诊断

每次模型请求结束后，Runtime 的 `model_round` 事件只保留经过校验的非负安全整数计数和固定状态标签，不保存供应商原始响应、请求正文、凭据或响应 ID。计数来自供应商最终 usage，不是字符数估算。历史版本只记录输入、输出和思考，没有记录缓存命中；没有缓存字段的旧事件无法事后还原命中率。

| 字段 | 口径 |
| --- | --- |
| `inputTokens` | 供应商原始输入数：Responses / Completions 包含缓存；Anthropic 不包含缓存读写 |
| `totalInputTokens` | 统一输入口径：Responses / Completions 使用原始输入；Anthropic 加上已上报的缓存读取与创建分量 |
| `cacheReadTokens` | 已上报的输入缓存命中数，属于总输入的一部分 |
| `cacheWriteTokens` | 已上报的输入缓存创建数，属于总输入的一部分 |
| `uncachedInputTokens` | DeepSeek 原始 miss；或由总输入减去缓存读写得到；Anthropic 使用原始 input |
| `outputTokens` | 供应商总输出，包含已计入其中的思考 token |
| `reasoningTokens` | 输出中的思考分量，不能再次加到输出或总消耗上 |

供应商未返回的缓存数保持缺失，不补成 0。明确返回的 `cached_tokens: 0` 则保存为 0。Anthropic 的 message_start 与后续 message_delta 为同一请求的累计计数，按字段更新，不逐事件相加。若供应商遗漏输入分量，已知分量不能证明完整用量，尤其不能用缺失的缓存字段计算命中率。溢出或不可能的派生计数不会被存为伪造的 0。

整段任务的输入缓存命中率应为 `Σ cacheReadTokens / Σ totalInputTokens`，只对缓存计数完整的请求计算，并同时报告覆盖请求数。不要平均每轮百分比，也不要把输出放入分母。总 token 消耗为 `Σ totalInputTokens + Σ outputTokens`；长对话中同一段历史每次发送都会再次计入请求输入，缓存只是使部分输入按缓存命中路径处理，并不从供应商 input_tokens 消失。输入的“系统提示 / 工具 schema / 对话 / 图片”细分若供应商没有分别报告，只能通过本地分段估算，必须与实际 usage 区分。

Responses 读取 `usage.input_tokens_details.cached_tokens`，兼容 `cache_write_tokens` / `cache_creation_tokens`；Completions 优先读取 `prompt_tokens_details.cached_tokens`，兼容 DeepSeek 的 `prompt_cache_hit_tokens`、`prompt_cache_miss_tokens` 与顶层 `cached_tokens`；Anthropic 读取 `cache_read_input_tokens`、`cache_creation_input_tokens`。相同含义的别名只选择一个，不叠加。

`cacheRetention: 'none'` 是 Pi 客户端选项，不能一概解释为供应商缓存被关闭。在当前 Pi Responses 实现中，它省略 `prompt_cache_key` 和会话亲和性；只有声明支持显式缓存模式的配置才会额外生成 explicit 模式。当前 DeepSeek 配置没有该显式模式标记。[DeepSeek Responses 文档](https://api-docs.deepseek.com/guides/responses_api/)明确说明不支持 `prompt_cache_key` / `prompt_cache_retention`，上下文缓存自动管理，因此仅改这个选项不能修复 DeepSeek 的前缀失配。

[DeepSeek 缓存文档](https://api-docs.deepseek.com/zh-cn/guides/kv_cache/)说明缓存按输入前缀匹配，缓存构建需要数秒且为尽力服务。应首先确认已发送的系统提示、工具定义及历史内容的前缀稳定，再分析服务端实际命中计数。OpenCode Go 的 Native `x-opencode-session` 固定取授权会话 ID，同一会话的不同请求和追问不会因请求 ID 改变而更换这个路由头。
