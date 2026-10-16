# 0.8 模拟器软件证据

本目录的 `runtime-pair/` 记录 **回执修复后 `5b5535…` App 的后台两项复验通过**；`final-pair/` 保留 **256 集成验证时点，同一 App／测试 APK 的十四项通过**。两轮 App 不同，不能把旧十四项或双屏结果转用于回执修复包。`service-ui-final/` 与 `background-runtime/` 保留更早 `9c03…` App 的成功记录；其余历史组同样保持各自身份。

| 证据组 | 实际验证范围 | 结果 |
| --- | --- | --- |
| `runtime-pair/` | 回执修复后 `5b5535…` App：真实 Native/Pi、本地 SSE、配置探测／HOME／追问／通知停止、空闲写入者交接 | 两项通过，0 失败／跳过；仅此范围在新 App 上重跑 |
| `final-pair/` | 256 集成验证时点：UI 正常／减少动画、provider、SQLite、launcher、选择器状态合同、后台引擎 | 同一 App／测试包十四项通过，0 失败、0 跳过；不覆盖后续 Runtime 修复包 |
| `service-ui-final/` | 上一轮 `9c03…` App 的真实 WebView 正常／减少动画；Native provider 设置；临时 SQLite 隔离；真实 PackageManager 与拦截 Intent | 九个独立组件用例通过，同 App、两批测试 APK，详见下文 |
| `background-runtime/` | 上一轮 `9c03…` App 的后台引擎、本地 SSE、通知停止、空闲写入者关闭；选择器结果在 Service 绑定前后的状态合同 | 后台两项、选择器三项通过，独立第三个测试 APK，不计入该轮九项 |
| `ui-provider/` | 真实 Android WebView 的展开收起动画、输入与选择、能力切换回滚；Native 模型设置与受控本地 SSE；独立临时 SQLite 写入隔离 | instrumentation 5 项通过；对应构建的 App JVM 76 项、JS 52 项通过，lintDebug 通过 |
| `launcher/` | 真实 PackageManager 应用查询、公共 MAIN/LAUNCHER Intent 与指定 display 的提交参数、取消/目标变化/自身应用拒绝 | instrumentation 3 项通过 |

所有设备测试均在隔离 AVD `android_gui_agent_api36`（API 36 / ARM64 / `sdk_gphone64_arm64`）执行，使用专属 ADB 5042 与 `127.0.0.1:5557`。这些结果不涉及 Thor 真机、Citra、游戏任务或真实云端模型验收。

## 固定安装包身份

完整 SHA-256 同时保存在各组 `summary.json`。本目录不收录 APK。

| 证据组 | 文件 | SHA-256 |
| --- | --- | --- |
| 回执修复后后台两项 | app-debug.apk | `5b5535d2cff41a1c67cecd9dab692961f491272955c409d66f25dd1b2743b9a8` |
| 回执修复后后台两项 | app-debug-androidTest.apk | `d0866d32e310e099f7c71f942ffefaf824942416d2dd2946674d7f807651355c` |
| 256 集成验证时点，十四项共同使用 | app-debug.apk | `256498c422e201fe728315f3dfbdcdefa649685fa16df46246cb9435f1ec7e24` |
| 256 集成验证时点，十四项共同使用 | app-debug-androidTest.apk | `d0866d32e310e099f7c71f942ffefaf824942416d2dd2946674d7f807651355c` |
| 上一轮两批共同使用 | app-debug.apk | `9c03e723c21978c809480b5fc75e717ca248f845ee91b2888f872be64373fee8` |
| 上一轮 provider 2 + SQLite 2 + launcher 3 | app-debug-androidTest.apk | `906a0eec326b0be3638ca72cbcb251b66c3f42e337d1797b4de58a1996504a8c` |
| 上一轮正常动画 1 + 减少动画 1 | app-debug-androidTest.apk | `3cac861a0e06a2601a916a8c4567ec322967a0bb63ebe7ce144cfae61edb43d8` |
| 同 App 的后台 2 + 选择器状态合同 3 | app-debug-androidTest.apk | `c7a9a197dbaccec6ba1e46756ef4f04496d57e9c27aeeb54f5f8e8994e5fe66e` |
| UI/provider | app-debug.apk | `bd6fdb7dc29b25048dcfa4f1f296bda58f95e6f70947afc1877f0c54f1289e1e` |
| UI/provider | app-debug-androidTest.apk | `8c628a4289900eaaca463b23b6a75eb21a6c4bd322fbc56062d68eb1683f4cf4` |
| launcher | app-debug.apk | `dcd90f3e4d3a7dc1f906d6a4e6c0b36f7bcabc0662dc27dedf6d8366dea17cc8` |
| launcher | app-debug-androidTest.apk | `7182cabf553b598582e0a69274811b09146e0eb6264fc68e18dead99d4676bc0` |

