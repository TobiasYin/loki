# 0.8 验证范围

- `software-checks.json` 与 `software-logs/`：实际软件检查、JUnit XML 汇总、构建与 JS 测试日志；成功构建可包含 Gradle UP-TO-DATE 项，不声称全部干净重跑。
- `software-dual-document/`：双文档执行页／聊天页分离的固定软件快照，最终App `0b6892…`／Test `92c91a…`，Runtime139、ChatJS66、Bridge13、relay3、JVM387；Android长后台及后续typed-target修改各自另证，不转用历史包结果。
- `software-target-recovery/`：后续页面变化恢复、停止边界与 Presentation 适配的统一构建快照，Runtime 115、AppJS 58、JVM 381，含首轮 fixture 编译失败。固定此轮包身份，后续测试 harness 重编不自动套用旧测试包哈希。
- `target-recovery/` 与 `stop-boundary/`：各阶段独立的真实 Pi／受控 SSE 源码验证与哈希；前者为交接前 109／55 历史快照，后者为 Runtime 115 的停止边界验证。均不表示 Thor 游戏通过。
- `emulator/`：官方模型配置、界面交互和存储隔离的模拟器组件证据，固定包及每项范围见其 README。受控本地 SSE 不代表官方云端已验证。
- `game/`：Thor Agent 在真实 Citra 的分阶段进展，只确认打开应用、启动游戏、打开菜单。截图独立核对尚未确认创建新档、取得剑、完成开篇任务。主机读取下屏诊断截图不算模型自主操作。

游戏完整任务继续使用原会话。真机已重新连接，新后台／双屏版本已经保留资料安装并恢复原会话续测；完整 Thor 游戏验收仍在进行。不要把 observer 的 instrumentation 成功或模拟器 Native 组件通过，解释为真实模型已经完成游戏目标。

不包含 APK、数据库、模型凭据、完整云端响应、用户其他会话、ROM 或游戏存档。失败轮原始材料保留在忽略的本地 build 目录；摘要记录失败原因及后续修复，不覆盖为成功。

- `software-long-task-recovery/`：后续 typed-target 恢复与长流展示投影的独立软件快照，固定 App `3afa8f91…`／Test `570ad02b…`，Runtime182、Chat69、实际 JVM398；Bridge13／relay3 明确复用此前检查，部分 Gradle 测试为 UP-TO-DATE。新包的设备／云模型／游戏结果另证，不转用旧 split 包验收。

上述 `software-long-task-recovery/` 为 `error:null` 规范化修复之前的历史快照，后续独立问题和新包另证，不作为最终发布验收。

- `software-native-null-final/`：修复成功回执 `error:null` 与未知 Runtime 错误码的最终软件包快照，固定 App `28cf1a11…`／Test `570ad02b…`。Runtime213 包含新增31项；JVM398本轮全部 UP-TO-DATE，Chat69／integration tsc／Bridge13／relay3 明确复用此前检查。此处“final”仅指本修复软件构建身份，不表示 Android／云模型／游戏任务已验收；旧包误跑日志不计入。

- `software-legacy-null-final/`：旧失败工具正文 null／缺失的显式历史投影最终软件快照，固定 App `396c98cd…`／Test `570ad02b…`／Lab `57278e3c…`。当前 Runtime235（含新增22项）和 integration tsc 通过；统一构建6秒、506任务中19执行／487缓存，JVM398全部为保留的 UP-TO-DATE XML。Chat69／Bridge13／relay3 明确引用前轮日志与源码范围，不声称重跑。此“final”仅固定本修复软件产物，Android／真机／云模型／游戏验收另证；旧213快照保持原样。

- `software-scoped-focus-final/`：分屏证明失效与固定输入焦点恢复原因的软件快照，App `ed916990…`／Test `cf42f277…`，Runtime251、JVM409。对应模拟器六项通过、Presentation 一项有效期到期失败，分别记录于 `emulator/scoped-focus/`；未转写成全部通过。

- `software-expiry-startup-final/`：临期完整读取新证明、启动专用保存和有限 DEBUG 阶段诊断，App `dbedacdc…`／Test `1bea4c10…`。实际 JVM419 中部分任务缓存，Runtime251／Chat69／Bridge13／relay3 明确同源沿用；新增三项真实 SQLite Android 用例的设备执行另证。统一构建、lint 和产物检查通过不代表 Android 或游戏目标通过。

- `software-presentation-trace/`：仅补充固定阶段诊断与哈希格式化，App `387472aa…`／Test `1bea4c10…`。实际 JVM421，其他源码同源沿用与缓存任务逐项标注。对应 `emulator/presentation-trace/` 在同一包完成七项 Android 验收，零失败／跳过／重试；旧 dbed 的 SQLite 三项另证，不重复计数，也不把游戏目标写为通过。
