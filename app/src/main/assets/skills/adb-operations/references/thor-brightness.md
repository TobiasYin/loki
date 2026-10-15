# Thor 双屏亮度

适用固件 Thor_V1.0.0.377_20260206_165408_user、Android 13。只在此固件启用专用接口，其他版本先验证，不猜 Binder 编号。


debug_thor_brightness 使用 Thor 固件 SettingsController 的限定亮度接口；Native 按 upper/lower 查找当前显示编号，不开放任意 Binder 调用。示例参数：

- 读取上屏：action=read, screen=upper。
- 读取下屏：action=read, screen=lower。
- 调上屏：action=set, screen=upper, value=96（白名单，直接执行）。
- 调下屏：action=set, screen=lower, value=80（白名单，直接执行）。

数值范围 0–255。先分别读取两屏原值；调整一屏后，分别回读两屏，确认目标值匹配且另一屏未变。固件读取包含浮点取整，允许 0–255 标度上最多 1 的量化误差，工具会明确返回 requested、brightness 和 quantizationTolerance；不能把更大的差异当作成功。原值、requested、brightness、verified、displayId 都会返回。测试完用保存的两屏原值分别恢复；恢复同样直接执行。自动亮度或第三方亮度应用可能覆盖设置，遇到不一致先报告，不能盲目循环重试。

settings get system screen_brightness 是上屏兼容整数记录。settings get system dual_screen_brightness_level 是下屏百分比记录（0–100），与工具 0–255 单位不同，可能有取整误差。直接 settings put 后者只改存储值，不保证立即改变背光，因此该写命令不开放。cmd display set-brightness 的 0–1 数值仅用于主屏通用路径，不支持凭空添加 -d 参数控制副屏。

可用白名单 cat 分别读取 /sys/class/backlight/panel0-backlight/brightness 与 panel1-backlight/brightness 及各自 max_brightness，作为硬件读回旁证。这台设备面板最大值为 4095；它们与 0–255 不是同一标度，也可能受 PWM、调光、灭屏和动画影响。绝不写 sysfs，绝不通过重定向或 root 修改面板。


read 和限定范围的 set 均为白名单，无需用户确认；上/下屏的写入仍执行 Native 权限、租约、屏幕识别和参数校验。
