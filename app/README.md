# Loki 正式 Android 宿主

产品入口为 `MainActivity` → `ProductHost` → trusted WebView + 真实 Pi Runtime/Native Fetch/设备 Gateway。构建必须从根目录执行 `./gradlew :app:assembleDebug`。原模拟 Coordinator/RuntimePort 仅为旧单元测试保留，不是正常产品路径。

Native 保存会话、Run、事件、领域回合、ContextSnapshot manifest 和附件，Keystore 加密凭据及配置备份。恢复只显示 interrupted，不执行旧 intent。四项能力共用 Native Authority；危险操作等待具体审批，未批准不能执行。

完整设置、构建、测试命令和限制见 [根 README](../README.md) 与 [中文集成报告](../docs/integration-report.md)。本目录 `validation.md`/`validation/` 是来源任务的独立模拟证据；本次产品证据在 `tests/integration-evidence/`。
