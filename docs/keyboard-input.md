# Agent 文字与键盘输入

新增两个模型可调用的工具，均属于 `screen_control`，绑定当前 Run 选定的屏幕。模型描述、输入契约、Native 执行器、工具展示名称和按需加载的设备操作手册已同步更新。

| 工具 | 行为 | 依赖 |
| --- | --- | --- |
| `input.type_text` / `input_type_text` | 对最新组件树的真实 `nodeRef` 输入 Unicode 文字；默认 insert 替换选区／在光标处插入，replace 全文替换，空 replace 清空 | 无障碍；可见、启用、非密码、可编辑且提供 SET_TEXT 的控件 |
| `input.key_press` / `input_key_press` | 一次固定键名和可选 CTRL／ALT／SHIFT／META 的完整按下、释放 | 无障碍确定目标；已授权 Shizuku shell 服务；所选屏幕的目标应用和窗口焦点 |

文字支持中文、Emoji 和换行，单次最多 4096 个 Unicode 码点。insert 按 Android UTF-16 选区插入，拒绝越界、单端缺失或切断代理对；未提供选区时追加。组件树提供实际 focused 状态，便于 Agent 在 Enter／Tab 可能移焦点后，通过观察、激活目标字段、再次观察来恢复快捷键目标；密码焦点不输出为 true。组件树为非密码的可编辑节点返回 `textSelection.start/end/unit=utf16`，它对应原始完整文本，不随树中文字裁剪偏移。不会自动回车、触发提交或修改剪贴板；应用可能过滤输入，Agent 需重新观察实际结果。

文字编辑复用已有控件激活的唯一窗口、Run／会话、树路径、节点身份、签名、有效期与可见边界校验，签名包含可编辑状态和选区。窗口相同但其他区域更新，不会单独拒绝稳定节点；目标字段变化仍需重新读取。自绘游戏界面如果不暴露可编辑节点，不能据此声称支持任意 Unicode 输入。

允许键名为 A–Z、0–9、ENTER、TAB、BACKSPACE、DELETE、ESCAPE、SPACE、UP、DOWN、LEFT、RIGHT、HOME、END、PAGE_UP、PAGE_DOWN。HOME 是文本移动行首，不是系统主页。键盘与物理 Odin 手柄使用不同后端和事件来源，不互相替代。按键不接受任意数字 keycode、Shell、持有、重复次数或模型提供的 displayId。

Shizuku 键盘准备阶段不发送事件，开始阶段再次检查焦点；每个事件设置并回读 displayId。所有尝试按下的键和修饰键在 finally 中逆序释放，取消和发送异常也继续清理。系统接受注入只表示 submitted，不等于目标应用已处理；重新观察后才报告成功。停止、撤权、真实窗口／应用／屏幕切换与助手自身操作保护保持生效。

实现参考 Android 的 [AccessibilityNodeInfo 文档](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) 中 SET_TEXT 和文字选区接口。源码回归包含 Unicode／选区、按键清理、真实 Gateway 授权与目标失效路径；构建与模拟器独立接收端结果单独保存于 `tests/keyboard-input-evidence/`，不把组件测试表述为云模型自主操作或真机游戏验收。
