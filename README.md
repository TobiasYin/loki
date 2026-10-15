# Thor Agent

面向 AYN Thor 的 Android Agent：本地 WebView 聊天与锁定版本 Pi，模型协议由 JS 处理，网络与设备授权由 Native 执行。五个独立任务的精确提交已保留历史整合，正式入口已连接真实 Runtime、通用 Native Fetch、持久化与设备 ToolPack；不返回模拟成功。

已完成一轮 **Thor 真机测试与修复**：真实 OpenCode Go Responses、Brave 搜索、完整下屏输入法、Shizuku 自动无障碍、手指和实体手柄接收基线，以及真实模型的逻辑坐标/截图坐标触摸已有实测证据，见 [实机测试报告](docs/physical-test-report.md)。原生手柄注入、完整故障矩阵和真实模拟器私有配置权限仍未完成，完整四能力产品尚不能宣称发布就绪。此前本地受控模型服务、JVM、DOM 与 Android AVD 的历史证据见 [中文软件集成报告](docs/integration-report.md)。

| 能力 | 新 Session | 当前实现与限制 |
|---|---|---|
| 上屏感知 | ON | 真实设备信息；校准显示与 Accessibility 后提供应用/窗口/UI 树/截图；每次模型请求仅注入最小上下文 |
| 联网搜索 | ON | Brave 独立 Native 凭据和端点；未配置时不发布搜索；公开 HTTPS 网页读取经 Native SSRF/重定向策略 |
| 屏幕操作 | OFF | 真实 Accessibility 定向触摸、过期目标拒绝；原生 gamepad 后端明确 unsupported |
| ADB 调试 | OFF | Shizuku shell UID、Native 白/灰/黑名单；结构化文件只允许明确的应用私有合成配置目录，写前备份、hash 冲突、读回及恢复 |

ADB 调试采用 Native 白/灰/黑名单：限定参数的状态读取、亮度、风扇和刷新率直接执行；含隐私的诊断或按键分配变更逐条 UI 确认；抹除、格式化、刷机、提权和未知命令禁止。执行已审查 argv，不开放任意脚本。Shell 不等于 Root。Thor 双屏与常用配置手册作为内置 adb-operations Skill 按需加载。

## 全项目入口

环境：JDK 17、Node ≥22.19、Android SDK Platform 35 / Build Tools 35.0.0。Gradle 8.14.3（wrapper 校验）、AGP 8.10.1、Kotlin 2.2.10、AndroidX WebKit 1.14.0；Pi agent-core/pi-ai 1.2.0、TypeScript 6.0.2、TypeBox 1.3.27。依赖锁和根级 SHA-256 verification metadata 一并保存。

```sh
cd path/to/thor-agent
export JAVA_HOME=path/to/jdk-17
export ANDROID_HOME=path/to/android-sdk
npm run install:deps
npm run build
npm run verify
```

正式 Agent 必须从根工程构建。`app/` 原独立模拟构建入口会明确提示改用根工程；原 Coordinator/模拟端口仅保留给原测试，不在产品入口使用。其他独立工程保留用于模块诊断；软件集成以根工程为准。

APK：

- Agent debug：`app/build/outputs/apk/debug/app-debug.apk`，包名 `dev.thor.agent`。
- Agent unsigned release：`app/build/outputs/apk/release/app-release-unsigned.apk`；尚无发布签名。
- Input Lab debug：`tests/input-lab-app/app/build/outputs/apk/debug/input-lab-debug.apk`，包名 `dev.thor.inputlab`，独立 UID/进程。
- Input Lab unsigned release：同目录 `release/input-lab-release-unsigned.apk`。
- 诊断宿主：`tools/diagnostic/build/outputs/apk/debug/diagnostic-debug.apk` 与 `native-bridge/host/build/outputs/apk/debug/bridge-host-debug.apk`。

`build/integration/artifacts.json` 保存本次实际 APK 大小与 SHA-256。`scripts/check-artifacts.py` 检查真实 APK 的 manifest、权限、JS bundle 和 debug/release 隔离，不只看源码。

## 正式使用流程

