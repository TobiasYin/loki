---
name: adb-operations
description: 在用户要求读取 Thor 系统状态、读取组件树、输入文字或键盘快捷键、滚动列表、拖动滑块、控制上屏或手柄、调整双屏亮度、风扇、刷新率、用 curl 请求网络、下载和安装应用或复现设备配置时使用。包含白灰黑名单与操作手册。
---
# Thor 设备操作手册

通过已授权的 Shizuku shell UID 2000 执行；debug 工具要求会话已启用 ADB 调试，手柄和触控要求屏幕操作，不得自行打开权限。执行 shell 命令前先用 debug_command_policy 检查当前分类或具体 command。模型提示和 Skill 都不能改变 Native 策略。

- 白名单直接执行：公开状态读取，以及限定参数范围的亮度、风扇（1/4/5）、刷新率（60/120 Hz）、超时和旋转等常用调整。无需页面批准；改前记住原值，改后回读，测试结束恢复。
- 灰名单逐条确认：可能含隐私的系统诊断/应用列表、影响按键分配的焦点锁和手柄映射变更。需要用户在聊天页点击本次批准；口头同意不能替代。拒绝、停止或过期后不得执行。
- 黑名单绝对禁止：抹除、格式化、刷机、分区、提权、关闭散热、清除应用数据、卸载、权限/连接设置写入，以及未知命令或参数。不能通过确认放行，不能换写法规避。

debug_shell 的 command 是单条设备命令，兼容开头 adb shell。直接执行审查后的 argv，不经 sh -c；禁止脚本、变量、管道、重定向、组合命令和未知程序。timeoutMs≤60000，maxBytes≤65536。输出可能截断，诊断尽量请求少量信息。

按任务只读取需要的资料：

- 上屏应用导航、精简组件树、屏幕外文字、列表滚动及拖动滑块：[references/screen-navigation.md](references/screen-navigation.md)。这些结构化操作只依赖感知／屏幕操作能力，不需要 ADB 调试；不要因为手册名称而请求无关权限。
- 表单填字、中文／Emoji、光标处插入、清空、Enter／Tab／方向键及组合快捷键：[references/keyboard-input.md](references/keyboard-input.md)。文字编辑使用无障碍，键盘按键使用已授权 Shizuku；都属于屏幕操作能力，按需读取，不使用 Shell 绕过目标校验。

- curl 网络请求、GitHub 发布查询、下载 APK、ADB 安装与回验：[references/download-install.md](references/download-install.md)。安装需要本次 UI 确认，普通请求和下载无需批准。
- 上/下屏分别调亮度：[references/thor-brightness.md](references/thor-brightness.md)。常规调整使用 debug_thor_brightness，value 和回读亮度均为 0–100 整数百分比。不要把存储设置项冒充实际副屏控制；底层命令原生单位只用于明确需要的诊断。
- 查找指定应用并在上屏打开：[references/thor-apps.md](references/thor-apps.md)。用 perception_find_apps 按名称／包名／launcher 类名匹配，再用 apps_launch 启动；不要为找一个应用读取完整安装清单。启动后必须重新观察前台与画面。
- 风扇模式与转速回验：[references/thor-fan.md](references/thor-fan.md)。不写硬件节点，不关闭散热。
- 刷新率：[references/thor-refresh.md](references/thor-refresh.md)，包含 60/120 Hz 的写入先后与实际回验。
- 旋转、焦点、手柄及其他常用配置：[references/thor-device.md](references/thor-device.md)。未审查项目只读或走系统界面，不猜枚举含义。
- 原生手柄按钮、摇杆与扳机事件：[references/thor-gamepad.md](references/thor-gamepad.md)。使用有界 hold、明确上屏路由，核对独立接收端，不把 InputManager 提交当作游戏响应。
- 保存与复现配置：[references/reproducible-profiles.md](references/reproducible-profiles.md)。只在用户要求时创建配置 Skill，按需读取 skill-creator。

优先用已有结构化感知/输入/备份工具完成对应任务。读回真实状态后再报告成功；停止不能撤回已执行变更。密钥、网络密码和无关私人数据不写入手册或配置。
