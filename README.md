# Loki

Loki 是放在 AYN Thor 下屏的 AI 搭子。它能观察上屏的游戏，并帮你操作或者提出建议；也能搜索攻略、安装应用、排查系统与游戏问题，按你的要求调整亮度、风扇和聊天背景。

当前公开发布版本：**v0.1.0**（Android `versionName=0.1.0`、`versionCode=10`）。项目从此版本重新开始公开版本编号；下文 0.3–0.8 等记录属于此前内部开发阶段，保留原始验证范围。发布说明见 [v0.1.0](docs/releases/v0.1.0.md)。

应用名：`Loki`；Android 包名：`com.tobias.loki`。图标与生成说明见 [应用品牌资源](app/branding/README.md)。更改包名后会作为新应用安装，旧版数据与授权不会自动迁移。

## 下载与安装

1. 从 [GitHub Release v0.1.0](https://github.com/TobiasYin/loki/releases/tag/v0.1.0) 下载已签名的 [`loki-v0.1.0.apk`](https://github.com/TobiasYin/loki/releases/download/v0.1.0/loki-v0.1.0.apk)，按 Android 系统提示安装。发布页同时提供校验和与发布清单。
2. 首次安装后打开 Loki，按下文完成模型配置，再按需要开启语音、搜索、无障碍和 Shizuku。

公开安装包使用长期发布签名，与此前开发 debug 包的签名不同；已有开发安装请先查看 [v0.1.0 下载与签名说明](docs/releases/v0.1.0.md#下载与签名)，保留数据并处理迁移，不要直接卸载清空。

本指南从打开已安装的 APK 开始。操作入口按 2026-10-20 的版本整理，平台价格、免费模型和额度以各平台当时的页面为准。

## 先看 Loki 能做什么

| 日常场景 | 可以怎么说 | 需要的能力 |
| --- | --- | --- |
| 牌组卡住了 | “看看我上屏的牌组，之后的成长方向如何？” | LLM、上屏感知、无障碍；读取游戏画面需模型支持图片 |
| 找一款新游戏 | “在 Play 商店安装排名第一的游戏。” | LLM、屏幕操作、无障碍；安装行为按系统提示确认 |
| 屏幕总是很快熄灭 | “帮我看看自动熄屏为什么这么快，并调成十分钟。” | LLM、屏幕操作；进阶诊断可用 Shizuku / ADB |
| 玩游戏时调掌机 | “把上屏亮度调到 60%，下屏 30%，风扇调成高档。” | LLM、Shizuku、对应设备工具权限 |
| 让下屏跟游戏更搭 | “给星露谷物语配置一个像素风的聊天背景。” | LLM；也可在聊天背景设置手动配置 |
| 查最近的攻略 | “搜索这个版本的新手攻略，给我来源链接。” | LLM、联网搜索服务 |

## 视频教程与跳转时间

配套教程《Loki 安装配置 · 新手教程》时长约 6 分 31 秒，章节如下：

| 时间 | 内容 |
| --- | --- |
| 00:00 | 本期内容：安装与配置 Loki |
| 00:09 | 首次打开与三类 Key |
| 00:37 | 三种推荐方案 |
| 01:35 | 掌机内配置 Zen、语音与 Tavily |
| 02:21 | 掌机内配置 AgentPlan |
| 02:38 | 掌机内配置 DeepSeek 官方 |
| 02:53 | 无障碍与 Shizuku 授权（只需配置一次） |
| 03:40 | 第一次对话与能力开关 |
| 03:57 | 全免费方案：注册 Zen → 豆包语音 → Tavily |
| 05:47 | 省事方案：AgentPlan 专属 Key 获取入口 |
| 06:03 | 高速方案：DeepSeek 官方 Key 获取入口 |

## 第一次打开：先连 AI，再开权限

首次打开会进入两步使用引导。完成后，可从会话侧栏底部的“设置 → 使用引导”再次进入。

第一步是连接 AI 服务。你会看到三类配置：

| 配置 | 是否必填 | 用途 |
| --- | --- | --- |
| LLM 模型 Key | **必填** | 让 Loki 理解问题、回答和规划操作。没有模型服务就不能开始使用 |
| 语音 Key | 选填 | 按住说话，把语音识别为文字；不配置仍能打字 |
| 搜索 Key | 选填 | 联网查询信息；不配置仍能使用模型已有知识 |

这三类能力不一定要三个不同的 Key。AgentPlan 可以共享一个套餐专属 Key；DeepSeek 官方可以共享一个模型 Key 给模型和搜索。

### 选一个适合你的方案

| 方案 | LLM | 语音输入 | 联网搜索 | 适合谁 |
| --- | --- | --- | --- | --- |
| 全免费方案 | OpenCode Zen 当前免费模型 | 豆包语音试用额度 | Tavily 免费额度 | 先体验、愿意分别注册三个服务的人 |
| 省事方案 | 火山 AgentPlan | 共享 AgentPlan Key | 共享 AgentPlan Key，启用豆包搜索抵扣 | 想尽量少填配置的人 |
| 高速方案 | DeepSeek 官方充值 API | 豆包语音试用额度 | 共享 DeepSeek 模型 Key，按官方搜索计费 | 希望使用 DeepSeek 官方接口的人 |

“全免费”指在各平台免费模型、试用资格和免费额度内使用，并非永久无限量。Zen 部分模型为限时免费，也可能存在地区和出口限制；Tavily 当前免费档为每月 1,000 API credits、无需信用卡，credits 不等于固定的搜索次数。豆包语音的试用资格、余量和到期时间请在控制台查看，开通后付费服务后可能产生费用。[Zen 免费模型](https://opencode.ai/v2/docs/console/models/#free-models)、[Tavily 价格](https://www.tavily.com/pricing)、[豆包语音计费](https://docs.volcengine.com/docs/DoubaoVoice/BillingOverview-15?lang=zh)。

AgentPlan 官方活动页目前写“限时 9.9 元起”，请以订购页显示的档位、周期、优惠资格及续费价为准。一个 Key 共用的是账号和套餐额度，不代表所有模型都可无限调用。[AgentPlan 官方活动与价格](https://www.volcengine.com/activity/agentplan)。

## 在 Loki 中填写 Key

### 全免费方案：Zen + 豆包语音 + Tavily

1. 在使用引导点“连接 LLM 模型”，进入“模型服务 → 添加模型服务”。
2. 选择 **OpenCode Zen（免费模型）**，填写从 OpenCode Console 获取的 API Key。
3. 停止输入后等待免费模型列表自动刷新；也可点“刷新 Zen 免费模型”。选择列表中可用的模型，点右上角“保存”，并使用该服务。不要把 OpenCode Go 的付费订阅 Key 配置入口与 Zen 免费入口混用。
4. 返回引导，进入“语音输入”。选择“新版 Key”，填写**豆包语音控制台**的 API Key，选择你已开通的流式语音识别版本和计费方式，例如“2.0 时长”，保存。旧版服务可选“旧版 Token”，再填 App ID 和 Access Token。
5. 进入“联网搜索”，选择 **Tavily**，填写 Tavily API Key，点保存。可使用连接测试确认当前配置。

语音页的“检查当前配置”只检查字段是否齐全；实际识别需保存后在聊天中试用。首次按住话筒时，按系统提示允许麦克风。

### 省事方案：AgentPlan 一个 Key

1. 使用引导可直接点“配置火山 AgentPlan”；也可从“模型服务 → 添加模型服务 → 火山 AgentPlan”进入。
2. 填写 **AgentPlan 个人版套餐专属 API Key**，选择当前套餐可用的模型，点“保存”并使用该服务。普通方舟按量 Key、火山 AK/SK 与套餐专属 Key 不能混用。
3. 语音默认跟随当前 AgentPlan，无需另外填语音 Key；不要勾选“始终使用独立语音凭据”。
4. 搜索选择“自动选择”或“豆包搜索”，并在火山控制台按指南开启**豆包搜索抵扣**。如果控制台未开通，正确的 Key 也可能无法搜索。
5. 使用模型连接测试、搜索连接测试分别检查；模型目录能显示并不等于当前档位拥有全部模型权限。

切换到其他模型服务后，独立语音配置继续保留；共享来源以当前界面显示为准。

### 高速方案：DeepSeek 官方 + 豆包语音

1. “模型服务 → 添加模型服务 → DeepSeek 官方”，填写官方 API Key。
2. 可展开“高级选项”刷新官方模型列表，选择模型并保存、使用此服务。官方地址由预设填写为 `https://api.deepseek.com`。
3. “联网搜索”选择“自动选择”或 **DeepSeek**，即可共享已配置的官方模型 Key，不用再创建 Tavily Key。搜索调用另按 DeepSeek 官方规则计费，充值余额同时用于相关 API 消耗。
4. 语音按上面的独立豆包语音流程配置；DeepSeek 模型 Key 不能直接填到语音栏。

“高速”是方案名称；实际速度取决于模型、思考强度、服务负载和网络。[DeepSeek 搜索 API 说明](https://api-docs.deepseek.com/guides/search)。

## 第二步：无障碍和 Shizuku

### 开启无障碍服务

1. 在使用引导第二步点“开启无障碍”。已经跳过引导时，入口是“设置 → 设备与调试 → 打开系统无障碍设置”。
2. 在 Android 无障碍页面找到 **Loki**，进入后打开“使用服务”，阅读系统说明并允许。
3. 返回 Loki，确认界面显示服务实际已连接。单纯看到系统开关为开，不一定代表连接正常。
4. 在“设备与调试”核对上下屏映射，保证游戏是上屏、Loki 聊天在下屏。

无障碍用于读取界面和执行定向操作。只想文字聊天时，可以稍后再开。若提示“连接失效”，先在系统页面关闭再重新开启 Loki 服务，然后回到应用确认。

### 启动并授权 Shizuku

Shizuku 用于进阶设备操作和 Shell 诊断，**不等于 Root**。先安装官方 Shizuku，并启动服务，再回到 Loki 授权。

无线调试启动（Android 11 及以上）：

1. 让掌机接入 Wi-Fi，按系统提示开启开发者选项中的“无线调试”。
2. 在 Shizuku 选择“通过无线调试启动 → 配对”，按页面指引输入 Android 显示的配对码。
3. 返回 Shizuku，点击“启动”，确认状态为“正在运行”。不同 Thor 固件的菜单位置可能略有差异。
4. 返回 Loki 的使用引导，点“授权并绑定 Shizuku”；或在“设备与调试”点“请求/绑定 Shizuku（shell UID）”。在 Shizuku 授权弹窗中允许 Loki。
5. 回到 Loki，确认 Shizuku 服务和授权状态正常。授权后 Loki 可自动开启自己的无障碍服务，仍要确认实际连接。

也可按 Shizuku 应用内显示的命令，使用电脑 ADB 启动。掌机重启后通常要重新启动 Shizuku 服务。具体步骤见 [Shizuku 官方使用手册](https://shizuku.rikka.app/zh-hans/guide/setup/)。

## 开始第一轮对话

完成引导后，新建会话，在输入区选择模型，先发送“你好，简单介绍一下你能帮我做什么”。得到回答后，再开启需要的能力。

| 会话能力 | 用途 | 建议 |
| --- | --- | --- |
| 上屏感知 | 读取上屏应用、UI 和截图 | 想让 Loki 看游戏时开启；图片内容需要视觉模型 |
| 联网搜索 | 查询实时信息与攻略 | 配置搜索服务后按需开启 |
| 屏幕操作 | 点击、滚动、拖动与输入 | 让 Loki 帮你操作应用时开启 |
| ADB 调试 | 设备状态、设置与诊断 | Shizuku 启动并授权后，按任务需要开启 |

这些开关表示本会话允许使用的能力，不能代替 Android 的系统权限。需要确认的操作会显示确认卡；在执行过程中可以停止任务。不要在不想让模型看到的敏感页面开启屏幕观察。

输入方式：直接打字，或配置好语音后切换话筒模式，**按住说话、松手发送、上滑取消**。“＋”可以添加图片或选择 Skill。会话侧栏可以新建和切换多个对话。

外观入口：“设置 → 页面配色”切换亮色、暗色或跟随系统；“设置 → 聊天背景”选择纯色、图片或系统壁纸，也能为上屏的不同应用配置专属背景。

## 平台账号注册与获取 Key

以下步骤在浏览器完成；Key 获取后只粘贴到 Loki 对应服务中。不要把 Key 发到评论、截图或公开文档。

### OpenCode Zen

1. 打开 [OpenCode Console](https://opencode.ai/console)，选择创建账号。可使用邮箱或页面提供的第三方登录方式。
2. 邮箱注册时填写自己的邮箱、设置符合页面要求的密码，并按提示完成邮箱验证。密码由本人保管。
3. 进入自己的 Console 工作区，点击右上角账号菜单 → **Account → Personal API keys → Add API key**。选择自己的 Workspace，填写便于识别的名称，例如 `Loki-Thor`；Expiration 选 **Never（不过期）**，Permissions 选 **All**，再点 Create。
4. 创建后复制 Key，回到 Loki 选择“OpenCode Zen（免费模型）”粘贴并等待目录刷新。免费模型以实际列表和平台规则为准。
5. 如果平台提示免费层限制，先核对账号、地区、额度及官方状态；可切换其他可用模型，或改用其余方案。

不要为了使用免费模型误选充值或付费订阅。需要收费的操作由用户自行确认。

### 火山引擎豆包语音试用

1. 打开 [豆包语音控制台](https://console.volcengine.com/speech/app)，注册或登录火山引擎账号。当前注册页面要求手机号，账号注册方式也包含手机验证；仅有邮箱不能独立完成该流程。
2. 按平台要求完成实名认证后，进入豆包语音服务。在正确项目中查看试用入口和余量；同一项目的服务和 Key 要对应。
3. 在“开通管理”或新版“API 服务中心”选择**流式语音识别**，按页面说明领取可用试用额度，确认版本为 1.0 或 2.0。录音文件识别与流式识别是不同能力，Loki 按住说话使用流式识别。
4. 在“API Key 管理 / API 管理”创建或复制语音 API Key。当前官方提供的[新版 Key 管理入口](https://console.volcengine.com/speech/new/setting/apikeys?projectName=default)可直接进入默认项目。
5. 返回 Loki → 语音输入 → 新版 Key，粘贴 Key，选择对应识别资源并保存。比如开通 2.0 按时长服务，就选“2.0 时长”。
6. 在控制台查看试用到期时间和余量。不要把“领取试用”与“正式开通后付费”混为一步；余额、自动扣费及用量以控制台为准。

参照 [豆包语音新版控制台快速入门](https://docs.volcengine.com/docs/DoubaoVoice/QuickStartNewConsole?lang=zh)及 [Key 使用说明](https://docs.volcengine.com/docs/DoubaoVoice/APIKeyUsage?lang=zh)。

### Tavily 免费搜索

1. 打开 [Tavily 控制台](https://app.tavily.com)，点击“Sign up”。
2. 使用邮箱注册，或选择页面提供的第三方登录方式。阅读页面的 Terms of Service 和 Auto-Renew 条款后，由本人确认继续；按提示完成邮箱验证和必要的账号设置。
3. 进入 Dashboard，查看当前为免费计划及可用 credits。在 API Keys 区域找到默认 Key，或创建一个名为 `Loki-Thor` 的 Key，再点击复制。
4. 返回 Loki → 联网搜索 → Tavily，粘贴 Key、保存并测试。API Key 只给 Tavily 栏，不填到模型服务。
5. 免费额度不需要信用卡。只想免费使用时，不必添加付费方式或升级到付费计划。

### DeepSeek 官方 API

1. 登录 [DeepSeek API Keys](https://platform.deepseek.com/api_keys)。
2. 点击页面右上方的 **“创建 API key”**，设置名称并创建。Key 仅在创建时完整显示，立即复制并妥善保存。
3. 在官方平台按需充值，然后回 Loki → 模型服务 → DeepSeek 官方，粘贴 Key，选择模型并保存。
4. 搜索选自动选择或 DeepSeek。语音仍使用独立豆包语音 Key。

### 火山 AgentPlan 专属 Key

1. 打开 [AgentPlan 我的订阅](https://ark.volcengine.com/region:cn-beijing/subscription/agent-plan)，登录持有套餐的火山账号。
2. 确认当前套餐、有效期和额度；计划购买时核对实际价格、自动续费与套餐权益。
3. 在“我的订阅”切换到 **“使用配置”** 标签。
4. 找到 **“专属 APIKey”**，点击旁边的复制图标。使用这里的套餐 Key，而非通用 API Key 或 AK/SK。
5. 在 Loki 选择火山 AgentPlan，粘贴后选模型并保存；按控制台说明开通豆包搜索抵扣，即可共用模型、语音和搜索。

## 常见问题

| 现象 | 优先检查 |
| --- | --- |
| 不能进入下一步 | 是否已保存并选用了带 Key 的启用模型服务；只填草稿还不算保存 |
| 模型列表有，但请求失败 | 余额、当前套餐权限、免费层额度、网络和服务状态；保存不等于真实请求成功 |
| 能聊天但看不到上屏 | 无障碍实际连接、上下屏映射、上屏感知开关、模型是否支持图片；系统安全页面可能禁止截图 |
| 能看屏幕但不能帮忙点 | 屏幕操作开关、无障碍服务、目标应用当前窗口是否可操作 |
| 按住话筒没反应 | 语音 Key / AgentPlan 来源、流式识别资源版本、试用额度、麦克风权限 |
| 搜索不可用 | 搜索服务是否保存、会话搜索开关、Key/额度；AgentPlan 另检查豆包搜索抵扣开通状态 |
| 亮度或风扇调整失败 | Shizuku 是否运行、Loki 是否获授权、会话 ADB 开关；不同设备支持范围不同 |
| 重启掌机后进阶操作失效 | 重新启动 Shizuku，并返回 Loki 确认绑定状态 |

## 开发与详细说明

开发环境、构建命令和历史验收说明见下方“开发、构建与验收”。更细的能力说明：[模型与设置](docs/settings.md)、[AgentPlan 接入](docs/volc-agent-plan.md)、[Zen 免费模型](docs/opencode-zen.md)、[语音与 Skill](docs/voice-skills-streaming.md)、[下载与安装](docs/download-install.md)。

## 开发、构建与验收

<details>
<summary>展开开发环境、构建命令及历史验收说明</summary>

Loki 是面向 AYN Thor 掌机的 Android AI Agent：本地 WebView 聊天与锁定版本 Pi，模型协议由 JS 处理，网络与设备授权由 Native 执行。五个独立任务的精确提交已保留历史整合，正式入口已连接真实 Runtime、通用 Native Fetch、持久化与设备 ToolPack；不返回模拟成功。

已完成一轮 **Thor 真机测试与修复**：真实 OpenCode Go Responses、Brave 搜索、完整下屏输入法、Shizuku 自动无障碍、手指和实体手柄接收基线，以及真实模型的逻辑坐标/截图坐标触摸已有实测证据，见 [实机测试报告](docs/physical-test-report.md)。0.6 新增固定 evdev 原生手柄按钮与六轴后端，真实身份、归零与停止验收进度见 [0.6 手柄说明](docs/release-0.6-gamepad.md)；完整故障矩阵和真实模拟器私有配置权限仍未完成，不能宣称所有游戏兼容或完整四能力产品发布就绪。此前本地受控模型服务、JVM、DOM 与 Android AVD 的历史证据见 [中文软件集成报告](docs/integration-report.md)。

0.5.0 的紧凑输入区、完整设置页、OpenCode Go 模型/思考强度选择与多会话并行见 [本轮界面与模型配置说明](docs/composer-models-parallel.md)。新会话继承上次选择的能力、模型、思考强度和 Skill；下表是首次安装的初始默认值。本轮 0.6 已恢复并完成专项实机回归，详情见 [0.6 验收记录](docs/release-0.6-acceptance.md)。

| 能力 | 新 Session | 当前实现与限制 |
|---|---|---|
| 上屏感知 | ON | 真实设备信息；解析或校准显示角色并开启 Accessibility 后提供应用/窗口/精简 UI 树/截图，支持暴露离屏文字查找；每次模型请求仅注入最小上下文 |
| 联网搜索 | ON | curl 等价 HTTP 请求、持久文件下载与完整性校验；Brave 独立 Native 凭据和端点；未配置时不发布搜索；HTTP/HTTPS 网页按系统 DNS/代理访问，支持内网与代理 fake-IP；服务凭据分别绑定端点 |
| 屏幕操作 | OFF | Accessibility 定向触摸、容器滚动、拖动及过期目标拒绝；已授权 Shizuku 下的固定 evdev 有界手柄按钮/六轴，要求真实设备与上屏焦点验证 |
| ADB 调试 | OFF | Shizuku shell UID、Native 白/灰/黑名单；已下载 APK 的逐次确认安装与版本回验；结构化文件只允许明确的应用私有合成配置目录，写前备份、hash 冲突、读回及恢复 |

0.7 增加设置、视觉游戏、新闻列表、长文四组多步演练，以及精简组件树、离屏文字查找、语义滚动和滑块拖动。双屏亮度工具统一为 0–100 整数百分比，调用和显示使用同一单位；工具过程按展开层级延迟渲染，长上下文通过独立数据体保存，避免大工具记录阻塞任务。实现与实测结果见 [0.7 场景与亮度说明](docs/release-0.7-scenarios.md)。

ADB 调试采用 Native 白/灰/黑名单：限定参数的状态读取、亮度、风扇和刷新率直接执行；含隐私的诊断或按键分配变更逐条 UI 确认；抹除、格式化、刷机、提权和未知命令禁止。执行已审查 argv，不开放任意脚本。Shell 不等于 Root。Thor 双屏与常用配置手册作为内置 adb-operations Skill 按需加载。

下载/安装工具、边界与真实模型的模拟器验收见 [网络请求与 APK 安装说明](docs/download-install.md)。

## 全项目入口

环境：JDK 17、Node ≥22.19、Android SDK Platform 35 / Build Tools 35.0.0。Gradle 8.14.3（wrapper 校验）、AGP 8.10.1、Kotlin 2.2.10、AndroidX WebKit 1.14.0；Pi agent-core/pi-ai 1.2.0、TypeScript 6.0.2、TypeBox 1.3.27。依赖锁和根级 SHA-256 verification metadata 一并保存。

```sh
cd path/to/thor-agent
export JAVA_HOME="$(cd path/to/jdk-17 && pwd)"
export ANDROID_HOME="$(cd path/to/android-sdk && pwd)"
npm run install:deps
npm run build
npm run verify
```

npm 依赖统一使用公共源 `https://registry.npmjs.org/`，模块内单独安装也使用同一源。文档中的 JDK/SDK 示例路径相对于执行命令的目录填写；环境变量通过 `pwd` 转为工具要求的路径。`npm run verify:public` 可独立检查开发机路径、npm 配置和三个锁文件，完整验证也会执行这项检查。公开前的检查结果及剩余事项见 [GitHub 公开检查记录](docs/public-release-review.md)。

正式 Agent 必须从根工程构建。`app/` 原独立模拟构建入口会明确提示改用根工程；原 Coordinator/模拟端口仅保留给原测试，不在产品入口使用。其他独立工程保留用于模块诊断；软件集成以根工程为准。

APK：

- Agent debug：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.tobias.loki`。
- Agent unsigned release：`app/build/outputs/apk/release/app-release-unsigned.apk`。Gradle 默认输出未签名包；[GitHub Release](https://github.com/TobiasYin/loki/releases/tag/v0.1.0) 提供使用独立长期发布密钥签名的可安装 APK，签名私钥与密码不进入仓库。
- Input Lab debug：`tests/input-lab-app/app/build/outputs/apk/debug/input-lab-debug.apk`，包名 `dev.thor.inputlab`，独立 UID/进程。
- Input Lab unsigned release：同目录 `release/input-lab-release-unsigned.apk`。
- 诊断宿主：`tools/diagnostic/build/outputs/apk/debug/diagnostic-debug.apk` 与 `native-bridge/host/build/outputs/apk/debug/bridge-host-debug.apk`。

`build/integration/artifacts.json` 保存本次实际 APK 大小与 SHA-256。`scripts/check-artifacts.py` 检查真实 APK 的 manifest、权限、JS bundle 和 debug/release 隔离，不只看源码。

## 正式使用流程

1. 设置中选择 OpenCode Zen（免费模型）、OpenCode Go 或 DeepSeek 官方预设，填写对应 API Key，再从模型列表选择。Zen 填 Key 后自动拉取免费模型，并按所选模型的总上下文、独立输入上限和输出容量管理预算与历史压缩，详见 [OpenCode Zen 配置说明](docs/opencode-zen.md)。OpenCode Go 结合服务模型列表和模型 profile 拉取能力、思考参数与上下文预算。也可选择自定义提供商，填写协议、基础 URL、模型 ID 和 Key，以及通用凭据 header 名称和含 `{{credential}}` 的模板。真实 Key 不返回 JS。保存更新配置修订并使旧探测失效。
2. 可在设置点击“测试当前已保存模型”，经真实 Pi 和 Native Fetch 发送合成图片、调用探测工具并回传图片。Native 要求当前修订、实际完成的 HTTP、Native 工具和图片读取证据。测试会调用服务并可能计费。普通 UI 没有设置 passed 的入口。
3. 已知官方预设选择模型后即可发送，自定义提供商仍需真实探测通过。输入框中可直接切换模型、思考选项和能力；“＋”提供附图和 Skill 选择。原生附图经系统文档选择器导入，图片保存在 Native，并与下一条消息关联；每条最多两张，图片解码/上传有界。用户发起的任务由前台服务在后台继续执行，离开或重建聊天 Activity 不清除运行和 Pi 上下文；停止、授权变化、执行页面连接或 renderer 失效会撤销旧 lease，不自动重放。通知栏可停止所有任务。
4. 已授予 Shizuku shell 权限时，应用自动开启自身无障碍并保留其他服务；没有该权限时可从设置手动开启。选择本次上下屏映射，可将聊天移动到所选下屏；会话的屏幕操作、调试开关仍默认关闭。灰名单命令/配置文件写入的批准卡绑定具体操作或完整差异；亮度、风扇、刷新率等白名单调整直接执行；拒绝、停止、hash 冲突都不能写入。

已实测模型配置：协议 `OpenAI Responses`，基础 URL `https://opencode.ai/zen/go/v1`，模型 `deepseek-v4.1-flash`。OpenCode 所需会话路由头由 Native 自动安装，Key 仍仅保存在 Native。

按用户要求，debug/release 均支持 HTTP/HTTPS、公网/内网/回环及代理 DNS，不限制地址段；使用系统 DNS 与代理。HTTPS 正常验证证书，模型/搜索凭据绑定各自配置的端点。release 不携带受信 receiver 测试权限或 runner provider。

0.8 的官方模型预设、现代输入区、消息展开动画、速度优化、后台运行、双屏工具和真实游戏长流程进度见 [0.8 变更与验证边界](docs/release-0.8-progress.md)。

## 默认开发与模拟器验收

后续开发任务默认使用项目专用 AVD **`thor_agent_dual_api36`**（默认序列号 **`emulator-5560`**）：上屏 **display 0 / 1920×1080**，下屏 **display 4 / 1240×1080**，均为 **369 dpi**。聊天主界面运行在下屏 display 4。开发代理和人工开发均按根目录 [AGENTS.md](AGENTS.md) 执行，配置及恢复方式见 [专用双屏模拟器说明](docs/project-thor-emulator.md)。

```sh
# 创建或复用项目专用 AVD，并核对屏幕 ID、尺寸与密度
python3 scripts/thor-emulator.py

# 安装与运行；每条 ADB 命令明确指定目标设备
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 shell am start --display 4 -n com.tobias.loki/dev.thor.agent.MainActivity
```

涉及 Android 界面或设备行为的变更，默认在这套双屏环境完成验收，并保留原有数据和凭据。历史测试脚本若固定为其他屏幕角色，应先适配参数或实现，再用于当前默认环境。真机专项测试按用户要求单独执行和记录，模拟器结果不能替代硬件验收。

历史受控服务 runner [emulator-verify.py](scripts/emulator-verify.py) 及历次 [软件集成报告](docs/integration-report.md)、[实机报告](docs/physical-test-report.md) 保留各自测试条件，不改变本节默认开发环境。

该 runner 只运行明确选定的软件测试类，覆盖模型协议与网页桥接、Skill 页面、语音模式、SQLite 和生命周期，不自动发现真机风扇/亮度等硬件测试。模型和搜索使用本机受控服务；匿名 HTTP 跨端口跳转用于检查 Android 原生桥接与无凭据请求。结束时移除测试端口转发并恢复无障碍设置。

runner 只通过 signature 保护的 debug provider 准备/读报告，经真实 Pi → Native → Accessibility → Android dispatch 施加输入；没有 setPassed/事件回放入口。准备和报告通道不注册为模型工具。receiver 的 `unqualified-physical-baseline-pending` 保持不变，不把 AVD 结果冒充 Thor POC-11。

## 架构与规格

[需求](docs/requirements.md) → [架构](docs/architecture.md) → [网络](docs/network-models.md) → [工具](docs/tool-system.md) → [安全](docs/security.md) → [会话](docs/session-context.md) → [Input Lab](docs/input-lab.md) → [测试](docs/testing.md) → [实机 PoC](docs/poc-plan.md)。早期规划及五个模块旧证据是历史材料；当前实现状态与本次实际验证以集成报告为准。

## 0.3.0：语音、Skill 与结构化过程

输入区支持火山双向流式语音：切换语音模式、按住说话、松手等待最终结果后发送，上滑取消；识别中的文字会实时替换更新。语音凭据在独立的 Native 语音设置中加密保存。

侧栏的 Skill 管理支持导入 SKILL.md 或单个 Skill 的 ZIP，查看/启停/删除；Agent 自带 skill-creator 与 skill-manager，可在用户要求时提出创建和管理操作。模型默认只看到名称和简介，正文与引用资料必须通过读取工具按需加载。思考、回答与工具调用分别解包和聚合，页面以思考面板和工具卡显示。

配置方法、按需加载边界与测试范围见 [功能说明](docs/voice-skills-streaming.md)；[本轮实机证据](tests/feature-evidence/README.md)独立于之前的输入设备验收。0.3.1 已配置语音凭据并通过真实火山识别与真机麦克风到 Agent 的端到端验收，见 [云端语音验收](docs/cloud-voice-acceptance.md)。火山 TTS 仅用于电脑端预生成离线测试 WAV，不进入 APK。

</details>

## 许可证

本项目原创代码采用 [MIT 许可证](LICENSE)，版权声明为 `Copyright (c) 2026 Tobias and contributors`。第三方依赖、随附上游代码及第三方素材遵循各自的许可证或权利声明；其现有声明保留在对应文件和目录中。
