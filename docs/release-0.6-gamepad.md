# 0.6 手柄后端、释放边界与实机验收

本记录说明 0.6 的原生手柄实现与验收范围。真实按钮、真实控制器轴、模型选用工具和具体游戏响应是不同的证据层次；不能因为工具返回成功，就宣称所有游戏兼容。

## 为何轴不能沿用 MotionEvent 注入

先前真实 Agent 调用中，A 按钮已在 Input Lab 观察到完整 down/up 与 GAMEPAD 来源。但 InputManager 的 KeyEvent 和 MotionEvent 都被本机重写为 deviceId=-1；KEY 接收端的原有场景只检查来源和配对，因此会判 pass，不能据此宣称真实 Odin 身份。MotionEvent 轴的 advertisedAxisIds 为空，即使偏转及归零到达，该轮 LAB-07 仍判 fail。严格组件测试保留了真实身份断言，没有把 -1 或 Virtual 放宽为成功。

失败原始证据保存在开发工作区的 `build/physical/release-0.6-2026-10-16/axes-03-debug-events.jsonl`。这是本机观测结论，不代表所有 Android 版本都具有完全相同的行为。

## 当前实现

| 能力 | 后端 | 目标约束 | 支持范围 |
| --- | --- | --- | --- |
| 按钮 | Shizuku shell UID 2000 写入真实 Odin Controller 的固定 EV_KEY + SYN_REPORT 帧 | 必须确认当前物理焦点显示与 APP 匹配授权上屏目标；不能凭 displayId 路由 | phase 仅 hold；固件映射与 getevent 状态已核对的按钮；默认 100 ms，最长 5000 ms |
| 轴 | Shizuku shell UID 2000 打开真实 Odin Controller evdev 节点，写固定二进制 EV_ABS 帧和 SYN_REPORT | 必须确认当前物理手柄焦点所在显示与 APP 匹配授权上屏目标；不能凭 displayId 路由 evdev | 仅 X/Y/Z/RZ/GAS/BRAKE；摇杆 -1..1，扳机 0..1；最长 5000 ms |

没有键盘、触摸映射或 InputManager 注入的替代路径；没有创建额外的内核控制器。应用会话仍需开启屏幕操作，Shizuku 则必须已经授权且实际 UID 为 2000。此结构化手柄操作不另外要求打开 ADB 调试能力，也不会自动改写焦点锁。

最终按钮与轴都使用 evdev 的实现采用 Shizuku UserService version 10。安装最终 APK 后必须让服务重新绑定并采用新版本，不能只根据应用版本或 force-stop 判断 shell 进程已更新；之前启动的 version 8/9 classloader 不包含最终按钮修复。

`perception.device_info` 的 gamepad 字段提供当前控制器 ID、sources、buttons、实际 motionRanges，以及独立的 buttonBackend、axisBackend、axisBackendError。按钮和轴 readiness 都依赖实际可写的 evdev 后端与已验证的布局；不能根据 InputManager 反射入口存在，就声明真实控制器身份可用。

## 发现、校准与有界提交

轴后端限定在 64 位小端 AYN Thor 的 shell 服务内运行。它从 `/proc/bus/input/devices` 发现唯一 Odin Controller，核对 Android InputDevice 的名称与 vendor/product，再核对 sysfs 与设备节点身份。event 编号不是常量，不能假定永远为 event9。

仅通过固定参数的 `getevent -lp <已验证节点>` 读取实际 min/max、flat 与当前值。对应的系统 key layout 只读，用于确认六轴名称与内核 code；不根据未验证的别名推断轴。该次真机发现对应 `Vendor_2020_Product_0111.kl`，摇杆范围为 -32767..32767，GAS/BRAKE 为 0..32767；这些数据用于核对实机，运行时仍重新读取。

按钮使用固定公开名称到内核 code 的映射，并同时核对固件 key layout 和 getevent 已报告标签。本机 A 为 0x130/BTN_GAMEPAD，X 为 0x133/BTN_NORTH，Y 为 0x134/BTN_WEST；不会把另一设备的命名习惯当作映射。getevent 的 KEY 能力信息通过 EVIOCGKEY 查询当前状态，已按下标签末尾带 *，目标按钮已按下时拒绝操作。该行为依据 [Android 13 getevent 源码](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-13.0.0_r1/toolbox/getevent.c) 及本机能力输出核对。

