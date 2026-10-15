# Thor 风扇控制与实际回验

适用固件：Thor_V1.0.0.377_20260206_165408_user，Android 13。先读取型号与固件。模式来自本机实际 GPIO5 控制分发表与 PWM/RPM 交叉验证，共享的 array_fan_speed 是其他机型菜单，不能直接套用。2/3 在本机不能作为中/高速，6 为尚未开放的自定义模式。

## 模式与权限

settings get system fan_mode 为白名单读取。1 为低速、4 为智能、5 为高速；0 为关闭，当前 Agent 策略禁止写 0，禁止绕过散热保护。settings put system fan_mode N 只接受 N=1、4、5，均为白名单，直接执行，不需页面批准。拒绝、停止或权限变化后不得继续执行。

手动 fan_speed 数值、温度曲线和 sysfs 写入没有开放，不得用脚本、重定向、直接 Binder、setprop 或 root 改风扇。智能模式会根据温度和供电状态调整，不能承诺固定 RPM。供电、温度或其他应用也可能影响最终行为。

## 验证流程

1. 用 debug_shell 读取 fan_mode；分别执行以下白名单只读命令，记录基线：
   - cat /sys/class/gpio5_pwm2/speed：风扇转速读数（本固件按 RPM 使用）。
   - cat /sys/class/gpio5_pwm2/duty：PWM 占空控制的原始值。
   - cat /sys/class/gpio5_pwm2/period：PWM 周期原始值。
   - cat /sys/class/gpio5_pwm2/state：风扇启用状态。
   - cat /sys/class/gpio5_pwm2/thermal：固件热管理标志，不当作摄氏温度。
2. 请求 settings put system fan_mode 5 或用户指定的已支持模式，说明散热与噪音变化后直接执行。
3. 再读取 fan_mode，等待风扇稳定后重复读 speed、duty、state；必要时数秒后再读一次。切换命令成功、设置值读回和实际 RPM 是不同证据，逐项报告。不通过音频推测 RPM。
4. 如果设备高负载或温度异常，避免低速测试；优先保持高速或智能，不关闭保护。模式值变化而转速没有变化可能是智能/供电策略或外部应用覆盖，报告事实并停止盲目重试。
5. 测试完成用保存的原模式恢复，再读模式和实际状态；恢复无需页面确认。原模式为 0 时不得通过本策略恢复关闭状态，提前说明并选择不开始修改或由用户手动恢复。

不同模式不承诺固定 PWM 或固定 RPM。配置复现应保存模式语义、原值、供电条件和观察值，不保存一次采样转速当作永久目标。
