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

## 每轮结束后的 Footer

一次用户提问对应一个 Run。该 Run 结束后，在 Assistant 消息底部依次显示累计输入、输出 Token、缓存命中率，以及可用的费用估算；进行中和配置探测不显示完成统计。Token 达到 1,000 后使用 K/M/B/T，保留一位小数；不足 1,000 保留整数。缓存命中率为 `Σ cacheReadTokens / Σ totalInputTokens`，按累计 Token 加权并保留一位小数，不平均各请求的百分比；分母为零、分量缺失或无效时显示 `—`。精确 Token 数保留在 Footer 说明中，计费不使用取整后的显示值。

统计包含该任务里的多次模型请求，按请求轮次去重，不读取其他会话，也不把思考再次加到输出上。执行过程折叠后 Footer 仍保留在最终回复下方。展示事件最多 600 条的窗口不参与截断用量统计：Native 单独从磁盘读取当前页面中已结束任务的完整 `model_round` 数字记录，缓存仅保留这些可见任务的小型用量元数据，不加载旧思考、工具正文或 Pi 日志。缓存随页面切换裁剪，写入新用量或恢复任务时失效。重新打开会话仍可恢复完整统计，不修改 Pi 上下文或会话文件。

运行时只读取 APK 内的 [token-pricing.json](../app/src/main/assets/token-pricing.json)，不联网查询价格。该快照核对于 2026-10-18，来源为 [OpenCode Go 官方价格](https://opencode.ai/v2/docs/console/go/)与 [DeepSeek 官方人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)。OpenCode Go 显示美元，表示 API 单价对应的额度用量估算，不代表订阅之外新增的扣款；DeepSeek 官方显示人民币。自定义 Provider 只显示实际 Token，不显示金额。Provider 与模型使用 Run 创建时保存的值，不跟随之后的设置切换。

每次请求分别选择对应上下文阶梯及峰谷单价，再累加金额。费用公式为 `(非缓存输入 × 输入单价 + 缓存命中 × 缓存读取单价 + 缓存写入 × 写入单价 + 输出 × 输出单价) / 1,000,000`。缓存读写从总输入中扣除后分项计费；没有独立写入价格时，已上报的写入按普通输入计价。Anthropic 使用已统一的总输入，旧字段仅在缓存分量完整时才补成总输入，避免重复相加。

峰谷以各请求的 `wallTime`（完成时间）按内置 UTC 工作日规则估算，旧记录缺失时用 Run 开始时间加相对耗时；不额外推断中国法定节假日，因此估价可能与实际账单不同。上下文阶梯应用于单次请求的输入，不应用于整段任务的累计输入。

缺失用量显示 `—`，只有部分请求上报时计数加 `+`；没有完整缓存、输入或输出计数、无法确认时间或缺少对应模型价格时不伪造金额。内置 Provider 此时显示“费用未估算”。极小的非零费用显示“小于 0.0001”，避免四舍五入成免费；明确零单价且用量完整的模型才能显示零估价。新发现且尚无本地单价的模型同样保留 Token、不猜测价格。

最新更新通过 119 项页面／计费测试、223 项 App JVM 测试、Android 静态检查及三种窗口尺寸的浏览器检查。Thor 上另通过 1 项独立临时数据库测试：80 次模型请求及其 800 条思考事件，页面仅加载最后 2 条事件时仍完整统计全部 80 次请求，并验证缓存拷贝隔离、迟到用量使缓存失效、切换会话隔离及运行中的任务不加载旧用量。首次断言误把页面保留的原事件也当作用量投影，修正断言范围后通过；测试未改用户库。

新版已保留数据安装至 Thor，下屏截图实际显示“输入 73.4K · 输出 5.5K · 缓存命中率 72.9% · 估算 ¥0.0431”；只读读取数据库确认来自 5 次模型请求，精确输入 73,429、缓存命中 53,504、输出 5,520，独立 Decimal 计算为 ¥0.04307508。全部会话表和配置／Skill 文件核对保留。本次不新增真实模型请求或游戏操作，界面验证使用既有用户会话。
