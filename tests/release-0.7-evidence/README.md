# 0.7 多步上屏与百分比亮度验收证据

2026-10-16，AYN Thor / Android 13，上屏逻辑 display 0、下屏 display 4。主版本 0.7.0-integration（code 8），Input Lab 0.4.0（code 5），Shizuku UserService version 11。显示编号来自本轮实际枚举，产品通过显示角色解析，不将其写死为通用硬件标识。

完整结论见 [0.7 说明](../../docs/release-0.7-scenarios.md)。本目录仅保存本地合成页面、专项运行及软件验证；完整用户数据库、原始恢复备份、凭据和 APK 二进制均留在忽略的 `build/physical/release-0.7-2026-10-16/`。

| 目录／文件 | 来源与证明范围 |
|---|---|
| [scenarios](scenarios/physical-scenario-flow.json) | `scenarios-06`，真实保存的 OpenCode Go Responses / deepseek-v4.1-flash，四个随机 seed、独立产品会话。设置进入子页、真实拖动并保存；游戏仅截图定位两关；首屏外新闻滚动后打开正确详情；长文实际滚到末尾并回答首尾码。四个 Run completed |
| [brightness](brightness/physical-brightness-percent.json) | `brightness-03`，真实模型先按需读 Skill，读两屏、上屏设 42%、读两屏、下屏设 37%、读两屏，无批准且没有 Shell 绕行。独立检查原始固件读回及屏幕互不影响，结束恢复原亮度与自动模式。测试旁证中固件值明确标注原始单位，不作为 Agent 的公开返回 |
| [components/large-context](components/large-context/physical-large-context.json) | `large-03`，实际 WebView/Native/SQLite 组件，无云调用。480253 字节 UTF-8 中文/emoji 请求经 base64 保存约 113 ms ACK；同会话 `begin` 经 Native→JS 完整恢复大 history 约 45 ms ACK。格式冲突立即拒绝且数据库未写入。此 Thor 不支持 ArrayBuffer，不冒充二进制实机验收 |
| [components/semantic-ui](components/semantic-ui/physical-semantic-ui.json) | `semantic-02`，手动测试权限的 API 组件。读取暴露的离屏目标、公开语义滚动直到可见；旧 nodeRef、系统栏坐标、切换应用后旧观察在输入前拒绝。无云模型，raw Android touch 为 0，滚动回调独立记录 |
| [components/gamepad](components/gamepad/physical-gamepad-component.json) | `gamepad-01`，手动测试权限组件，实际 Odin 按钮／六轴与两种中途停止，检查释放和归零。不是本轮模型操控手柄的新增证明，模型手柄任务的历史证据见 0.6 |
| [raw-integrity.json](raw-integrity.json) | 从本目录复制的原始事件独立重算封存前缀 SHA-256、序号连续性及无丢失／不完整标记 |
| [software](software/software-summary.json) | 295 JVM、46 Runtime JS＋44 App JS＋13 Bridge JS，共 103 JS；类型检查、指定 lint、debug/release 与 APK 内容隔离检查。UI 软件覆盖范围另见 [懒渲染说明](software/ui-lazy-rendering.md) |
| [cold-launch.json](cold-launch.json) | 最终安装包哈希与桌面冷启动的下屏 MainActivity 位置核对 |
| [preservation.json](preservation.json) | 私有前后快照比较：原会话/Run/消息/事件/附件保留，原内容核对；配置与凭据 XML 字节一致，原导入 Skill 元数据与文件一致 |
| [history](history/manifest.json) | 七轮失败／中止／部分通过历史的清单，保留错误归因及 APK 指纹，不把失败改为通过 |

`scenarios-06` 的主包 SHA-256 为 `43ba17870befd6a5b3bf98b223456006e40396c7f84deff82dfc460da9393d04`。随后只修正旧终态、无结果亮度调用的单位显示，并增强 Native→JS 大 history 返回测试；最终组件及亮度回归使用主包 `fed08226e9e113f52cb2ccf81e74643c76d8e195cbf0248b5b24da5489006af2`。两包原生感知、输入、亮度、模型和持久化逻辑相同。各轮 summary.json 记录实际使用的主包、Lab 与测试 APK 指纹，最终全部构建产物见 [artifacts.json](software/artifacts.json)。release 仍为 unsigned。

长文保留了一次 `TARGET_CHANGED`：旧目标在提交前被拒绝，模型重新观察后完成。不能把“四场景通过”解读成所有中间调用均无错误。模型没有任务总时间上限，场景 suite 共约 685 秒；仍保留工具、调用次数和数据体预算。

场景报告固定 `agentAnswerVerified=false`、`systemInjectionProven=false`、`receiverQualificationVersion=scenario-ui-observation-v1`；回答正确性由独立测试另外核对。原始输入日志不能认证操作者身份，也不提升原 Lab 的物理基线或全部游戏兼容资格。组件树只读取应用实际暴露的内容，不能读取未加载的虚拟条目、隐藏私密内容或密码；Canvas 仍需截图。
