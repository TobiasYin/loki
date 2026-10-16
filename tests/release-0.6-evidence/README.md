# 0.6 最终验收证据

2026-10-16，AYN Thor / Android 13，上屏逻辑 display 0、下屏 display 4。最终主 APK 为 0.6.0-integration（code 7），SHA-256 为 `2bdaa96e7382ad48e13529699db731c155d306d67eb98f18f2689a6502d9ba88`；安装包指纹与构建产物一致。完整结论见 [验收记录](../../docs/release-0.6-acceptance.md)。

| 目录／文件 | 归因与结果 |
| --- | --- |
| [receiver](receiver/physical-cloud-input.json) | receiver-06，真实 OpenCode Go Responses / DeepSeek V4.1 Flash，经产品权限链与独立接收端；逻辑点击、APP 信息＋全屏截图＋自主定位点击、A、六轴四项 pass。四个 Run completed，工具 error=null |
| [components/gamepad](components/gamepad/physical-gamepad-component.json) | component-03，测试权限下的原生组件，不是模型任务；四轮真实 Odin 身份、A down/up、六轴偏转/归零、两种中途停止全部通过。另有焦点解析回归；suite 共 2 tests |
| [components/settings](components/settings/physical-settings-draft.json) | 最终主包的当前草稿真实测试：Go 文本成功、无效模型拒绝、Brave 搜索成功，配置/凭据/会话数保持 |
| [components/voice-gesture](components/voice-gesture/physical-voice-gesture.json) | 最终包、实际平台触摸、真实产品 WebView；模拟 ASR 并截获发送。长滑仅待取消、滑回后松手发送、长滑松手取消、无重复和几何移动全部通过。截图使用真实帧同步和 PixelCopy |
| [voice](voice/physical-voice-agent.json) | 最终包实际扬声器→物理麦克风→火山 ASR→真实模型，回复“语音测试成功”，取消不发送。没有把离线 TTS 集成到 APK；归档副本省略无关的模型思考文本 |
| [components/target-guards](components/target-guards/physical-target-guards.json) | 越出可触范围、过期、切换 APP 的负向组件测试；目标代码与最终包一致。接收报告 fail/inconclusive 是预期，三种提交均被拒绝且 rawCount=0。该项在最终按钮改用 evdev 之前的 0.6 包上执行，没有把它冒充模型验收 |
| [software](software/jvm-test-counts.json) | 252 JVM、46 Runtime JS＋13 Bridge JS＋33 UI/启动 JS，零失败；构建、指定 lint、实际 APK manifest/资源隔离、Chrome 布局和交互记录 |
| [cold-launch.json](cold-launch.json) | 通过正式桌面 Launcher 冷启动，MainActivity 实际位于 display 4，不调用测试校准；稳定后已保存模型与语音会话可见。列出主包、测试包与 Input Lab 安装指纹 |
| [preservation.json](preservation.json) | 测试前后私有数据库比较：原会话/Run/消息/事件/附件无缺失，原消息/Run/事件/附件内容不变；仅通用会话标题回填。模型、语音和凭据 XML 字节一致，原导入 Skill 元数据和文件一致 |
| [history](history/) | 旧 InputManager 路径的按钮虚拟 deviceId=-1、严格组件失败及接收报告归档清单。旧失败留存，最终通过来自新目录，不通过放宽断言获得 |

`receiver` 与 `gamepad` 中每份报告配有原始 events.jsonl。只对报告封存的 `eventPrefixBytes` 前缀计算 SHA-256，并与 `eventPrefixSha256` 比较；完整性检查不忽略截断或丢失。结尾可能存在测试释放后的窗口诊断行，不能把全文件哈希当作封存前缀哈希。

实际声学测试使用仓库离线火山 TTS fixture `tests/fixtures/volc-voice/01-short-command.wav`，PCM16/16kHz/mono，3817 ms，PCM SHA-256 `10d4261f9ba53ce4690d594e7d23499869358fe296be2dbcbcf6526cf6654a4d`。

本轮测试未提升 `receiverQualificationVersion`、未声称 `systemInjectionProven`、未覆盖所有游戏与固件组合。服务被 SIGKILL 时的内核按键/轴释放保证不在本实现能力内。显示名称只用于当前设备角色提示，不证明同名外接屏的硬件身份。

私有备份、API Key、完整用户数据库和 APK 二进制不进入此目录。全部原始失败与私有恢复备份保存在忽略的 `build/physical/release-0.6-2026-10-16/`。Input Lab 遇到 50 份设备侧报告预算时，只在完整归档并验证前缀后移除明确的自动测试副本；实体手指与手柄基线保留。