请求轴初始值必须处于其实际 neutral ± flat 范围。不能用接收端验收时的 5% 容差掩盖用户正在持有的物理输入。数据读取与打开 fd 属于 prepare 阶段，位于 `cp.effect` 外。准备结果绑定 lease 与完整请求 hash，2 秒内有效；实际 start 只做最终校验及固定帧提交，不在提交锁中等待整个持有时长。

按钮每帧只包含一个已验证的 EV_KEY 及一个 SYN_REPORT，值只能为按下 1 或松开 0；轴帧仅包括请求的六轴子集与一个 SYN_REPORT。二者采用 arm64 的 24 字节 input_event 结构。调用者不能指定任意节点、事件类型、内核 code 或未经归一化的原始数值。打开时禁止跟随设备节点符号链接，核对 stat/fstat；持有期间核对原节点、sysfs 与 Android 控制器身份。

## 停止与清理

服务独立定时结束有界动作。应用轮询也持续检查授权与目标；用户停止、lease 更换、过期、宿主 Binder 死亡或目标失效会触发清理。定时器使用已捕获的 lease 检查存活，不会错误地拿服务线程的 shell UID 冒充应用 IPC caller。

松键/归零只写入本次动作已经打开的原 fd，并只涉及该次请求的按钮或轴；控制器重建后绝不重新打开新节点继续写。只有已经尝试提交输入的动作才会发 UP 或 neutral；仅准备而未提交时只关闭资源，不制造输入。服务自身被 SIGKILL 时，现有物理 evdev 节点没有临时 uinput 设备关闭时的内核释放保证，不能承诺绝无卡键。

清理回传区分 releaseAttempted、releaseAcknowledged、resourceClosed、cleanupComplete 与 cleanupError。`released` 只能表示实际 UP/neutral I/O 被接受，不能根据 job 是否存在返回 true；归零失败仍关闭原 fd 并保留不完整状态。输入曾被尝试提交、后续失败时保守记为 unknown，不报 not_submitted。工具提交与清理 I/O 均不证明具体游戏已经消费了事件。

耗时的只读元数据发现不持有动作定时器或服务清理 monitor，避免一次状态刷新中的 getevent 等待阻塞释放。系统调度、Binder 或设备 I/O 仍可能引入延迟；5000 ms 是请求持有上限，不是 Android 的硬实时承诺。

## 当前目标守卫与组件测试

持有操作通过 `Checkpoint.checkingTarget` 继续验证同一授权、显示及结构目标，允许自身输入造成的内容反馈；新输入仍要求严格的新鲜快照。子 checkpoint 的已提交状态必须传播给外层 Gateway，尤其是发生部分写入后返回错误的情况，否则会错误地产生 not_submitted。相关传播回归由主集成流程维护。

`PhysicalGamepadComponentTest` 使用真实 AndroidBackend/Gateway/Shizuku 与独立 Input Lab，不调用模型、不清库、不改配置或凭证，不手动校准屏幕。它通过正常启动的自动屏幕角色识别建立目标，接收端明确使用 manual_platform_api，agentRunId 以 no-agent 标记；不能归类为 agent_e2e。

四轮为 A 持有 200 ms、六轴持有 300 ms，以及按钮/六轴请求持有 3000 ms、在 Native 实际提交确认后 100 ms 停止。每轮使用 10 秒接收观察窗口。ARM 前只通过普通前台 launch 建立 Lab 焦点，并只读 dumpsys input 的首个 FocusedDisplayId 与对应 FocusedWindows，要求上屏 Lab 窗口连续稳定；错误时只在 ARM 前重试普通 launch，不改焦点设置或强制 focus task。ARM 后等待 700 ms 并验证严格目标稳定，提交前再核对焦点。诊断保存在每轮 focusDiagnostics，错误也不丢弃。组件测试兼容持有目标守卫：使用同一个真实目标快照，取消触发对应 ManualTestAuthority，并通过独立原始事件验证源、设备 ID、down/up、六轴偏转及最终中立。接收端原始文件封存前缀的字节数和 SHA-256 必须一致；清理确认后不得出现新生成的非零事件。失败时仍尝试保存各轮报告，再汇总失败。

运行类为 `dev.thor.agent.PhysicalGamepadComponentTest`，参数为 `physicalAcceptance=true`、动态确认的 labDisplay 与 chatDisplay。当前 Thor 测试值分别为 0 和 4，换设备或显示重建后需重新发现。

## 最终实机证据与结果

