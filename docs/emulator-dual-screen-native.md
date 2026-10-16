# 模拟器双屏 Native 组件验证

入口：`dev.thor.agent.EmulatorDualScreenControlTest#realNativeBackgroundRolesControlsAndCrossScreenGuardsAreIndependent`，必须显式传入 `emulatorAcceptance=true`，且仅接受 `Build.HARDWARE` 为 `ranchu`／`goldfish` 的模拟器。完整模拟器 Native 组件已于 2026-10-17 在 `run-07` 通过：70.597 秒，1 项测试，19 个工具步骤。独立接收、屏幕隔离、助手自身保护、逃离受阻下屏和恢复检查均通过。前序失败历史保留在文末。

先独占隔离模拟器，安装同一最终版本的主 APK、测试 APK，以及 `tests/input-lab-app/app/build/outputs/apk/debug/input-lab-debug.apk`。不对 Thor 发命令。测试拒绝已有活动 Run、已有模型凭据和已有 Input Lab Activity 任务，避免覆盖真实配置或打断原接收演练。不存在模型凭据时使用临时 dummy 官方 preset，只做 Native begin/工具组件操作，不启动模型、SSE 或网络。

第二屏通过公共 `DisplayManager.createVirtualDisplay` 创建，使用 `PUBLIC | OWN_CONTENT_ONLY | PRESENTATION | TRUSTED | SUPPORTS_TOUCH | OWN_FOCUS` 与持续排空的 `ImageReader` surface。仅 fixture 创建时 adopt `ADD_TRUSTED_DISPLAY`，立即 drop 后才启动接收端和执行 Native 工具。surface 只承载实际显示，不拿它的消费者像素替代 Native 截图。不写 `overlay_display_devices`：AOSP 开发者 overlay 会在默认显示器创建悬浮窗口，可能遮挡上屏，影响原有输入安全守卫。若模拟器拒绝公共虚拟显示器、第三方 Activity 启动或 Accessibility 访问，保留失败报告；不加入权限或安全守卫绕过来伪造通过。[DisplayManager 官方 API](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[AOSP OverlayDisplayAdapter](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/display/OverlayDisplayAdapter.java)。

独立 Input Lab 包的 LabActivity 与 ScenarioActivity 通过两个 `NEW_TASK | MULTIPLE_TASK` 任务分别运行在主屏和第二屏。测试把两块显示器临时映射为 upper/lower，在 lower 显示真实聊天页面，再经过 Service Engine 回调执行 `agent.background`，核验聊天实际隐藏、第二次调用无提交。随后使用普通 ProductHost Native RPC 的 begin、authority、审计意图持久化、tool.prepare、tool.execute、attachment.get，不直接调用接收端控件回调或写接收端状态。

验证内容：

- 每个 Run 默认 upper，Run A 选择 lower 后 Run B 仍为 upper。
- 两屏分别取得真实 Native 截图与 UI 树；截图另存并记录尺寸、显示器 ID 和散列。
- 下屏依据当前树中“设备偏好”的实际范围执行坐标 tap，独立接收端必须收到一组 DOWN/UP，并进入 preferences，导航计数严格为 1。
- 上屏根据重新读取的 nodeRef 执行 ACTION_CLICK，独立 Lab-05 语义计数严格为 1，接收窗口确实为 upper。
- Run A 在 lower 使用旧 upper 的 snapshot、nodeRef、frameId 必须全部拒绝。下屏额外 DOWN/UP 或上屏任何坐标触摸都使测试失败。
- 正常接收验收封存后，再让已选 lower 的 Run 遇到聊天重新占据下屏：下屏截图必须因助手自身窗口拒绝且不输出附件，但同 Run 仍能选择 upper 并读取独立接收端，随后固定后台工具腾出聊天。不得降低助手自身保护。
- 保存两个接收端原始日志，并核对其完整／封存前缀长度及 SHA-256。不会把 Android 日志冒充可信输入发送者证明，也不会把组件执行冒充真实模型自主完成。

输出位于应用私有 files 目录：

- `emulator-dual-screen.json`：阶段、错误、实际工具回执、双 Run 屏幕角色、原始接收报告、散列和恢复检查。
- `emulator-dual-screen-upper.jpg`、`emulator-dual-screen-lower.jpg`：生产 Native 截图。
- `emulator-dual-screen-upper-events.jsonl`、`emulator-dual-screen-lower-events.jsonl`：独立接收日志。上屏只保存报告所封存的前缀。

收据只保留 run/session 标识和工具结果，不写 begin/network binding、lease 或凭据。finally 只结束测试自己的 Run，恢复原选中会话，封存／释放本轮接收端，停止本次独占创建的 Input Lab 页面，释放虚拟显示器及 surface，恢复 profiles、native-credentials、display-roles 和无障碍启用值，并通过正常空闲 Engine 重载恢复内存中的原配置。原历史只允许增加测试记录，必须完整保留测试前所有条目。未启动模型的测试 Run 最终按 failed 结束，不能伪造模型 completed。

## 已执行记录与当前卡点

`build/emulator/dual-screen-2026-10-17/run-01/` 保存首轮失败：测试只读 helper 给 Budgets.read 传了 8 MiB，而公共读取预算上限为 1 MiB；前置读取立即失败，尚未创建显示器或改配置。现已改为 Budgets.CONFIG。

`run-02/` 使用 app `9c03e723c21978c809480b5fc75e717ca248f845ee91b2888f872be64373fee8`、test `c7a9a197dbaccec6ba1e46756ef4f04496d57e9c27aeeb54f5f8e8994e5fe66e` 实际执行 18.271 秒：公共虚拟显示器成功创建为 display 2，但平台拒绝把第三方 ScenarioActivity 启动到未受信任的虚拟显示器，未执行任何 Agent 工具。原设置、凭据偏好、校准、无障碍与历史均保留，虚拟显示器已释放。旧 idle Host 已关闭引发的选中会话清理空 connection 也被记录；现改为在重新加载原状态后的新 Host 恢复原选中会话。

AOSP Android 16 明确：未受信任显示器不能任意承载未允许 embedded 的第三方 Activity；TRUSTED 标志需 ADD_TRUSTED_DISPLAY，SUPPORTS_TOUCH 提供虚拟触摸能力，OWN_FOCUS 提供该显示器独立焦点。因此下一轮**只在测试创建真实显示器期间**临时 adopt `android.permission.ADD_TRUSTED_DISPLAY`，使用这三个经过源码核对的标志，finally 立即 drop，然后才进行正常启动和 Native 工具调用。生产授权、窗口安全探测和输入守卫均不变，没有 INTERNAL_SYSTEM_WINDOW 或 Shell 输入。尚待新测试 APK 编译与实际运行，不能据此声称已通过。[DisplayManager flags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java)、[DisplayManagerService 权限核验](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java)、[ActivityTaskSupervisor 启动规则](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java)。

`run-03/` 使用同 app `9c03…fee8`、新 test `906a…4a8c` 执行 64.568 秒：受信任／触摸／独立焦点显示器成功承载两套真实接收 task；`agent.background` 经实际 Engine/Native 授权提交成功，窗口诊断确认聊天已隐藏、下屏 ScenarioActivity 可见。随后上屏首次进入沉浸模式触发系统 ImmersiveModeConfirmation 教学层，完整安全触摸区域不能稳定，测试按守卫停止，没有执行坐标／语义输入。cleanupErrors 为空，原配置、角色、无障碍、凭据偏好、历史均保留且临时显示器已释放。不能把这一部分成功当作双屏整套通过。

下一轮按 Android CTS 窗口测试惯例，仅在 fixture 初始化临时将 `immersive_mode_confirmations` 设为 `confirmed`，finally 精确恢复原值并回读核对，避免系统首用教学层打断本轮目标。此设置不忽略现有覆盖层，不改 Native overlay／密码／助手自身守卫，也不会成为生产功能。[CTS 可恢复沉浸教学初始化](https://android.googlesource.com/platform/cts/+/master/tests/tests/systemui/src/android/systemui/cts/WindowInsetsBehaviorTests.java)。

`run-04/` 越过系统教学层后，测试自身误把 LAB-05 的 readiness 写成 RECORDING。接收端实际 `arm()` 进入 ARMED，语义目标已经可用；只有原始 dispatch 才进入 RECORDING。现严格要求正确 testRunId 和 ARMED／RECORDING，并保留后续稳定窗口检查，不通过额外注入事件制造 readiness。初始化和恢复检查均通过。

`run-05/` 使用同 app `9c03…fee8` 与 test `2e923…6dc0` 进入 14 个真实 Native 工具步骤：上下屏树／截图成功，下屏真实 tap 后独立日志记录 DOWN、UP、click 与 navigation，旧上屏 snapshot／nodeRef／frame 在下屏全部被拒绝。上屏最后语义激活被生产实现的 `window.isFocused` 必要条件拒绝。独立上屏 armed 日志已明确 `hasWindowFocus=false`；上屏仍然可见，当前树与截图可读，节点不是旧引用。原始失败消息和两侧接收日志已保留。

这暴露了多屏可见窗口与全局键盘焦点不等价的产品边界。后续补丁只对语义激活增加 `UiActionWindow`：要求同一选定显示器的唯一可见应用窗口，原绑定 windowId／包／矩形区域，复用生产 UpperTargetResolver 重新计算完整遮挡与可触区域并与观察精确相等，再保持原节点路径、身份、签名、可见性、bounds 与权限校验；focused 本身可以为 false，但观察后 focus／窗口结构发生变化仍使快照失效。固定 Android BACK 与真实手柄的焦点规则保持原样。新增 6 项核心边界回归；待统一新包实际重测，尚不宣称整套通过。

## 最终通过结果

`run-06` 已用新生产 app `256498…7e24` 验证无焦点上屏语义激活和独立 LAB-05 pass／semanticCount=1，但测试后来误把 Android 延后触发的 click 回调当作必须仍在 UP dispatch 栈内，因真实 dispatchToken=null 报错。接收端未作任何修改：Android View 可以 post performClick，原始记录明确保留 null。测试现保留严格单 DOWN/UP、同 downTime、一次 click／navigation 和正确独立界面状态；同步 token 存在时必须匹配 UP，延迟回调则核对事件顺序和 5 秒有界观察时间，并明确只证明时间窗口关联，不能证明发送者身份。

最终 `run-07` 使用 app SHA-256 `256498c422e201fe728315f3dfbdcdefa649685fa16df46246cb9435f1ec7e24`、test SHA-256 `d0866d32e310e099f7c71f942ffefaf824942416d2dd2946674d7f807651355c`，70.597 秒通过。Input Lab APK SHA-256 为 `9697588ca085ef7e616e6f736eb38ecdfd8375e4138633fa8e8033c71cdb392e`。关键事实：

- 上屏 window 119 的 focused=false、active=false，仍为当前唯一可见、绑定树窗口；Native 语义激活成功，独立接收端 `status=pass`、semanticCount=1，没有上屏坐标触摸。
- 下屏一对真实 Android DOWN/UP、一次控件 click、一次导航，独立 page=preferences；延迟 click 的 dispatchToken 保留 null，未伪造来源。
- 两屏分别取得原本可输入的 Native frame 与当前树；旧上屏 snapshot、nodeRef、frame 在下屏全部 TARGET_CHANGED，Run B 仍保持 upper。
- 聊天重新占据已选下屏后，截图按自身保护 PERMISSION_MISSING 且不返回附件；同 Run 随后成功选择 upper、读取 Input Lab，再通过固定后台工具腾出聊天。
- 两份独立接收日志的封存前缀长度及 SHA-256 均被测试和外部取回再次核对。cleanupErrors=[]；profiles、凭据偏好、校准、原选中会话、无障碍启用值、沉浸教学状态及原历史完整保留，虚拟显示器释放。服务最终为空，animator 原值 null、通知原值 denied 及 flags 均未改变。

公共证据位于 `tests/release-0.8-evidence/emulator/dual-screen/`，包含固定哈希 summary、成功仪器日志、Native 工具结果、独立接收报告／原始日志、两张 Native 截图与恢复检查。完整本地固定 APK 和前序失败位于 `build/emulator/dual-screen-2026-10-17/`。这是本地离线合成界面的真实 Native 组件验收；没有调用模型、没有完成完整设置任务、没有操作 Thor 或证明任意游戏兼容，不能冒称模型自主多步 E2E。