UI/provider 原始固定产物位于 `build/emulator/ui-modern-2026-10-16/final/`，其本地 APK 与设备实际安装 APK 哈希一致。该组的四份 UI 源码哈希也保存在 `ui-provider/summary.json`；它描述当时源码快照，并不声称与后续所有源码逐字相同。后加的应用工具中文标签、应用查询与启动工具由后续包承载，没有据此倒推旧 UI 测试覆盖新版工具。

launcher 原始固定产物位于 `build/emulator/app-launcher-2026-10-16/`。该组 App 与 test APK 的哈希均不同于 UI/provider，未在此新版包上重跑 UI/provider 完整组件套件。

## 回执修复后：只重跑后台两项

`runtime-pair/summary.json` 与成功日志记录新 App `5b5535…`／测试包 `d0866d…` 的 **2/2 通过，17.426 秒，0 失败、0 跳过**。固定 APK、本地文件与实际安装文件的哈希一致，安装前无应用进程或 Runtime Service，使用 `install -r` 保留数据。

两项使用真实 Native bridge、Pi 与五次本地 SSE 请求，验证配置探测到后台运行的衔接、HOME／Activity 销毁后继续执行、返回同 Host/WebView、同 Pi 会话追问、实际通知停止和部分上下文保留，以及空闲配置重载等待旧写入者结束。没有云端请求、Thor 或设备输入。通知临时 grant 后在 finally 恢复原 denied，完整 flags 与原值一致；动画设置保持 `null`，最后 Service 已退出。

新 APK 的 `ui.js` 与 `style.css` 内容哈希和 256 集成包一致，本轮按变更范围只重跑后台两项。**先前十四项套件中的其余十二项，以及双屏用例，没有在新 App 上重跑**，不能因为 UI 内容一致就改变旧证据包身份，也不把本地后台验证扩展为所有真实设备工具的后置观测验收。完整固定产物位于 `build/emulator/runtime-receipt-2026-10-17/run-01/`，公开目录只有范围摘要和成功日志，不含测试密钥值、历史、原始云端响应、数据库或 APK。

## 256 集成验证时点：同包十四项

本轮使用上表 `256498…` App 与 `d0866d…` 测试包，本地固定文件与实际安装文件的哈希一致。所有测试在隔离模拟器串行完成，无卸载、清数据或 wipe；未操作 Thor。

| 用例 | 实际结果 | 耗时 |
| --- | --- | --- |
| 正常 UI、动画、真实触摸／键盘 | 1 项通过 | 21.048 秒 |
| 新进程的真实系统减少动画 | 1 项通过 | 8.413 秒 |
| Native provider 2 + 临时 SQLite 2 + launcher 3 | 7 项通过 | 23.921 秒 |
| PickerResultLifecycleTest 状态合同 | 3 项通过 | 0.137 秒 |
| 后台执行与空闲写入者关闭 | 2 项通过 | 25.652 秒 |

正常动画六组真实中间帧数为 **11、7、7、10、9、30**，均采到 settled、无超时，原有至少两个中间帧标准不变。最大帧间隔为 83.4ms，不宣称稳定 60 FPS。减少动画在新进程前应用真实系统设置，结束恢复 `null`。UI fixture 结束后重载清理；profile、凭据及原通知权限保持／恢复。后台两项临时 grant 通知后，在 finally 恢复为原来的 denied，完整权限 flags 相同，最后 Service 已退出。

后台测试使用真实 Native bridge、Pi 与五次本地 SSE 请求，覆盖 HOME／Activity 销毁后的执行与配置探测衔接、同 Host/WebView、同 Pi 会话追问、实际通知停止和部分正文保留；没有云端请求或设备输入。选择器三项检查未绑定时暂存结果、保存目标会话／URI 和取消行为，不打开系统文件选择器、不读取 URI。provider 仍使用受控本地 200／401，launcher 仍拦截 Intent。因此十四项不是官方云端、完整文件导入、实际应用前台切换或 Thor 游戏验收；双屏用例有单独报告，不计入十四项。

