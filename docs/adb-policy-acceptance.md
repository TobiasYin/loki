# ADB 命令分级与 Thor 常用配置 · 0.4.0

本轮依据用户最终要求：亮度、风扇、刷新率等限定范围的常用调整无需批准；灰名单只保留需要用户确认的操作。策略版本为 2，App 为 0.4.0-integration / versionCode 4。

## 执行规则

| 类别 | 具体范围 | 执行行为 |
| --- | --- | --- |
| 白名单 | 公开状态读取；双屏亮度 0–255；风扇模式 1–4；刷新率 60/120 Hz；限定屏幕超时与旋转设置 | 当前会话已启用 ADB 调试后直接执行，不弹批准框；修改前记录、修改后回读、测试后恢复 |
| 灰名单 | 含使用/应用信息的诊断、应用列表、非公开系统属性；焦点锁、ABXY 与手柄鼠标分配等 | 显示具体命令及影响，逐条 UI 批准；拒绝、取消、过期或权限变化后不执行 |
| 黑名单 | 抹除、格式化、刷机、分区、提权、关闭风扇/散热、清除或卸载应用、权限/连接设置写入；未知程序/参数形式 | Native 与真实 UID2000 服务拒绝，不提供确认放行 |

共享策略在 ProductHost、AndroidBackend、DebugConnection、DebugUserService 校验。命令解析为独立 argv，只使用审核的 /system/bin 程序；实际生产路径不调用 sh -c。禁止变量、转义、解释器、管道、重定向、组合或任意路径二进制。单凭命令黑名单的子串匹配不足以构成拦截，因此未知形式一律拒绝。

debug.command_policy 读取规则或分类，不执行命令。灰名单批准绑定具体 run/request、完整参数指纹、截止时间和能力 epoch；真实执行端重新校验分类及批准摘要。伪造批准不能运行黑名单。固定的自身无障碍开启操作继续由 Native 管理，保留其他服务，不通过 Agent Shell 修改权限。

## Thor 双屏和配置 Skill

默认内置 adb-operations，和 skill-creator、skill-manager 一起安装。上下文只发布名称和描述；Agent 主动 skills_read 时才注入正文，具体参考文件再按任务读取。内置资源升级会更新正文/引用文件并保留用户的停用选择；用户 Skill 不被覆盖。

- thor-brightness.md：两块屏幕独立读取、调整、回验和恢复。
- thor-fan.md：本固件风扇模式、RPM/PWM、恢复及保护边界。
- thor-refresh.md：60/120 Hz 写入先后、设置值与两屏实际模式的区别。
- thor-device.md：型号/固件、屏幕发现、休眠、旋转、焦点、手柄、性能、充电和灯效入口。
- reproducible-profiles.md：配置快照、应用、部分失败与恢复，以及按用户要求保存为新 Skill。

副屏亮度百分比设置项不能直接证明背光改变。debug.thor_brightness 封装本机固件 SettingsController 的固定接口，按 upper/lower 发现当前显示编号；不把下屏编号永久固定为 4，不开放任意 Binder transaction。支持固件仅限 Thor_V1.0.0.377_20260206_165408_user，其他版本拒绝直调用。该版本读取分发表为 0x4004，和部分社区示例不一致；setter 为 0x4002。固件异步保存亮度，Native 在短提交后独立轮询回读，允许标度上的 1 单位取整误差，并返回 requested/brightness/verified/quantizationTolerance。

风扇名称/顺序来自本机固件 array_fan_speed 与实际控制逻辑：1 低速、2 中速、3 高速、4 智能；0 为关闭，禁止写入。只读暴露具体 speed/duty/period/state 等节点，不开放硬件写入。性能、充电保护等未经写入验证的配置只记录状态与系统界面入口，不猜枚举或宣称可直接写。

## 验证状态

软件回归已完成：59 项 JavaScript 测试、190 项 JVM 测试、Android lint、debug/release 与 instrumentation 构建、产物隔离检查通过。新增测试包含命令解析/绕过、黑名单不能批准、精确灰名单批准、直接 argv 不解释脚本、Thor 参数约束、内置 Skill 升级、结构化 UI，以及工具目录增大时的 L0 限制。

真实 OpenCode Go Responses 的文字/图片/工具/图片回传探测已重新完成，两个 HTTP 200，Native 配置状态为 passed；设备信息与 Brave 搜索 completed。此前本地预算 32K 导致多轮 Skill 操作提前超限，本机已通过实际 Native 设置页调整到 131072，修订 1002，保存后真实重探测；模型、端点与密钥不变。

另修复工具目录扩展后配置探测的 1 KiB L0 溢出：在此预算内保留不可用工具摘要并标记 omitted；独立有效工具集合和 Native 权限检查保持权威。没有提高 1 KiB 限制或手工伪造探测通过。

另完成真实 SQLite 临时库回归：1000 条历史事件、20 次增量保存共 201 ms，重新打开、更新和事务回滚通过，临时库已删除。保存路径改为事务内批量读取比较，页面只投影当前会话及活动运行；完整历史仍保存在 Native。

双屏亮度、风扇、刷新率、灰名单正负分支和最终云模型接收端重复测试正在进行；以下最终证据将在实测结束后补齐，不以构建或分类单测代替真机结论。

## 可复现入口

使用根工程构建，保留现有 Native 连接资料与数据库，不运行会重置资料的 WebViewFlowTest/fixture 安装流程。

- scripts/physical-live.py --reuse-profile：真实图片/工具探测、设备信息和 Brave；--configure-budget-128k 通过 Native 设置页保存预算并重新真实探测。
- scripts/physical-adb.py：真实 Agent 白/灰/黑名单与双屏亮度；--fan 测风扇，--refresh 测刷新率。只有灰名单验收自动点击产品实际批准/拒绝按钮，不冒充真人确认。
- scripts/physical-device.py：真实模型、Native 触控和独立接收端闭环，包含原始事件前缀哈希核对。

测试使用真实保存的 OpenCode Go / deepseek-v4.1-flash，不配置模拟模型，不读取明文密钥，不手工更改 probe 为 passed。清理只恢复本轮实际修改的设备状态并关闭测试会话的调试能力。自动测试框架清理不是产品具备任意系统修复能力的证明。

参考：[Android 13 DisplayManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/display/DisplayManagerShellCommand.java)、[Pi 的 OpenCode Go 模型参数](https://pi.dev/models/opencode-go/deepseek-v4-1-flash)、本机固件与实际设备回读。128K 为本地预算设置，不是独立测量服务上下文容量。
