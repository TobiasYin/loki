# AYN Thor 常用配置手册

## 兼容与屏幕识别

本手册基于真机 AYN Thor、Android 13/API 33、Thor_V1.0.0.377_20260206_165408_user。运行前读取 getprop ro.product.model、getprop ro.build.display.id，固件改变后重新核对支持范围。结果是兼容性依据，不是权限来源。

上屏当前为 display 0，逻辑横屏 1920×1080；下屏当前为 display 4，逻辑横屏 1240×1080，名称 Screen-2。编号会随显示设备重建改变，不能把副屏永远写死为 4。用 cmd display get-displays 或 perception_device_info 的显示信息发现当前屏幕；截图与触控使用本应用已校准的目标快照。不能把物理面板纵向分辨率直接当作触摸坐标。

双屏亮度的专用工具和单位说明见 [thor-brightness.md](thor-brightness.md)；风扇见 [thor-fan.md](thor-fan.md)，按任务读取。

## 常用显示设置

以下读取命令均为白名单，格式为 settings get system KEY；写入前先保存当前值，写入后读取同一项回验。

| 项目 | KEY | 已审核写入 | 复现注意事项 |
| --- | --- | --- | --- |
| 上屏自动亮度 | screen_brightness_mode | settings put system screen_brightness_mode 0 或 1 | 0 手动，1 自动；只在任务需要时修改，恢复原模式 |
| 屏幕超时 | screen_off_timeout | 整数 15000–1800000 毫秒 | 600000 为 10 分钟；不能设置永久不休眠 |
| 最低刷新率 | min_refresh_rate | 60、60.0、120、120.0 | 与最高刷新率成对管理，最低不得高于最高 |
| 最高刷新率 | peak_refresh_rate | 60、60.0、120、120.0 | 设置项读回不代表每个应用实际刷新率，需显示信息验证 |
| 自动旋转 | accelerometer_rotation | 0 或 1 | 两屏与应用可有独立方向策略；控制界面改变后重取快照 |
| 固定旋转 | user_rotation | 0、1、2、3 | 只有关闭自动旋转时有效，不能把固定旋转误当上下屏切换 |
| 屏幕焦点锁 | screen_focus_lock | 0 或 1 | Thor 专用，影响上下屏按键焦点；以当前系统界面的标注核实 |
| 系统键焦点锁 | enable_system_key_focus_lock | 0 或 1 | 影响系统键分配，先记录两项焦点状态并小范围验证 |
| ABXY 布局翻转 | flip_button_layout | 0 或 1 | 可能改变游戏接收的按键；当前游戏有输入持有时先松开 |
| 全局手柄鼠标 | global_gamepad_to_mouse_mode | 0 或 1 | 开启可能与其他映射应用冲突，需用户明确要求 |

亮度、刷新率、超时、旋转为白名单直接执行；焦点锁、ABXY 布局和手柄鼠标会改变按键分配，属灰名单，精确命令需页面确认。刷新率写入顺序：升到 120 时先提高 peak，再提高 min；降到 60 时先降低 min，再降低 peak。逐项读取确认，再用显示信息核对实际模式；系统省电、温度或应用策略可能限帧。不要为了回验擅自更改其他应用设置。

## 性能、散热、充电、灯效与手柄：先读再核实

这些是当前固件发现的常用配置入口。读取单个设置项可直接执行；对枚举未核实或涉及硬件保护的写入不开放，使用系统 Thor 设置页面及结构化屏幕工具时仍受屏幕操作权限约束。

| 类别 | 可读 KEY（system） | 如何复现 |
| --- | --- | --- |
| 性能模式 | performance_mode | 记录数值和 Thor 设置页显示的模式名称；不跨固件猜数值含义，不绕过温控 |
| 风扇模式 | fan_mode | 已审查 1 低速、2 中速、3 高速、4 智能的灰名单切换；先读 thor-fan.md；禁止写 0 关闭散热 |
| 双屏启停 | dual_screen_display_mode | 记录原模式并优先保留双屏；关闭下屏会影响聊天和确认操作，不能在无人可确认时关闭 |
| 充电上限 | percent_80_charge_limit | 记录开关及系统界面，涉及充电保护，不直接写值 |
| 充电分离 | is_charging_separation | 记录开关、电量和供电条件；不假设软件开关等于硬件生效 |
| 下屏音量 | secondary_screen_volume_level | 记录与上屏媒体音量的区别；未审查直接写入，使用系统音量界面 |
| 触发器模式 | trigger_input_mode | 可能不存在显式记录；系统界面提供模拟/数字/两者，不能猜 0/1/2 对应关系 |
| ABXY 临时布局 | temp_abxy_layout_mode | 记录当前系统界面与游戏映射，不将布局切换当作硬件输入注入 |
| 摇杆灯开关 | joystick_light_enabled | 可能是双值字符串，保留原结构，不把它当布尔整数 |
| 灯效亮度 | led_light_brightness_percent | 记录实际字符串和界面百分比；没有开放任意字符串写入 |
| PWM 模式 | display_pwm_value | OLED 调光选项依固件和屏幕而异；仅记录，不使用未经验证的写命令 |

若用户要把某个尚未审查的写操作纳入自动复现，应说明具体功能、原值、目标值及现有阻断原因，交由开发者审核；不得把未知命令当作“灰名单全部可批”。无法确认的枚举只保存原始值，不在回答中编造含义。

## 诊断与本机授权

id 应显示 shell UID 2000；禁止 root。读取 dumpsys battery、wm size、wm density 可直接执行；dumpsys display/power、窗口和应用列表等含使用信息的诊断属于灰名单。getprop 的公开型号与系统版本可直接读取；其他属性可能包含设备标识，需确认。

已有 Shizuku shell 授权时，应用会通过固定的 Native 操作自动启用自己的无障碍组件，保留其他无障碍服务。Agent 不能用 debug_shell 修改 enabled_accessibility_services 或 adb_enabled。模型、搜索和语音密钥由 Native 加密保存；手册与配置快照中不写密钥、序列号、网络密码或完整应用/窗口历史。

依据：本机设置与背光读回；Android 13 DisplayManagerShellCommand；Thor SettingsController 的读取分发表已经按本机固件核对，和社区部分示例不一致；不能直接复制其他版本的读取编号。固件接口不是 Android 跨设备标准。