`final-pair/` 只保留摘要、布尔结果、五份成功日志与一张已检查的合成 UI 截图，不含测试密钥值、完整历史、原始云端响应或数据库。完整本地证据位于 `build/emulator/ui-service-2026-10-17/final-03/`，固定 APK 在 `build/emulator/dual-screen-2026-10-17/run-07/`。**后续 Runtime 修复版本不与本轮包相同，本轮通过不能代替其回归。**

## 上一轮 Service 架构复验

`service-ui-final/summary.json` 记录同 App 的九个独立通过用例及各批测试 APK 身份。本地固定 APK 与实际安装 APK 的哈希逐一核对。Native provider、SQLite 与 launcher 七项合跑用时 17.424 秒；正常 UI 一项 22.089 秒，减少动画新进程一项 9.509 秒。先前同 App 的额外减少动画通过不重复计数。

正常 UI 使用 250ms、`cubic-bezier(.4,0,.2,1)` 过渡。思考展开／收起、外层工具入口、单卡展开／收起和快速反转加流式更新，分别记录 **8、3、8、10、9、20** 个真实中间高度帧；六组全部采到非动画终态，没有超时。真实平台触摸与键盘、模型／思考强度选择、标题按宽度省略、能力乐观状态及 bridge 失败回滚均通过。工具仍按外层、单卡两级懒渲染；关闭动画结束后才释放其内容。

部分 rAF 帧间隔达到 283.4ms，因此结果证明实际过渡和正确收尾，**不承诺稳定 60 FPS**。减少动画使用新 instrumentation/WebView 进程启动前的真实系统设置，未伪造浏览器媒体查询；结束后恢复原动画设置 `null`。通知权限保持原值，profile 与凭据未变；合成页面通过空闲 Service 所属入口重载清理，未另建数据库写入者，最后 Service 已退出。

前几轮正常动画检查失败，失败证据留在 ignored 本地 `build/emulator/ui-service-2026-10-17/{run-01,run-02,final-01}/`。产品先将前段过快趋近终态的 190ms 过渡优化为上述 250ms 方案。后续 trace 进一步发现旧采样截止时真实 WAAPI 仍在执行，最大高度只采到 157.4，尚未到最终 206，导致用不完整范围计算中间帧。测试随后改为真实输入后至少 550ms、且 WAAPI 与 disclosure 状态实际 settled 才停止，硬上限五秒；**原来至少两个中间帧的标准没有降低**。最新通过不把旧失败改记成功，也不证明早期失败全部由同一原因引起。

公开文件只保留帧数／收尾摘要、成功测试日志、provider 布尔合同结果以及两张合成 UI 截图。没有测试密钥值、Native 设置表单截图、完整历史、云端原始响应或数据库。provider 的 200／401 来自本地受控服务；launcher 仍是 RecordingContext 拦截 Intent，未实际打开目标 Activity。这九项不是后台任务、文件选择器、双屏自动操作或 Thor 真机游戏验收；这些需要各自独立证据。

`background-runtime/summary.json` 与两份日志另行记录同 App、测试包 `c7a9a197…` 的五项通过：后台两项使用真实本地 SSE，验证返回聊天时执行上下文保留、通知停止与空闲重载等待旧写入者关闭；选择器三项检查绑定前结果暂存、取消与保存状态，不声称完整系统文件选择器导入端到端通过。通知授权及 flags 已恢复。该组不包含双屏通过结论，也不与 UI 九项混为同一测试包。

## UI/provider 的证据边界

