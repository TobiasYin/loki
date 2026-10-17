# Thor 实机键盘与默认上屏应用上下文验证

2026-10-17，AYN Thor / Android 13，上屏 0、下屏 4。使用保存的模型配置和授权，保留用户数据安装最终 App/Test/Input Lab 后，仅执行一轮显式 `PhysicalKeyboardInputTest`。最终 **1 项通过，27.664 秒**，没有重试。

- 原生组件通过产品 Native RPC 调用文字和键盘工具：中文与 Emoji 整段替换、选区插入、清空均由真实 EditText 回调验证。Enter 后通过新组件树和正式激活工具重新聚焦，再用 Ctrl+A 和 Backspace 实际选中、清空；未用 shell、UiAutomation 或夹具替代键盘输入。
- 独立上屏接收端密封了 21 条事件 / 3270 字节，SHA-256 `4a7620d5afdb32e048027959fee6d3d5ce6e16eab894e15dae82517da55868d4`，零丢失。键盘事件增量为 8，文字变化回调 5 次，编辑动作回调 1 次，提交按钮未触发。
- 首次 authority 默认包含真实 PackageManager 返回的 `Thor Input Lab`、`dev.thor.inputlab`、`0.4.0`、版本代码 `5`。正式后台工具腾出下屏后，在独立下屏 Activity 上正式选取 lower；默认上屏信息来源仍为 display 0，与 PM 一致。**上下屏夹具同属 Input Lab，此项不单独证明不同包之间的跨屏识别。** 下屏未执行输入，最终 touch/click 都是 0。
- 手动组件没有执行模型 Runtime 回合，正式 end 按正常产品规则把该 Run 记为 failed（“Runtime 未完成；不重放”）；组件通过结论来自工具和独立接收端断言。手动组件 Run 结束后，同一测试会话保留上下文，通过正式 Runtime 发起真实 `deepseek-v4.1-flash`（low）请求。未在问题中提供期望名称或版本；模型只依据默认上屏基础信息回答，0 工具调用，四个字段与 PM 全部一致。真实传输 HTTP 200 / EOF，模型 Run completed。此项证明真实模型收到默认应用信息；键盘部分仍是 Native 组件验证，不标作模型自主键盘端到端测试。
- 原有 183 会话、191 Run、382 消息、62708 事件、244 附件逐行所有字段和 payload 均未变化。四份配置 XML 逐字节相同，原选中会话不变；最终 193 Run 全部已结束。全部已记录权限 grant/完整 flags、无障碍服务列表、输入法与动画设置保持原值。
- 所有夹具已退出，上屏恢复原 Cocoonshell 主界面；使用正常 Launcher 启动后，Thor MainActivity 默认显示在下屏 4。

`component-report.json` 与 `receiver-events.jsonl` 只包含合成夹具数据、固定测试工具结果和模型字段比对结果。原始用户数据库、偏好配置、凭证、模型答案与网络请求/响应体保留在 ignored build，不进入此公开证据。
