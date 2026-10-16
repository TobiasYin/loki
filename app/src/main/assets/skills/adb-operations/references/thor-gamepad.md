# Thor 手柄事件与上屏控制

先通过 perception_device_info 发现当前屏幕、物理控制器和 motionRanges，再读取上屏的 app/window/screenshot 信息。不要保存或重用固定的控制器 ID；固件可能重建 Odin Controller。手柄工具与触控工具都要求会话开启屏幕操作，实际后端还需要已授权的 Shizuku shell UID 2000；不另外要求打开 ADB 调试能力。

input_press_button 与 input_set_axes 都使用固定 evdev 后端写入真实 Odin Controller，分别只写已验证按钮的 EV_KEY 帧或请求的 EV_ABS 轴及 SYN_REPORT，由系统实际分发 SOURCE_GAMEPAD/SOURCE_JOYSTICK 与控制器 ID。二者不使用键盘事件、触摸映射或 InputManager 注入替代，也没有创建新的内核控制器。

按钮和轴事件都遵循物理手柄焦点，不能通过 displayId 自动路由。后端必须确认当前焦点显示及 APP 都是授权的上屏目标；焦点错误直接拒绝，不自动改灰名单的焦点锁。开始前重新读取 getevent -lp 的范围与当前值，并核对只读系统 key layout。getevent 的 KEY 标签后带 * 表示已按下，目标按钮已按下时拒绝；请求轴已非中立时也拒绝，避免覆盖用户正在持有的输入；当前已审查六轴为 X/Y/Z/RZ/GAS/BRAKE。本机接收端已观察到 Android InputManager 的 KeyEvent 与 MotionEvent 注入都可能把 deviceId 改成 -1，因此不能作为真实手柄身份的替代后端。

## 按钮与摇杆

- 按钮示例：`{"button":"A","phase":"hold","durationMs":100}`。phase 只有 hold；调用在同一个有界操作中按下并松开，不支持跨工具调用维持 down/up。可选按钮 A/B/X/Y、L1/R1/L2/R2、L3/R3、START/SELECT/MODE 和 DPAD_UP/DOWN/LEFT/RIGHT；实际控制器没有报告、固件 key layout 不符或当前已按下的按钮都会被拒绝；A 的本机标签为 BTN_GAMEPAD，不能凭其他设备习惯猜 BTN_SOUTH。
- 摇杆示例：`{"axes":{"X":0.6,"Y":-0.4},"durationMs":300}`。X/Y 通常为左摇杆，Z/RZ 通常为右摇杆，以本机 motionRanges 为准。X/Y/Z/RZ 范围 -1..1；GAS/BRAKE 为 0..1。仅支持这六个经过本机 key layout 与范围验证的轴；其他轴或别名直接拒绝，不猜缺失映射或把扳机当成触摸。
- 所有持有最长 5000 毫秒，普通点击从 100 毫秒开始；每次动作结束自动松键或回归中立。不要靠连续长持有模拟永久按键。用户停止、授权撤销、目标变化、lease 过期或宿主死亡都会触发本 Run 的清理；input_release_inputs 不释放其他任务拥有的输入。

## 如何判定成功

工具返回 submitted/released 只表示内核接受了固定 evdev 按键/轴帧及释放写入。某个游戏可能过滤注入事件、根据焦点接收、受 Thor 内置映射影响，或把模拟扳机映射到别的动作。必须用独立接收端的原始 keyCode/action/source/deviceId 或 axis 值，以及上屏画面变化核对，不能据工具回传直接宣称游戏已响应。

服务有独立的有界释放计时器，即使聊天界面不再轮询也会清理。不过服务进程本身被系统强杀（SIGKILL）时，向现有物理 evdev 节点写入没有临时 uinput 设备关闭时的内核归零保证。这是已知限制，不能描述为绝对不会卡键；短动作优先，在接收端验证 down/up、非零轴及最终中立状态。

每次输入前重取目标快照，目标 APP、显示设备或控制器重建后停止本次操作再重新观察。不要复用此前游戏中的坐标或声称一次接收端测试代表全部游戏兼容。