- `ui-normal.json` 与 `ui-normal.log`：真实 WebView `133.0.6943.137` 中的合成会话 UI 测试。实际 Android 触摸与键盘事件进入 WebView；Native 任务命令由测试拦截，不调用云端或控制上屏应用。覆盖思考、外层操作记录、单工具卡的展开收起、快速反转、流式更新、键盘操作与焦点保留；模型/思考强度选择；完整标题按可用宽度省略；能力即时切换、旧快照、防过期失败、明确失败及 bridge 失败回滚。
- `ui-reduced.json` 与 `ui-reduced.log`：在启动新的测试/WebView 进程**之前**将真实 Android 动画时长设为 0，浏览器实际报告 reduced motion 并立即完成展开/收起；没有伪造 `matchMedia`。测试后系统设置恢复为原值 `null`。不声称该 WebView 能在同一进程中即时响应系统动画设置变化。
- 正常动画通过真实 `requestAnimationFrame` 记录连续高度，六组展开、收起与反转轨迹各有 3–12 个中间高度帧。最大帧间隔约 33–150ms，证明存在实际过渡，**不代表稳定 60 FPS**。完整采样在 JSON，摘要在 `summary.json`。
- `provider-defaults.json`、`provider-settings.json` 与 `provider-settings.log`：空配置默认官方 provider；官方模型与元数据、保存后重开、跨 provider 空密钥拒绝、密钥不进入 WebView/普通 profile 偏好；Go/DeepSeek 的官方元数据门禁无需假造探测成功；受控本地 SSE 200 与 401 显示。**没有调用官方云端验证凭据、模型或网络可达性。** 测试凭据已清理。
- `scoped-storage.log`：随机临时 SQLite 数据库与 trigger 验证 capability/stream 的目标会话写入不影响无关会话，并保留原有历史；不读取或清空用户 `product.db`。
- `js-tests.log`：对应 UI 源码快照的 52 项 JS 回归日志。JSDOM 结果仅作为软件逻辑检查，真实 WebView 的证据另由上述 instrumentation 与帧采样提供。
- `images/` 仅保留真实合成 UI 截图：模型选择、话题标题、能力失败回滚。截图等待视觉帧稳定后取得，不是 DOM 人工拼图，也不包含私有聊天内容或凭据。

UI/provider 全部测试保存了既有 profile 与凭据；没有复制真实设备数据作为模拟器配置。较早探索轮次及另一套旧 provider APK 的截图不收录，以免混淆固定包范围。

## launcher 的证据边界

`launcher/instrumentation.log` 包含以下三个组件用例的通过结果：

- `sdkSubmissionIsExplicitPublicMainLauncherOnRequestedDisplayWithoutExtraCapabilities`
- `cancellationTargetChangeAndOwnAppDenialNeverSubmit`
- `launcherDiscoveryReturnsOnlyQueryMatchesAndNeverOwnChat`

查询使用真实 Android PackageManager；启动使用 RecordingContext 拦截 SDK Intent，检查显式公共 MAIN/LAUNCHER、指定显示屏与提交边界。**该测试没有实际启动目标 Activity，也没有证明前台切换或模型自主启动应用。** 后者需要单独的模型/真机流程证据。

安装保留已有数据，没有卸载、清空或 wipe；测试不修改配置，原始报告确认产品数据库测试前后相同。本目录只保留 `productDbUnchanged: true`，不收录数据库文件、私有数据库哈希、全量备份、设备凭据或本机绝对路径。

## 文件校验

`manifest.json` 为本目录除自身之外的全部文件记录字节数与 SHA-256，可用于检测整理后文件是否变化。早期 UI/provider JSON 只压缩空白；`service-ui-final/` 的 JSON 则是明确筛选后的摘要与布尔结果，没有复制完整原始报告。原始报告与固定 APK 仍留在上述 ignored build 目录，本目录提供可审阅的紧凑证据，不替代真实云端或真机验收。

## Subsequent long-background and Presentation boundaries

`final-null-recovery/` fixes App `396c98cd…` / Test `570ad02b…`: six cases passed (explicit background/UI/provider 5 and real 380-second background 1), but Presentation failed in two independent attempts. `scoped-focus/` fixes App `ed916990…` / Test `cf42f277…`: the same six cases passed, while Presentation failed once with a passive trace proving the same-key proof reached its 5-second absolute expiry. These groups are not all-passed acceptance, and their results do not transfer to the later proactive-refresh/startup-storage package. Each README records exact identities, durations, unresolved boundaries and complete preservation, including the one selected-session `updatedAt` metadata change. No Thor/cloud/game success is claimed.

`expiry-startup/` separately fixes App `dbedacdc…` / Test `1bea4c10…`: three isolated startup-recovery SQLite cases passed, followed by one Presentation failure at same-key proof expiry. The additional five cases and 380-second test were not executed on this pair; earlier packages' results are not transferred. The attempt and restored baseline are retained pending more precise reader diagnostics.

`presentation-trace/` fixes App `387472aa…` / Test `1bea4c10…`: all seven actually executed emulator cases passed without skip, failure or retry (Presentation 1, short background/UI/provider 5, real 380-second background 1). The earlier startup-storage 3 result remains a separate dbed source-equivalent check and is not counted again. Current full-proof reconstruction is observed, but the tail 64 does not prove 1,999 ms near-expiry invocation or explain older missing reader traces. All old failed groups remain intact; no Thor/cloud/game completion is claimed.
