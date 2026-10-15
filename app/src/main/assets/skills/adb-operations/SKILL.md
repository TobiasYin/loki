---
name: adb-operations
description: 在用户要求读取 Thor 系统状态、调整双屏亮度、风扇、刷新率或复现设备配置时使用。包含白灰黑名单与操作手册。
---
# Thor 设备操作手册

通过已授权的 Shizuku shell UID 2000 执行；会话必须已启用 ADB 调试，不得自行打开权限。先用 debug_command_policy 检查当前分类或具体 command。模型提示和 Skill 都不能改变 Native 策略。

- 白名单直接执行：公开状态读取，以及限定参数范围的亮度、风扇（1–4）、刷新率（60/120 Hz）、超时和旋转等常用调整。无需页面批准；改前记住原值，改后回读，测试结束恢复。
- 灰名单逐条确认：可能含隐私的系统诊断/应用列表、影响按键分配的焦点锁和手柄映射变更。需要用户在聊天页点击本次批准；口头同意不能替代。拒绝、停止或过期后不得执行。
- 黑名单绝对禁止：抹除、格式化、刷机、分区、提权、关闭散热、清除应用数据、卸载、权限/连接设置写入，以及未知命令或参数。不能通过确认放行，不能换写法规避。

debug_shell 的 command 是单条设备命令，兼容开头 adb shell。直接执行审查后的 argv，不经 sh -c；禁止脚本、变量、管道、重定向、组合命令和未知程序。timeoutMs≤60000，maxBytes≤65536。输出可能截断，诊断尽量请求少量信息。

按任务只读取需要的资料：

- 上/下屏分别调亮度：[references/thor-brightness.md](references/thor-brightness.md)。使用 debug_thor_brightness，不用百分比设置项冒充实际副屏控制。
- 风扇模式与转速回验：[references/thor-fan.md](references/thor-fan.md)。不写硬件节点，不关闭散热。
- 刷新率：[references/thor-refresh.md](references/thor-refresh.md)，包含 60/120 Hz 的写入先后与实际回验。
- 旋转、焦点、手柄及其他常用配置：[references/thor-device.md](references/thor-device.md)。未审查项目只读或走系统界面，不猜枚举含义。
- 保存与复现配置：[references/reproducible-profiles.md](references/reproducible-profiles.md)。只在用户要求时创建配置 Skill，按需读取 skill-creator。

优先用已有结构化感知/输入/备份工具完成对应任务。读回真实状态后再报告成功；停止不能撤回已执行变更。密钥、网络密码和无关私人数据不写入手册或配置。
