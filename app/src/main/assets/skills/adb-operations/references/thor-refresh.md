# Thor 刷新率

适用 Thor_V1.0.0.377_20260206_165408_user。上/下屏支持 60/120 Hz；实际模式受固件、省电、温度和应用影响，不把设置值等同于两块屏幕实际刷新率。

先分别读取 settings get system min_refresh_rate、settings get system peak_refresh_rate 保存原始字符串。当前策略只允许写 60、60.0、120、120.0，均为白名单，无需 UI 确认。

升到 120：先 settings put system peak_refresh_rate 120.0，再 settings put system min_refresh_rate 120.0。降到 60：先 settings put system min_refresh_rate 60.0，再 settings put system peak_refresh_rate 60.0。不能让最低值大于最高值。

修改后逐项回读；需要核实实际屏幕时用 cmd display get-displays 或结构化显示信息，分别报告上/下屏当前模式。这个系统设置不承诺某个游戏实际达到 120 fps，也不是独立指定两屏各自刷新率的工具。

测试后按原值和正确先后顺序恢复，再回读。无需用户批准。不要用 wm size/density、未知 Binder 或重启来强行套用刷新率。查询返回 null 或不在已审核值内时停止修改，不能猜默认值。
