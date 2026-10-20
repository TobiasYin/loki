# OpenCode Zen 免费模型

在「设置 → 模型服务 → 添加模型服务」选择 **OpenCode Zen（免费模型）**，填写该服务的 API Key。停止输入约 800 ms 后自动获取免费模型，也可点击「刷新 Zen 免费模型」。选择模型后点击右上角「保存」，再启用该服务；聊天输入区可继续在这套服务的免费模型间切换。重新打开已保存的服务时，会使用其独立保存的 Key 自动刷新。

当前验收状态（2026-10-20 最新真机复测）：始终使用原生 HTTP 直连，没有 CLI 或本地转发进程。用户授权后，AYN Thor / Android 13 上的 GLM 三轮验收全部通过：文字、Native 技能列表、Native Skill 读取，共 5 次模型请求均为 HTTP 200；设置页连接检查亦通过。此前模拟器的出口 IP 拒绝作为独立环境的历史结果保留。详见文末「Thor 真机验收」。

使用官方基础地址 `https://opencode.ai/zen/v1`，从 `/models` 获取可用 ID，再与 Models.dev 的 `opencode` 能力和价格目录取交集。只有明确声明输入、输出及已声明缓存单价均为零，且具有兼容协议、文字输出和工具能力的模型才会列出。不能仅凭 ID 含 `free` 判断价格；没有 `free` 后缀但价格为零的模型同样可以列出。未知能力、已弃用或不兼容的模型会被排除。

API Key 按 Provider 记录在 Android Keystore 中加密保存，不进入 WebView、模型目录缓存或 Models.dev 请求。Key 仅发送到 Zen 的模型列表及用户发起的模型请求；目录请求不跟随重定向。刷新失败保留本地模型资料；成功返回的空免费列表会清空可选项并阻止使用过期模型。内置 2026-10-20 的公共目录快照用于首次显示及离线回退，免费状态与可用性以服务端为准。

## 上下文与输出预算

各模型分别保存总上下文 `limit.context`、独立输入上限 `limit.input`（未声明时使用总上下文）和输出上限 `limit.output`，不会给所有 Zen 模型套同一个容量。例如此次公共目录中的 Big Pickle 总上下文为 200,000 tokens，输入上限为 160,000，输出上限为 32,000。设置页会同时显示这三个值；聊天区模型面板也会在输入上限小于总上下文时显示独立输入容量。

运行时把总上下文与独立输入上限中较小的容量交给 Pi 历史管理器，让自动压缩更早触发。每次发送前检查当前 Pi 输入估算，加上 4,096 tokens 余量不得超过独立输入上限。输出自动模式继续从总上下文扣除输入估算及 4,096 tokens 余量，并受该模型输出上限约束；用户显式填写的输出上限同样必须满足模型容量。压缩过程的请求也接受输入预算检查；当前输入过大且无法安全压缩时会返回容量限制错误，不会无限增大容量或静默丢弃历史。输入 token 数是估算，服务端实际分词及限流仍可能返回错误。

切换模型后使用新模型的能力和容量重新构建运行配置。对话历史沿用现有 Pi 会话持久化和压缩机制。

## 参考