开发工作区的原始验收目录为 `build/physical/release-0.6-2026-10-16/`，该目录包含私有测试数据，不应整体提交。可提交证据已归档至 `tests/release-0.6-evidence/receiver/` 与 `tests/release-0.6-evidence/components/gamepad/`，不拷贝整个私有原始目录。只读硬件发现原始文件位于其 `evdev-discovery/` 子目录，包括 getevent、proc devices、node 权限、key layout 与 sysfs identity。

最终真实模型 `receiver-06` 使用 deepseek-v4.1-flash 与真实 OpenCode Go Responses，logical、frame、A 和六轴四轮的 runStatus 均为 completed，工具 error 均为 null，独立接收端场景全部 pass。截图轮同时完成上屏 APP 感知、完整截图、从画面定位 START 并点击。按钮原始记录的 deviceId 为真实 Odin 30、source 为 1281（包含 GAMEPAD）；六轴 deviceId 为 30、source 为 16777232，轴 0/1/11/14/23/22 均观察到偏转并最终全部为 0。最终按钮也具有真实控制器身份，没有放宽 -1 或 Virtual。

历史 `receiver-05` 的模型四项场景也曾 pass，但按钮仍为虚拟 deviceId=-1；严格的 `native-components-01` 正确揭示这一缺口。该历史结果不能替代最终版本的身份验收，原始失败记录保留。

最终按钮改为 evdev 后的 `native-components-02/gamepad` 前三项通过：普通 A 实际持有 201 ms、六轴完整偏转后归零、A 在实际持有 102 ms 时停止并观察到 UP；均为真实 deviceId=30。第四项六轴取消在提交前因实际焦点不在上屏被守卫拒绝，outcome=not_submitted、原始输入数为 0。这是安全拒绝，不算六轴取消成功，组件整体仍为 passed=false。测试 setup 随后增加 ARM 前真实焦点稳定检查与普通前台 launch 重试，生产守卫未放宽。

最终 `native-components-03/gamepad` 汇总 passed=true，仪器测试共 2 项（焦点解析回归、含四轮真实场景的组件测试）。A 按钮实际持有 200 ms；六轴非零到归零间隔 300 ms；请求持有 3000 ms 的 A 与六轴分别在实际持有 102 ms、103 ms 后停止并观察到释放/全轴归零。四轮均为真实 Odin deviceId=30；按钮 source=1281，轴 source=16777232。两个取消动作分别在停止请求后 2 ms、3 ms 收到清理确认，releaseAcknowledged/resourceClosed/cleanupComplete 均为 true、cleanupError=null；原始日志确认清理后没有新按下或非零轴事件。取消工具结果为 CANCELLED/unknown，正确保留已提交输入的事实，未误报 not_submitted。

| 验收项 | 可提交证据位置 | 最终状态 |
| --- | --- | --- |
| 原生按钮 200 ms | [按钮报告](../tests/release-0.6-evidence/components/gamepad/physical-gamepad-button-report.json) 与对应 events.jsonl | component-03 pass，真实 Odin 30，实际 200 ms |
| 原生六轴 300 ms 及归零 | [六轴报告](../tests/release-0.6-evidence/components/gamepad/physical-gamepad-axes-report.json) 与对应 events.jsonl | component-03 pass，真实 Odin 30，实际 300 ms，六轴最终为 0 |
| 按钮停止释放 | [按钮取消报告](../tests/release-0.6-evidence/components/gamepad/physical-gamepad-button-cancel-report.json) 与对应 events.jsonl | component-03 pass，实际 102 ms，完整 DOWN/UP，清理后没有新按下 |
| 六轴停止归零 | [六轴取消报告](../tests/release-0.6-evidence/components/gamepad/physical-gamepad-axes-cancel-report.json) 与对应 events.jsonl | component-03 pass，实际 103 ms 后六轴全零，清理后没有新非零事件 |
| 组件汇总与发送端时间 | [组件汇总](../tests/release-0.6-evidence/components/gamepad/physical-gamepad-component.json) | passed=true，四轮通过，真实身份、日志前缀完整性与清理均通过 |
| 模型逻辑/截图坐标、A 与六轴 | `tests/release-0.6-evidence/receiver/` 下汇总、四份 report 与原始 events | receiver-06 四项 pass、run completed、工具无 error；按钮及轴均为真实 Odin 30，轴最终全零 |

测试通过时仍须保留 Input Lab 的 receiverQualificationVersion 与 systemInjectionProven 原始字段，不得把单次 Android dispatch 原始记录升级为系统发送者认证或全游戏兼容结论。