1. Native 设置填协议、基础 URL、模型 ID、Key；可配置通用凭据 header 名称及含 `{{credential}}` 的模板。真实 Key 不返回 JS。保存先使探测失效，再更新凭据与配置修订。
2. 点击“测试当前已保存模型”，经真实 Pi 和 Native Fetch 发送合成图片、调用探测工具并回传图片。Native 要求当前修订、实际完成的 HTTP、Native 工具和图片读取证据。测试会调用服务并可能计费。普通 UI 没有设置 passed 的入口。
3. 探测成功后才可发送。原生“附图”经系统文档选择器导入，图片保存在 Native，并与下一条消息关联；每条最多两张，图片解码/上传有界。停止、授权变化、离开前台、页面或 renderer 失效撤销旧 lease，不自动重放。
4. 已授予 Shizuku shell 权限时，应用自动开启自身无障碍并保留其他服务；没有该权限时可从设置手动开启。选择本次上下屏映射，可将聊天移动到所选下屏；会话的屏幕操作、调试开关仍默认关闭。灰名单命令/配置文件写入的批准卡绑定具体操作或完整差异；亮度、风扇、刷新率等白名单调整直接执行；拒绝、停止、hash 冲突都不能写入。

已实测模型配置：协议 `OpenAI Responses`，基础 URL `https://opencode.ai/zen/go/v1`，模型 `deepseek-v4.1-flash`。OpenCode 所需会话路由头由 Native 自动安装，Key 仍仅保存在 Native。

生产配置只允许 HTTPS。debug APK 的 HTTP 例外只限明确登记的 `127.0.0.1`，供受控服务使用；release 无明文例外、受信 receiver 测试权限或 runner provider。

## 仅软件模拟器验证

```sh
# 先启动明确选定的 AVD。脚本拒绝实体设备和未指定 serial。
# API36 可创建受控 overlay 显示；记录 dumpsys display 中真实生成的 ID，不猜 ID。
adb -s emulator-XXXX shell settings put global overlay_display_devices 720x1280/240
adb -s emulator-XXXX shell dumpsys display
python3 scripts/emulator-verify.py --serial emulator-XXXX --lab-display ACTUAL_OVERLAY_ID
```

runner 只通过 signature 保护的 debug provider 准备/读报告，经真实 Pi → Native → Accessibility → Android dispatch 施加输入；没有 setPassed/事件回放入口。准备和报告通道不注册为模型工具。receiver 的 `unqualified-physical-baseline-pending` 保持不变，不把 AVD 结果冒充 Thor POC-11。

## 架构与规格

[需求](docs/requirements.md) → [架构](docs/architecture.md) → [网络](docs/network-models.md) → [工具](docs/tool-system.md) → [安全](docs/security.md) → [会话](docs/session-context.md) → [Input Lab](docs/input-lab.md) → [测试](docs/testing.md) → [实机 PoC](docs/poc-plan.md)。早期规划及五个模块旧证据是历史材料；当前实现状态与本次实际验证以集成报告为准。

## 0.3.0：语音、Skill 与结构化过程

输入区支持火山双向流式语音：切换语音模式、按住说话、松手等待最终结果后发送，上滑取消；识别中的文字会实时替换更新。语音凭据在独立的 Native 语音设置中加密保存。

侧栏的 Skill 管理支持导入 SKILL.md 或单个 Skill 的 ZIP，查看/启停/删除；Agent 自带 skill-creator 与 skill-manager，可在用户要求时提出创建和管理操作。模型默认只看到名称和简介，正文与引用资料必须通过读取工具按需加载。思考、回答与工具调用分别解包和聚合，页面以思考面板和工具卡显示。

配置方法、按需加载边界与测试范围见 [功能说明](docs/voice-skills-streaming.md)；[本轮实机证据](tests/feature-evidence/README.md)独立于之前的输入设备验收。0.3.1 已配置语音凭据并通过真实火山识别与真机麦克风到 Agent 的端到端验收，见 [云端语音验收](docs/cloud-voice-acceptance.md)。火山 TTS 仅用于电脑端预生成离线测试 WAV，不进入 APK。
