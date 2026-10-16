# Thor 双屏亮度

适用固件 Thor_V1.0.0.377_20260206_165408_user、Android 13。只在此固件启用专用接口，其他版本先验证，不猜 Binder 编号。


debug_thor_brightness 使用 Thor 固件 SettingsController 的限定亮度接口；Native 按 upper/lower 查找当前显示编号，不开放任意 Binder 调用。示例参数：

- 读取上屏：action=read, screen=upper。
- 读取下屏：action=read, screen=lower。
- 调上屏：action=set, screen=upper, value=38（38%，白名单，直接执行）。
- 调下屏：action=set, screen=lower, value=31（31%，白名单，直接执行）。

常规亮度调整统一使用此工具。value 只接受 0–100 整数百分比，0 表示最低、100 表示最高；小数、负数和大于 100 的数均会拒绝，不会静默取整或截断。返回 unit=percent，before、requested、brightness 也都是百分比。例如 brightness=49 表示 49%，无需再换算。

先分别读取两屏原值；调整一屏后，分别回读两屏，确认目标值匹配且另一屏未变。Native 内部将百分比换算为最接近的固件整数，再严格核验固件原始回读与转换目标相差最多 1 个固件单位。公开 quantizationTolerance=1 表示显示百分比可能相差最多 1 个百分点；只有 verified=true 才算固件回验通过，不能自行用百分比容差覆盖失败。测试完用保存的两屏百分比分别恢复；百分比量化可能使底层恢复值与原始硬件记录相差 1 个固件单位。恢复同样直接执行。自动亮度或第三方亮度应用可能覆盖设置，遇到不一致先报告，不能盲目循环重试。

通用 debug_shell 保留系统命令的原生单位，不能把它们的数字混入本工具的百分比结果。settings get system screen_brightness 是上屏兼容原始整数记录（0–255），settings get system dual_screen_brightness_level 是下屏存储百分比（0–100），都只作明确标注单位的诊断。直接 settings put 后者只改存储值，不保证立即改变背光，因此该写命令不开放。cmd display set-brightness 使用原生 0–1 主屏值，不支持凭空添加 -d 参数控制副屏；用户明确需要原始命令时才用，不代替普通双屏百分比调整。

可用白名单 cat 分别读取 /sys/class/backlight/panel0-backlight/brightness 与 panel1-backlight/brightness 及各自 max_brightness，作为硬件读回旁证。这台设备面板最大值为 4095；这是面板原始标度，不能当百分比显示，也可能受 PWM、调光、灭屏和动画影响。绝不写 sysfs，绝不通过重定向或 root 修改面板。


read 和限定范围的 set 均为白名单，无需用户确认；上/下屏的写入仍执行 Native 权限、租约、屏幕识别和参数校验。
