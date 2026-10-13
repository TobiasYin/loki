# Android 多屏、焦点、触摸与真实手柄

## 可确认的平台事实

- ActivityOptions.setLaunchDisplayId 提供目标显示参数，但活动能否在该显示启动仍受系统/显示权限影响。[官方 API](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int))。
- GestureDescription.Builder.setDisplayId 在 API 30 加入，结合 AccessibilityService.dispatchGesture 可以表达定向显示手势。[官方 API](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder#setDisplayId(int))。
- AOSP 将显示专属输入与非专属输入路由区分；多显示焦点受系统配置控制，不能据“双屏”断定两屏同时拥有独立输入焦点。[显示与焦点](https://source.android.com/docs/core/display/multi_display/displays)、[输入路由](https://source.android.com/docs/core/display/multi_display/input-routing)。
- 游戏接收按键和轴是不同事件语义。接收控制器的官方指南不代表普通应用可以向其他应用注入这些事件。[控制器输入](https://developer.android.com/games/sdk/game-controller/controller-input)。
- AOSP InputManager 的注入入口使用 INJECT_EVENTS 权限且属于隐藏接口；此处不是普通 APK 可直接调用的公开能力。[AOSP 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/input/InputManager.java)。

## 显示映射与失效

[决定] 不写死 displayId=0/1 对应上下屏。首次校准收集 DisplayManager 提供的信息，由用户确认 upper/lower；保存角色与可用标识，运行时再次核对。重启、折叠状态变化、外接显示、显示销毁/重建、分辨率或旋转变化使 displayEpoch 增长，旧动作快照失效。

下屏启动优先正常 Activity 指定已确认 display；若固件不允许，记录实际由 Thor 启动器移动应用的方式作为候选交互，不声明已支持自动定位。不可用时提示用户定位应用，不擅自更改系统多屏配置。

输入法出现、系统弹窗、下屏聊天获得焦点、上屏游戏暂停等必须进入测试矩阵。当前目标应用是上屏窗口事实，不是“最近拥有全局焦点的包名”。

## 跨屏触摸

首版工具：input.tap、input.long_press、input.swipe。参数包含逻辑目标 upper、坐标/路径、duration、frameId 或明示坐标系、预期目标（已知时）。默认单指，长按/滑动最大持续时间暂定 5 秒；手势资源串行。

路径：Runtime 请求 → Native 网关 → 指定 displayId 的 Accessibility 手势。screen_control 必须 ON，无障碍手势条件必须满足；不要求 adb_debug。没有当前截图也可接受用户明确坐标，但必须校验显示尺寸和上屏映射；禁止自动定位下屏按钮替代上屏。

成功返回 submitted 和系统回调结果，不宣称游戏状态一定改变。需要确认效果时，由 Agent 在感知开启时另调截图/UI 工具；关闭感知时只能报告注入状态。

## 真实原生手柄：条件性方案

[需求] 必须覆盖游戏收到的按钮与模拟轴，不以屏幕虚拟按键或普通键盘事件冒充手柄。

| 路线 | 可取之处 | 风险与结论 |
|---|---|---|
| 普通 APK 直接跨应用注入 | 无额外服务 | 公开能力不足，不作为首选可行方案 |
| Shizuku UserService＋受限输入 Binder 适配 | 可在 shell 身份执行受限高权限逻辑 | 非 SDK 接口、权限和 OEM 行为待证；首选 PoC 路线 |
| shell input gamepad/joystick | AOSP 命令识别这些 source，可测按键基线 | source 名存在不等于可注册真实设备、提供完整轴、通过模拟器过滤 |
| 虚拟 HID/uinput/厂商接口 | 可能提供设备身份和轴能力 | 节点/SELinux/固件接口/权限不可假设，可能需 Root 或厂商合作 |
| VirtualDevice 系列 API | 有虚拟设备相关平台能力 | 不能把其存在当成普通 sideload APK 可给物理上屏游戏注入；角色/权限/显示约束单独审查 |

AOSP Shell input 源码见 [InputShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/input/InputShellCommand.java)；VirtualDevice 权限说明见 [源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/companion/virtual/VirtualDeviceManager.java)。这些引用是平台事实，不是 Thor 固件版本证据。

## 输入执行器契约

- input.press_button：受支持按钮枚举，down/up 或有界 hold；记录 device/source 与目标 display；实际按钮列表由后端能力探测给出。
- input.set_axes：左/右摇杆归一化轴，扳机范围，duration；执行器映射到接收端真实轴范围，不能随意用触摸 motion 代替 joystick motion。
- input.release_inputs：只释放本 Run 已拥有的按键/轴；停止、权限撤销、目标失效、binder 死亡触发清理。
- 同时按键/轴必须在同一受限输入会话表达；最大动作时长暂定 5 秒，持续控制需分段，不允许无限按住。UserService 执行器设置不依赖 WebView 的租约看门狗；宿主失联时自行归零/释放。若服务自身死亡无法确认释放，应标记未知并阻止继续输入，POC-07 必须验证目标应用是否卡键。

screen_control 可以要求 Shizuku 系统授权，同时 adb_debug 仍 OFF。Native 仅暴露固定按钮/轴/时长方法，不提供任意命令字符串、脚本或“执行内部 Shell”逃生口。通用 Shell 只有 adb_debug 才能调用。

真实物理手柄和 Agent 同时操作容易产生冲突。首版在明确的短时操作区间内执行，用户手动停止优先；若不能可靠识别人为接管，不承诺自动接管检测，至少提供下屏常驻停止入口和租约超时释放。

## POC-07 的最低证据

后续授权开发后，先使用通过 POC-11 校准的独立 [Thor Input Lab](input-lab.md) APK 记录 source、deviceId、按钮 down/up、axes、时间、焦点与目标显示，手动 API 和 Agent 均复用相同用例与断言；再在至少两个用户指定的模拟器/游戏验证。覆盖下屏键盘开/关、聊天焦点、上屏触摸后、实体手柄并发、旋转与服务断开。

按键成功但轴不成立，或事件只能发给当前下屏，不算通过。无 Root 路线失败时维持该工具 unavailable，并提交范围决策；可以交付“部分触摸预览版”，不能标注完整满足 REQ-11。不会未经用户决策转为 Root 方案。

Input Lab 把 API 返回、系统送达事件和场景效果分层记录；source/deviceId 不作为发送者认证，普通事件不带 toolCallId。配对/归零不能由接收器失焦时自行重置伪造；无法观测释放标为 unknown。上屏未收到事件只能证明该窗口的观测，不足以推断误发下屏，需发送端/下屏记录补证。
