# 文档索引与维护规则

日期：2026-10-13。本文档集是设计基线 v0.2（加入独立上屏测试 APK），而非发布版本。

## 证据等级

- **[需求]**：用户明确要求，约束产品目标。
- **[决定]**：本次规划采用的方案；实现前仍需评审，条件性方案注明门槛。
- **[事实]**：由官方文档或本次读取的官方源码支持。注明出处与版本。
- **[待验证]**：Thor 固件、具体 WebView 或用户网关上尚未证实。
- **[不承诺]**：超出当前证据或首版边界的能力。

未单独标记的工具名、字段、错误码、时限、UI 行为均为 Thor 拟议契约，不是 Android/Pi 官方 API。每个 PoC 状态目前均为“未执行”。

## 文件导航

| 类别 | 文件 | 负责回答 |
|---|---|---|
| 产品 | [product-scope](product-scope.md)、[requirements](requirements.md) | 为谁做、做什么、怎样验收 |
| 架构 | [architecture](architecture.md)、[technology](technology.md) | 分层、依赖方向、替代方案 |
| Agent | [pi-webview](pi-webview.md)、[session-context](session-context.md) | Pi 接入、运行事件、上下文 |
| 真机续测 | [0.5.0 真机验收](physical-resume-2026-10-16.md) | 下屏 IME、会话/Skill、上屏接收与真实下载安装 |
| 语音修复 | [0.5.1 长按录音修复](voice-hold-fix.md) | 触点归属、捕获丢失、真实按住/松手与火山识别 |
| Skill 验收 | [Agent 创建与复用攻略 Skill](skill-agent-acceptance.md) | 真实模型检索、保存、独立会话匹配与按需读取 |
| 工具 | [tool-system](tool-system.md)、[perception-tools](perception-tools.md) | 工具契约、注入和撤销、按需感知 |
| 平台 | [displays-input](displays-input.md)、[debug-files](debug-files.md) | 双屏、触摸、手柄、Shell、文件 |
| 网络 | [network-models](network-models.md)、[web-search](web-search.md) | 模型协议边界、通用传输、搜索后端 |
| 体验 | [ui-ux](ui-ux.md)、[settings](settings.md) | 页面、状态、权限配置 |
| 数据 | [storage](storage.md)、[security](security.md) | 持久化、凭据、信任边界 |
| 测试接收端 | [input-lab](input-lab.md) | 独立上屏 APK、手动 API/Agent 共用场景和证据 |
| 交付 | [poc-plan](poc-plan.md)、[testing](testing.md)、[roadmap](roadmap.md) | 可行性门槛、测试与顺序 |
| 治理 | [ADR](adr/README.md)、[open-questions](open-questions.md)、[references](references.md) | 决策、风险、官方依据和修正 |

本次落盘范围与核查记录：[交付记录](delivery-report.md)。

## 文档变更

接口细节以专项文档为准；README 只提供概览。新增工具必须同步权限矩阵、错误及取消语义、测试，不得只新增提示词。任何实机结论需记录 Thor SKU、Android/API、固件 build、WebView 包名/版本、权限状态、应用版本、步骤、脱敏证据和日期。

不复制完整官方文档；参考资料表集中维护链接，专项章节就近引用。Pi 基线锁定源码提交，网页文档记录访问日期，后续升级需要重跑相关验证。数值预算均为初始设计目标，不是测量结果。

提交消息按内容选择 `feat:`、`fix:`、`debug:`、`chore:` 等前缀。本次没有建立 Git 仓库或创建提交。

- [火山 AgentPlan 单 Key 接入](volc-agent-plan.md)：文本模型发现与选择、豆包搜索、ASR、TTS。
- [项目专用 Thor 双屏模拟器](project-thor-emulator.md)：display 0 / 4、Thor 对应分辨率与密度、可重复启动脚本。
- [AgentPlan 模拟器验收](agentplan-emulator-acceptance.md)：真实套餐服务闭环、冷启动与单 Key 持久化、验收边界。
