# 技术选型、备选方案与风险

> 2026-10-13 状态更新：五模块已开发并进入正式软件整合。本文早期“仅规划/未授权/无 APK”等叙述保留其历史背景；当前实现、实际测试与尚未完成的 Thor 验收，以 [中文集成报告](integration-report.md) 为准。来源模块旧测试不等于本次集成结果。
所有“首选”为设计选择，不表示依赖已安装。Pi 核对基线见 [参考资料](references.md)。

| 领域 | 首选 | 备选与取舍 | 验证门槛 |
|---|---|---|---|
| 宿主 | Kotlin + Android SDK | Java 可行但无明显收益 | Thor Android/API、生命周期 |
| UI 与 Agent | 同一可信本地 WebView，TypeScript + Pi | Compose UI＋隐藏 WebView 会增加消息同步；嵌入其他 JS 引擎缺 Web API；Node 嵌入增加包体/维护 | POC-03/10 |
| 模型适配 | Pi Agent Core + pi-ai；只打包目标 API | 若当前基线有回归，选择已审计稳定 tag；小范围 JS 适配可另提 ADR；不把协议搬到 Native | POC-03/04 |
| HTTP | Native OkHttp 候选＋Fetch 兼容层 | Android 平台 HTTP 客户端；浏览器直连不符合原生转发要求 | POC-04；未来锁版本 |
| JS/Native IPC | AndroidX WebKit message listener＋feature probe | 受限字符串消息分块；不无条件退回开放 addJavascriptInterface | 桥 origin、二进制和吞吐 |
| 上屏识别/截图 | Accessibility、DisplayManager、PackageManager | MediaProjection 仅为截图备用路线，需单独同意且多屏选择待验证 | POC-01/02 |
| 触摸 | Accessibility dispatchGesture 指定 display | 受限 Shizuku 输入执行器；禁止自动提升到任意 Shell | POC-06 |
| 原生手柄 | Shizuku UserService 中受限输入执行器（条件性） | Shell input 仅作基线；虚拟 HID/uinput/厂商接口各有特权风险，非默认解法 | POC-07，发布门槛 |
| Shell/文件 | Shizuku UserService；结构化文件工具优先 | 直接无线 ADB 客户端增加配对与安全维护；SAF 适合用户导出文件 | POC-08 |
| 持久化 | Native SQLite/Room 候选；设置 DataStore 候选 | 文件日志适合导出而非多实体事务；不以 localStorage 作为事实源 | POC-10 |
| 凭据 | Keystore 保护加密密钥，私有目录存 API Key 密文 | 不把 API 字符串误当 Keystore 原生任意数据保险箱 | POC-04/10 |
| 搜索 | Brave Search API 候选，用户自配密钥 | Tavily；用户自建 SearXNG；按可达性和成本选一个首发 | POC-09 |
| MCP | 仅预留 ToolPack 适配 | 未来比较 pi-mcp 和自建轻量客户端；当前不引入 | 后续 ADR |

## 版本策略

本次读取的官方 Pi 仓库已从旧地址重定向至 `earendil-works/pi`，提交 `7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6`。该提交两个 package.json 都声明 1.2.0、Node engines ≥22.19.0，包名为 `@earendil-works/pi-agent-core` / `@earendil-works/pi-ai`。

这些是源码元数据；不等同于已验证 npm 发布包或项目最终锁定版本。浏览器支持由 pi-ai README 说明，Node engines 不能用于推断需要在 Android 安装 Node，也不能用于证明任意 WebView 兼容。正式 PoC 时记录 tag/commit、发布包完整性、包版本、transitive dependencies 与许可证；本次不生成 lockfile。

Android minSdk/targetSdk、AndroidX、Kotlin、OkHttp、UI 框架均待 POC-01 的真实系统信息和后续开发时官方支持矩阵确定。公开感知/定向触摸方案主要需要 API 30+；不把这一门槛误写成已确认 Thor 固件值。

## 风险优先级

P0：手柄身份/轴/路由不成立；WebView Fetch 链路不成立；撤销存在放行窗口。

P1：应用配置受沙箱限制；截图/窗口受系统策略限制；模型网关“兼容”不完整；模型摘要导致错误恢复；UI 抢焦点。

P2：截图跨桥内存峰值、搜索区域可用性、OEM 杀进程、主题/键盘适配。先验证高影响可行性，再实现大量 UI；不为未证明的手柄和 Shell 能力作完成承诺。

## 独立测试 APK 选型补充

Thor Input Lab 首选 Kotlin＋原生 View/自绘场景，便于直接观察 Activity 的输入分发和控件语义；独立包/进程/UID，无 Pi、无输入注入、无 Shizuku 依赖。初期本地 arm＋导出，后期增加签名权限保护的 bound service 测试通道。只有文档契约可复用，不共享被测执行器的判定计算。见 [设计](input-lab.md) 和 ADR-011；具体包名/签名/Q-18 与时序阈值/Q-19 仍待验证。
