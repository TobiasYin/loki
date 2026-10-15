# Thor 刷新率

适用 Thor_V1.0.0.377_20260206_165408_user。两屏公开的支持模式均包含约 60/120 Hz；本机实测中，写 min/peak=120 后设置回读为 120，但两屏实际仍约 60 Hz。标准 user-preferred-display-mode 与帧率匹配通知对照也未使实际模式变化。当前不能宣称此固件的 120 Hz 切换已经验证可用。

先分别读取 settings get system min_refresh_rate、settings get system peak_refresh_rate 保存原始字符串。当前策略只允许写 60、60.0、120、120.0，均为白名单，无需 UI 确认。

升到 120：先 settings put system peak_refresh_rate 120.0，再 settings put system min_refresh_rate 120.0。降到 60：先 settings put system min_refresh_rate 60.0，再 settings put system peak_refresh_rate 60.0。不能让最低值大于最高值。

修改后必须逐项回读，并用 cmd display get-displays 或结构化显示信息核对两屏当前 mode 对应的 fps；不要只看支持模式列表里的 120。实际仍是 60 时报告未生效，恢复原设置并停止反复尝试。固件中该设置流程含下屏亮度渐变，前后也核对两屏亮度，必要时仅恢复已记录的原值。不要通过原始 sysfs、PServer Binder、未知事务或 root 绕过固件。

测试后按原值和正确先后顺序恢复，再回读。无需用户批准。不要用 wm size/density、未知 Binder 或重启来强行套用刷新率。查询返回 null 或不在已审核值内时停止修改，不能猜默认值。
