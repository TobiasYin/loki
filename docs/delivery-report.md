# 文档阶段交付记录

日期：2026-10-13。工作目录：`.`。

## 范围与结果

执行前检查目标目录存在且为空；未发现需要覆盖或移动的已有项目文件。检查了上级 AGENTS.md 路径，未发现额外文件指令；遵循用户提供的提交前缀要求，本次未初始化 Git、未创建提交。

首轮创建 **40 份中文 Markdown 文档**，包含 5 个未来模块的职责 README、20 项功能需求、10 项未执行 PoC、10 份 ADR、17 个开放问题以及官方参考索引。未创建功能源码、构建配置、依赖清单、锁文件、APK、测试程序或其他占位代码；未安装依赖或运行构建。

## 已完成的文档核查

- 全项目只有 `.md` 文件，目录为 app、runtime、native-bridge、tools、tests、docs 与 docs/adr。
- 本地 Markdown 文件链接的目标存在；代码围栏成对，未出现空文档。
- 需求编号 REQ-01 至 REQ-20、PoC 编号 POC-01 至 POC-10 连续；每项功能需求都有验收与验证入口。
- 对照检查四开关默认、按 Session 配置、Native 模型协议边界、Shell 跨能力例外、未验证手柄、按需截图、UI 折叠与模型压缩的区别。
- 审查并修正了宿主/输入服务同时死亡时无法保证释放成功的表述；加入独立租约与未知结果处理。
- 这些是文档检查结果，不是产品单元测试、编译或实机测试结果。

## 关键决定

1. Thor 单机独立 APK，无 Root 优先，不上 Play；不扩展到通用安卓。
2. Kotlin 宿主＋可信本地 WebView/Pi 为条件性选择；协议全部在 Pi/JS，Native 只做通用 HTTP Fetch。
3. 能力用工具集合控制，Native epoch/lease 处理即时撤销；系统授权和 Session 意愿分别表达。
4. L0 仅必要设备/上屏应用与能力摘要；截图、版本、窗口、UI 树、设备诊断逐项查询。
5. 触摸与真实手柄分别验收；受限输入服务不等于开放任意 Shell。
6. 本地历史、执行事件和模型上下文分开；可折叠轨迹保留，模型压缩不破坏工具配对。
7. ToolPack 可扩展，MCP 首版只留边界，避免提前实现插件平台。

## 当前未决与限制

Pi 官方当前源码已更换仓库/scope，固定核对提交见 [参考索引](references.md)，正式依赖仍未选定。Thor/WebView 实测、真实手柄输入和焦点、模拟器配置权限、两种兼容网关的图像工具回传、搜索服务可达性都是后续验证门槛。

原对话四张图未随读取返回，UI 文档按用户文字需求设计；不能声明已完成图片视觉核对。当前工具不能直接切换本聊天的 Work/模型，已提示用户选择 gpt-6-astra，不声称本次已经切换。

## 追加规划：独立上屏测试 APK

按用户补充要求增加 **Thor Input Lab**，新增 3 份规划文档：`docs/input-lab.md`、`tests/input-lab-app/README.md`、`docs/adr/011-input-lab.md`，并同步现有概览、需求、架构、感知/输入/文件、测试、PoC、路线图、安全与参考索引。

当前合计 **43 份 Markdown、25 项需求、11 项 PoC、11 份 ADR、19 个开放问题**。专项定义 **14 个 LAB 场景**：手动 API 和 Agent 共用场景/断言/报告，原始接收事件与页面结果分开，测试控制与输入通道分开，接收器需先经 POC-11 校准。路线图加入 M1a 接收端先行，原 M1 调整为 M1b。

新增规划未改变仅文档范围：没有写测试应用或产品代码、没有安装依赖、没有构建 APK，所有实机验证仍未执行。文件范围、围栏和内部链接已重新检查；下面为更新后的完整目录树。

## 当前目录树

```text
./
├── app/
│   └── README.md
├── docs/
│   ├── adr/
│   │   ├── 001-scope.md
│   │   ├── 002-pi-webview.md
│   │   ├── 003-native-fetch.md
│   │   ├── 004-capabilities.md
│   │   ├── 005-minimal-context.md
│   │   ├── 006-input-backends.md
│   │   ├── 007-debug-boundary.md
│   │   ├── 008-session-projections.md
│   │   ├── 009-search-backend.md
│   │   ├── 010-extension-boundary.md
│   │   ├── 011-input-lab.md
│   │   └── README.md
│   ├── README.md
│   ├── architecture.md
│   ├── debug-files.md
│   ├── delivery-report.md
│   ├── displays-input.md
│   ├── input-lab.md
│   ├── network-models.md
│   ├── open-questions.md
│   ├── perception-tools.md
│   ├── pi-webview.md
│   ├── poc-plan.md
│   ├── product-scope.md
│   ├── references.md
│   ├── requirements.md
│   ├── roadmap.md
│   ├── security.md
│   ├── session-context.md
│   ├── settings.md
│   ├── storage.md
│   ├── technology.md
│   ├── testing.md
│   ├── tool-system.md
│   ├── ui-ux.md
│   └── web-search.md
├── native-bridge/
│   └── README.md
├── runtime/
│   └── README.md
├── tests/
│   ├── input-lab-app/
│   │   └── README.md
│   └── README.md
├── tools/
│   └── README.md
└── README.md
```

主要阅读入口：[项目总览](../README.md)、[文档索引](README.md)、[功能验收](requirements.md)、[架构](architecture.md)、[PoC](poc-plan.md)、[ADR](adr/README.md)、[开放问题](open-questions.md)。
