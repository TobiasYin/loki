# 工具发布拒绝原因：软件增量

本包对应目标变化导致工具暂时取消发布后，`tool.prepare` 曾把原始原因统一变成参数错误的窄修复。当前 Run 保存本轮投影的固定拒绝码，并在每次 authority 快照整表替换。仅能力启用、模型支持工具、非配置探测且状态与显式原因匹配时，保留 `TARGET_CHANGED`、`PERMISSION_MISSING`、`SECURE_CONTENT`、`DISPLAY_GONE`。未授权、未知、无固定原因、模型不支持及真实后端失败不会因此被解释成可恢复目标变化，也没有授权或提交被拒绝的工具。原 bound、角色与源观察检查顺序保持。

主构建成功：14 秒、387 个任务、30 个执行、357 个 UP-TO-DATE。稍后仪器诊断测试编译成功：6 秒、144 个任务、8 个执行、136 个 UP-TO-DATE；最终 Test APK 包含后一次编译的测试诊断，不混称主构建时的旧测试包。

五模块当前 XML 合计 **446 项，失败/错误/跳过均为 0**：App 117、device-core 201、bridge core 41、authority 13、Input Lab 74。本轮 App 与 Input Lab 测试任务执行共 191 项；其余 255 项为 UP-TO-DATE 报告。新增 5 项工具发布回归已包含在 App 117 中，独立编译与 JUnit 日志保留但不再次累加。新增回归使用真实 Snapshots 对话框变化与可用性投影，再调用实际生产 helper，验证取消发布仍保留 TARGET_CHANGED；同时覆盖固定硬拒绝、无明确原因、能力与模型关闭以及刷新清除旧原因。

Runtime 251 与 JS 73 为同源码历史结果，本轮未重跑。此前 441 项不能再次加到本轮 446。四个变动源码的当前哈希单列；旧 fixture-fix 所选库存的其他 67 个文件字节一致，详见 `baseline-source-equality.json`，没有将所选库存误称为整个仓库证明。

App `7da2c6d3…`、Test `cdacb660…`、Lab `4cd028e2…` 固定 APK 的实际字节哈希均已核验，完整值见 `pins.json`。四份静态构建 artifact 文件亦核验。此目录只收集软件证据，不包含设备、云模型或游戏完成验收；此前冻结的包与父级 manifest 均未修改。
