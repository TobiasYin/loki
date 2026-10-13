# Thor Agent

面向 **AYN Thor 双屏安卓掌机** 的独立 Agent APK：下屏聊天，上屏按需感知；用户可以按会话开启搜索、跨屏操作和设备调试。

> 当前状态：仅项目规划和文档。2026-10-13 完成首轮官方资料核对；未开发功能、未安装依赖、未构建或运行 APK，未做 Thor 实机验证。文中的接口契约、阈值和目录分工均为拟议设计，不能视作已经实现。

本次交付与检查结果见 [交付记录](docs/delivery-report.md)。

## 产品边界

- 只支持 AYN Thor；优先无 Root；自行分发 APK，不上 Google Play。
- 用户自填 OpenAI Responses 兼容 API 或 Anthropic Messages API；所选端点与模型必须同时支持图片输入和工具调用。
- Pi Agent Core 负责 Agent 循环，pi-ai 负责所有模型协议；Android Native 只提供通用 HTTP Fetch 流式传输及设备工具。
- 默认只注入必要设备、上屏当前应用信息和能力状态；截图、版本、窗口详情、UI 树、诊断均按需调用独立工具。
- 能力按 Session 保存，以动态工具注入控制可见性，以原生执行入口实时校验处理撤销竞态。

| 能力 | 新 Session 默认 | 内容 |
|---|---|---|
| 上屏感知 | ON | 最小应用上下文、按需截图、应用/窗口/UI 树/设备信息 |
| 联网搜索 | ON | 搜索、网页读取；未配置后端时显示不可用 |
| 屏幕操作 | OFF | 跨屏触摸、原生手柄按键/轴事件 |
| ADB 调试 | OFF | 设备 Shell、设备文件读写、配置备份恢复 |

**ADB 调试是高权限通道。Shell 可以读取屏幕、注入输入或发起联网操作；其他三个开关不能在任意 Shell 已开启时提供强隔离。Shell 也不等于 Root，不能承诺读取其他应用的私有目录。** 详见 [安全边界](docs/security.md)。

## 独立上屏测试应用

规划 **Thor Input Lab**，位于 `tests/input-lab-app/`，作为手动 API 测试和 Agent 自动执行测试共同依赖的上屏接收端。独立 APK/进程/UID，记录真实送达的点击、手势、手柄按钮/轴、焦点及场景状态；不内置注入器，也不是正式使用 Thor Agent 的运行依赖。详见 [专项设计](docs/input-lab.md)。

## 推荐阅读顺序

1. [目标、非目标与场景](docs/product-scope.md) → [需求和验收](docs/requirements.md)。
2. [总体架构](docs/architecture.md) → [技术选择](docs/technology.md) → [Pi / WebView](docs/pi-webview.md)。
3. [工具及撤销](docs/tool-system.md) → [感知工具](docs/perception-tools.md) → [双屏和输入](docs/displays-input.md)。
4. [网络与模型](docs/network-models.md)、[搜索](docs/web-search.md)、[Shell 与文件](docs/debug-files.md)。
5. [会话与压缩](docs/session-context.md)、[UI/UX](docs/ui-ux.md)、[设置](docs/settings.md)、[存储](docs/storage.md)。
6. [上屏测试 APK](docs/input-lab.md)、[PoC 计划](docs/poc-plan.md)、[测试](docs/testing.md)、[Roadmap](docs/roadmap.md)、[ADR](docs/adr/README.md)、[开放问题](docs/open-questions.md)、[资料与修正](docs/references.md)。

## 目录约定

| 目录 | 未来职责 | 当前内容 |
|---|---|---|
| `app/` | Android 应用生命周期、入口、权限与设置宿主 | 模块 README |
| `runtime/` | Pi、模型协议、会话投影、聊天 Web UI | 模块 README |
| `native-bridge/` | 受限消息桥、通用 Fetch、原生授权网关 | 模块 README |
| `tools/` | 内置 ToolPack 与平台执行器 | 模块 README |
| `tests/` | 将来的契约、真机及集成测试 | 测试范围 README |
| `tests/input-lab-app/` | 独立上屏测试 APK，统一输入接收和场景断言 | 职责 README，仅规划 |
| `docs/` | 产品、架构、技术、体验与交付文档 | 本次主要交付 |
| `docs/adr/` | 可追溯决策及备选方案 | ADR 索引和决策记录 |

目录是逻辑边界，不预先等同于 Gradle/npm 子项目。无 package.json、Gradle、Manifest、源码、锁文件或虚假测试占位。后续开发授权后再决定物理拆分。

## 维护规则

需求使用 REQ 编号，实机验证使用 POC 编号，关键决策使用 ADR 编号，风险使用 Q 编号。修改决策时同步需求、契约和验收；不要把“设计决定”“官方存在 API”“实机已通过”混写。参见 [文档索引与规则](docs/README.md)。

当前最大可行性门槛：Pi WebView 流式链路、上屏准确识别、真实手柄输入与焦点、目标模拟器配置路径权限。失败时必须明确缩减范围或重选方案，不能用未验证实现承诺补齐。
