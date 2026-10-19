# AgentPlan 与项目双屏模拟器验收

2026-10-19。仅在模拟器运行本轮验收；Thor 只用于读取屏幕参数，没有安装或执行本轮真机测试。

专用 AVD 为 `thor_agent_dual_api36`，序列号 `emulator-5560`，Android 16 / API 36 / ARM64。上屏 display 0：1920×1080，下屏 display 4：1240×1080，均为 369 dpi。已实际关闭 AVD，再通过 `scripts/thor-emulator.py` 冷启动，脚本自动重建并核对 0 / 4。随后安装最终 Debug 包，通过独立仪器用例确认：原 Keystore Key 保留，模型、语音和搜索仍解析到同一个服务凭据，聊天界面确实位于 display 4。

## 服务链路

同一套餐 Key 只保存到当前 Provider 的 Native 加密条目，临时输入文件导入后删除。测试报告与截图不包含 Key。模型目录来自官方公开配置，在线发现 13 个条目，验证模型选择器可切换且 Key 不回填。目录内容不等于当前账号每个模型都已获得权限。

| 检查 | 验证范围 |
| --- | --- |
| 文本连接 | `deepseek-v4.1-flash`，套餐 Responses 入口，真实 SSE 文本增量 |
| 搜索连接 | 当前套餐 Key 调用豆包搜索，验证官方结果结构 |
| 完整工具循环 | 真实模型通过产品 `web.search` 工具取得搜索结果，任务 completed |
| ASR 2.0 | 固定公开合成 PCM 经模拟器网络发送到套餐 async 入口，松手前未提交，松手后识别出预期短语并释放输入源 |
| TTS 2.0 | 套餐入口返回完整 PCM，通过模拟器 AudioTrack 播放；完成事件可携带音频数据，已增加回归覆盖 |
| 语音设置 | 清楚显示共享当前 AgentPlan Key；共享模式隐藏独立语音凭据表单；提供显式合成播放入口 |
| 冷启动 | 两个屏幕 ID、分辨率、密度、当前 Provider 与加密 Key 均保留或按脚本恢复 |

ASR 使用已知合成输入，未采集物理麦克风；TTS 播放通过不代表音质的主观评测。没有逐个实测全部 13 个模型，也没有将屏幕尺寸对齐视为 Thor 固件、HDR、120 Hz、手柄输入或双屏设备控制全部等价。Thor 当前 Android 13，与本机现有 API 36 镜像版本不同。

## 自动化检查与交付

最终 Debug 包构建、259 项 App JVM 单元测试、390 项 Runtime 测试、Android Lint、集成 TypeScript 检查与搜索协议检查通过。模拟器的真实服务用例与冷启动凭据 / 界面用例分别保留报告。包内包含火山模型目录与套餐 TTS 入口，未打入离线 WAV 样本或普通 TTS 计费入口。

清理经用户授权的下载和构建缓存后，干净构建暴露 3 个原来缺失的上游 POM/module 校验条目；已与 Maven Central 官方文件及校验文件逐项比对后补充，未修改依赖版本。原有共享模拟器中本轮临时添加的 Provider / Key 已删除，专用模拟器保留可用配置。

证据在 [tests/agentplan-emulator-evidence](../tests/agentplan-emulator-evidence)，包括服务结果、最终仪器日志、冷启动日志、屏幕与凭据核对、语音页截图和最终 APK / 源文件哈希。启动方式见 [项目专用 Thor 双屏模拟器](project-thor-emulator.md)，配置方式见 [AgentPlan 单 Key 接入](volc-agent-plan.md)。