- [OpenCode Zen 官方接口说明](https://opencode.ai/docs/zen/)
- [Zen 可用模型](https://opencode.ai/zen/v1/models)
- [Models.dev 公共能力与价格目录](https://models.dev/api.json)

## 本次验证（2026-10-20）

- `:app:testDebugUnitTest`：267 项全部通过；覆盖价格筛选、目录体积预算、认证失败、重定向拒绝、Key 隔离与配置持久化。
- `npm --prefix runtime test`：405 项全部通过；包含 Zen 文字/工具调用路由、独立输入上限拦截，以及总上下文仍有余量时按独立输入上限触发 Pi 自动压缩。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，均已编译并以 `install -r` 安装。
- AVD `thor_agent_dual_api36` / `emulator-5560` / Android 16 API 36 ARM64；核对 display 0 为 1920×1080、369 dpi，display 4 为 1240×1080、369 dpi。`EmulatorOpenCodeZenTest` 在 display 4 通过 1 项完整场景测试。
- 设备场景覆盖填 Key 后自动获取、排除付费模型、选择和保存后重开、聊天模型信息显示输入容量、Native 配置携带输入上限、失败保留缓存、成功空列表禁用。使用合成 Key 和进程内 HTTP 拦截响应，无真实云端推理。测试结束恢复原 Provider、目录及配置偏好，并校验原有加密凭据一致。
- 本地验收记录：`build/verification/opencode-zen/device-report.json`、`device.log`、`runtime.log`、`settings.png`。

前述受控验收不包含真实云端推理；后续真实调用结果见下节。本次未向 Thor 真机安装或执行测试。

## 真实服务与 Session 路由复核（2026-10-20）

用户授权在同一专用模拟器保存独立测试服务「OpenCode Zen · 模拟器测试」，不覆盖其他 Provider 的凭据。真实 `/models` 和 Models.dev 拉取成功，筛选出 7 个免费兼容模型；当前选用 `glm-5.3-flash-free`，上下文与输入上限 200,000、模型输出容量 32,000，测试显式输出上限 4,096，思考强度 low。

初次真实运行收到 HTTP 403，错误类型 `FreeTierError`。该错误文案不能单独证明所有第三方请求都不受支持，也不能据此宣称模型已通过验收。连接测试保留服务端脱敏诊断，并对免费层拒绝单独说明。

复核发现 Zen 的会话路由头此前未配置，已补齐：Native 为每个本地会话持久保存独立的 `x-opencode-session`，使用官方 `Identifier.descending("session")` 兼容格式（`ses_` + 12 位时间戳/计数器反码十六进制 + 14 位 Base62 随机字符）；同一聊天的多轮请求及重启使用同一值，保留原有应用会话 UUID。连接探测使用独立临时会话 ID。该阶段请求使用 `loki/<version>` User-Agent；最新兼容请求头已按文末复测结论调整。

参考官方 [ID 生成实现](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/id/id.ts)、[请求头实现](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/llm/request.ts)。只补正常的会话标识并不能证明请求已被服务端接纳，必须以真实推理结果为准。复核记录保存到 `build/verification/opencode-zen/live/`，不含 API Key。

复测结论：专用模拟器曾出现独立的 DNS 解析故障（`ping: unknown host opencode.ai`），已通过保留数据的 AVD 冷启动恢复，并再次核对双屏角色、尺寸和密度。恢复后，在修正 Session 路由的实际产品运行中，GLM 免费模型仍返回 HTTP 403；设置页连接测试约 1.3 秒返回 `FreeTierError`。因此当前状态为「配置和免费目录获取成功，真实聊天未通过」，工具调用未能进入执行阶段。不能把此结论扩展为所有账户、模型或客户端都不可用。

本次修正后，应用 JVM 测试 267 项及 Native bridge core 测试 48 项全部通过。模拟器测试服务、独立加密凭据及默认选择予以保留；冷启动后成功读取该记录。真实服务结果见 `live/runtime-final.json`、`live/runtime-final.log`、`live/connection-ui.json` 及 `live/connection.png`。这些记录仅含公共模型元数据、固定状态、合成测试结果及脱敏错误，不包含 Key。

修正后的最终受控设备场景再次通过（`controlled-final.log`）。复测同时修复了已选会话在免费目录变空后仍被误判为可用、进而读取缺失 profile revision 的边界问题。测试保留并恢复已配置的真实 Zen 凭据与其他 Provider；完成后将应用重新启动到 display 4。

## 工具协议与代理仓库复核（2026-10-20）

用户提供的 [OmniRoute issue #13935](https://github.com/diegosouzapw/OmniRoute/issues/13935) 及其合并修复 [PR #16858](https://github.com/diegosouzapw/OmniRoute/pull/16858) 显示，除 Session ID 外，上游还可能检查工具声明；维护者报告将占位工具改为 `read` 后部分免费模型可用。这是其他客户端的观测，不能代替 Thor 实测。

Zen 运行时将已有、可用的 `skills.read` 以 `read` 名称暴露给模型，仍使用真实 Skill 资源读取 schema 和描述，参数校验、Native 授权、事件及权限标识保留 `skills.read`。它只能读取允许的 Skill 及其资源，不能任意读取文件。不添加空操作工具，也不凭空声明 shell。其他 Provider 保留 `skills_read`；现有 `read` 名称冲突时保留原名；Skill 读取不可用时不会强行发布。新增 3 项测试覆盖实际 Native 身份与执行、不可用工具排除、其他 Provider 和名称冲突。

宿主机按该线索发送含真实 Skill 读取声明的对照请求后，GLM 返回 `FreeTierError`，文案明确指向当前出口 IP 的滥用流量限制，并称与账户无关；DeepSeek 对照仍返回客户端使用条件拒绝。错误不同说明请求内容影响了服务端结果，但不足以证明已完全通过其他检查。

更新 APK 后，在同一 AVD、同一独立加密 Key 上通过实际聊天运行复测：目录仍返回 7 个免费兼容模型，Session ID 格式及重启读取正确；GLM 第一轮仍收到 HTTP 403（352 bytes），没有完成文字回复，工具阶段未执行。记录见 `live/runtime-read-alias.json` 和 `.log`。设置页的独立文本连接测试不发布产品工具，其结果不能等同完整 Agent 工具链验收。真实云端推理仍为未通过。

另已读取 [vyn-7/opencode-bypass](https://github.com/vyn-7/opencode-bypass) 的 README、代理源码和 agent 配置，没有运行其安装脚本或注册自启动。该实现经本地 `opencode serve` / `opencode run` 调用真正的 OpenCode CLI，再转换 OpenAI 流式协议；它不是生成 Session ID 的库。源码将图片替换为省略标记，客户端工具转为提示词文本及 `<tool_call>` 块，且后端仍保留 OpenCode 自身工具执行能力，依靠 agent 提示词要求模型不要调用。因此不能直接代替 Thor 的图像输入、Native 工具授权及上下文预算机制；当前产品未引入该代理。

最终 `npm --prefix runtime test` 405 项通过，APK 和测试 APK 构建成功，双屏模拟器受控 UI 场景再次通过（`live/controlled-read-alias.log` / `.json`）。安装均使用 `install -r`；已有 Provider 和 Keystore 凭据保留，应用最终启动于下屏 display 4。

## 按 issue 继续直连复测（2026-10-20）

重新核对 [合并后的请求头代码](https://github.com/diegosouzapw/OmniRoute/blob/224ca60/open-sse/utils/opencodeHeaders.ts) 和 [免费层请求契约](https://github.com/diegosouzapw/OmniRoute/blob/224ca60/open-sse/executors/opencodeFreeTierContract.ts)，发现此前测试遗漏了带版本号的兼容 User-Agent。新增仅在显式启用时执行的模拟器协议对照测试；不执行诊断声明的工具，也不改变 Provider 或凭据。

同一 AVD、同一 Key 下，保持有效 Session 和真实 Skill 读取 schema 的 `read` 声明，得到以下对照：

| 请求变化 | GLM 结果 |
| --- | --- |
| `loki/<version>` | HTTP 403，客户端契约拒绝 |
| 仅改为 `opencode/1.18.31`，不补其他头 | HTTP 200，完整 SSE 和精确测试标记 |
| 保留 Loki UA，仅补 client/project/request 头 | HTTP 403，客户端契约拒绝 |
| `opencode/1.18.31 loki/<version>` | HTTP 200，完整 SSE 和精确测试标记 |

双 Session 头、`read.filePath` 参数及 `bash` 声明并非上述 GLM 成功请求的必要条件。生产请求仍使用实际可用的 Skill 读取能力和原参数，不注入虚构 shell。带 `bash` 的一次控制请求仅用于还原 issue 的诊断条件，未调用或执行该工具。

协议矩阵中 beam、DeepSeek、GLM、LongCat、MiMo、MiniMax、Nemotron 共 7 个免费模型均返回 200、完整 `[DONE]` 及精确文字标记；该矩阵验证独立文字请求，不验证 Native 工具执行。记录为 `live/contract-matrix.json` / `.log`，UA 单变量对照为 `live/contract-identity.json` / `.log`。

正式请求使用 `OpenCodeZenRequestContract` 集中提供带 Loki 归属的兼容 UA；连接测试同时声明与运行时相同的只读 Skill schema。WebView 实际桥接请求未保留设置的 UA，故将最终 UA 放在 Native `NetworkProfile.nativeUserAgent` 中，在准备请求时覆盖浏览器默认值。该设置只用于 Zen 网络 Profile；Go 和其他 Provider 不套用 Zen 兼容值。新增 Native 回归覆盖浏览器缺失、调用者覆盖、Profile 隔离和非法头值。Zen Chat Completions 还显式使用 `max_tokens`，离线旧目录缓存也会应用此兼容字段。

Native 修正后，实际 GLM 首轮聊天收到 HTTP 200，输出测试标记（2,770 输入 tokens，39 输出 tokens）；同一聊天第二轮工具请求返回 HTTP 403。后续 GLM、DeepSeek、Beam 和设置页连接测试均被拒绝。测试观察器仅记录固定错误分类，确认后续错误为 `network_abuse`，不同于最初的 `client_contract`。设置页保留的脱敏诊断明确说当前出口 IP 检测到滥用流量，且与账户无关。不能把先前客户端头缺失造成的失败都归为 IP 问题，也不能把独立文字成功扩展为完整工具链通过。

应用内记录：`live/runtime-native-identity.json`、`live/runtime-three-phase-glm.json`、`live/runtime-three-phase-deepseek.json`、`live/runtime-three-phase-beam.json`；设置页诊断和截图：`live/connection-contract.json`、`live/connection-contract.png`。当前没有更换或绕过出口网络。模拟器未配置 Android 全局 HTTP 代理，宿主任务没有 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY 环境变量；用户另已明确确认当前没有开启 VPN/TUN/代理。上游返回 IP 限制并不证明用户使用了代理，也可能包含服务端误判。

最新本地检查为应用 JVM 268 项、Native bridge core 49 项、Runtime 405 项通过。Debug APK 和测试 APK 重新构建并以 `install -r` 安装；专用双屏配置及现有 Key 保留。工具调用的三阶段真实验收仍为未通过，等待出口限制解除后继续验证。

最后以 Beam 在最终 APK（`max_tokens`）执行相同三阶段验收，首轮同样收到 403 / `network_abuse`，未进入工具阶段。GLM、DeepSeek、Beam 均受到当前出口限制，证据不支持“仅某一个模型不可用”；其他四个模型只有先前独立文字成功记录，未据此声称它们此刻仍可用。

另核对 [官方免费模型说明](https://opencode.ai/v2/docs/console/models/#free-models)：多个免费模型属于限时供应，Beam 另有地区限制，包括中国内地、香港和澳门。模型目录可见和零价格不等于对任意地区或出口可调用；但本次 Beam 的实际错误分类仍是 `network_abuse`，未据此将错误改判为已确认的地区限制，也未确认具体每日或每分钟配额。

## Thor 真机验收（2026-10-20）

用户明确要求在真机上验证后，使用 USB 设备 AYN Thor，serial `THOR_DEVICE`，Android 13 / API 33 / ARM64。聊天在下屏 display 4（横屏 1240×1080），上屏 display 0。应用版本 `0.8.0-integration` / versionCode 9，保留数据安装 Debug APK 和测试 APK。实际设备 APK SHA-256 与构建产物一致：`40406cb846e493e578372d843a7ad7f5b427d5590b5105036b6e8604d3ef186c`。

新增独立服务「OpenCode Zen · 真机测试」，使用用户此前提供的 Key，经一次性应用私有缓存输入后在真机 Keystore 下加密保存；一次性缓存已删除，不复制其他服务的凭据，不在报告中保存 Key。界面实际自动刷新得到 7 个免费兼容模型并保存 GLM。测试输入为合成内容，模型为 `glm-5.3-flash-free`，上下文与输入上限均 200,000，模型输出上限 32,000，本轮输出上限 4,096，思考 low。测试会话关闭设备感知、屏幕控制、联网搜索及 ADB 能力。

三阶段真实产品运行耗时 29.47 秒，全部通过：

| 阶段 | 真实结果 |
| --- | --- |
| 第一轮文字 | HTTP 200，收到精确标记 `ZEN_THOR_LIVE_OK` |
| 第二轮技能列表 | 模型请求 `skills_list`，Native 执行 `skills.list`；结果回传后的第二次模型请求也为 200，最终回复标记正确 |
| 第三轮 Skill 读取 | 模型请求 `read(name="adb-operations", path="SKILL.md")`，Native 执行 `skills.read`；结果回传后的第二次模型请求为 200，最终回复标记正确 |

共 5 次真实模型请求全部 HTTP 200，流正常结束。输入从 2,763 到最后一轮 5,147 tokens，后续命中上游缓存；同一聊天多轮继续使用同一 Native Session 路由。没有工具替身或伪造结果。WebView 仍未保留 JS UA，由 Native Profile 注入正确兼容头。随后重新打开已保存的服务，在真实设置页点击连接测试，3,528 ms 返回文本流可用。该按钮只验证文字连接；完整工具往返由上面的三阶段运行独立验证。

本轮真机没有复现此前模拟器的 403。它证明当前版本和所选 GLM 在这台真机上完成了直连文字和只读工具链，不能据此证明所有模型、所有出口或未来免费状态始终可用。

数据保全核对：原有 42 个会话、55 次运行、110 条消息、2,871 条事件、42 个附件的逐 ID 内容摘要全部保持一致。原有 2 个 Provider 及 6 项凭据存储条目均保留；仅新增独立 Zen 测试服务、1 个测试会话、3 次运行、6 条消息和 39 条事件。原默认 Provider 已恢复，程序恢复原选中聊天，系统无障碍设置保持一致。未改动亮度、风扇、刷新率、屏幕角色或网络设置。应用最终在下屏 display 4 空闲。

证据位于 `build/verification/opencode-zen/physical/`：`live-glm.log`、`live-glm.json`、`chat.png`、`connection.log`、`connection.json`、`connection.png`、`preservation.json` 和 `package.json`。前面的模拟器受限结论仍按各自记录保留；本节是真机新增通过证据。
