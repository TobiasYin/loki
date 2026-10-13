# ADR 决策记录

日期：2026-10-13。状态说明：“已采纳（需求）”是产品约束；“已采纳（设计）”是当前规划；“条件性/候选采纳”需要指定 PoC 通过后才能成为实现基线。没有一条 ADR 表示功能已实现。

| 编号 | 决策 | 状态 |
|---|---|---|
| ADR-001 | [产品仅面向 Thor、独立分发和无 Root 优先](001-scope.md) | 已采纳（需求） |
| ADR-002 | [Pi/JS 运行在可信本地 WebView](002-pi-webview.md) | 条件性采纳 |
| ADR-003 | [Native 仅实现通用 Fetch 流式传输](003-native-fetch.md) | 已采纳（需求），凭据引用方案有条件 |
| ADR-004 | [动态工具注入与即时撤销](004-capabilities.md) | 已采纳（需求） |
| ADR-005 | [最小 L0 与独立按需感知工具](005-minimal-context.md) | 已采纳（需求） |
| ADR-006 | [触摸与真实手柄分开验证](006-input-backends.md) | 条件性采纳，产品目标不变 |
| ADR-007 | [任意 Shell 是明确的高权限通道](007-debug-boundary.md) | 已采纳（需求与安全边界） |
| ADR-008 | [持久化领域模型与 UI/模型投影分开](008-session-projections.md) | 已采纳（设计） |
| ADR-009 | [独立搜索 ToolPack 与显式后端](009-search-backend.md) | 候选采纳 |
| ADR-010 | [首版内置 ToolPack、预留 MCP](010-extension-boundary.md) | 已采纳（范围） |
| ADR-011 | [独立上屏测试 APK 作为统一接收端](011-input-lab.md) | 已采纳（需求及设计），测试 IPC 待验证 |

新增决策保留旧记录并标注 superseded，不直接抹去原取舍。修改时同步需求、专项技术文档、验证门槛和开放问题。
