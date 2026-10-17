# Agent 文字和键盘输入验证

本轮新增 `input.type_text`（Unicode 字段编辑）和 `input.key_press`（固定键和组合修饰键），均为选定屏幕上的 screen_control 能力。工具契约、Native 后端、模型说明、选区感知、界面名称和按需加载的内置操作手册同步更新。

- `software/`：474 项 JVM 检查结果，无失败、错误或跳过；包括当时新增 Core 28 项。Runtime 254 项、聊天页面 73 项为单独本轮执行，不能与 JVM 混称一套测试。各构建执行／UP-TO-DATE 区别、来源哈希和重点 XML 分别保存。
- `software-fixture-fix/`：唯一的测试页面源修正。移除本地键盘监听器的 EditText 仍被 Android 声明可编辑并支持 SET_TEXT，改为真正的普通 TextView。App 和 Test APK 字节不变；更新后的 Input Lab 为 `b8c2944f…`。第一次组件失败保留，未将其算作真实按键通过。
- `software-focus/`：新增实际 focused 感知及保护回归，JVM 合计 475 项（Core 230，含本次新增 29 项）；Enter 后由正式 Agent 工具重新激活输入框并核对焦点，再执行快捷键。新增提示文案曾使一项上下文预算测试失败，缩短说明后 254 项 Runtime 测试全部通过，未扩大预算或改变断言。早期结果不重复累计。
- `debug-fixture-boundary.json`：实际 Debug APK 含测试 Activity／Provider，Release APK 均不含。

最终焦点观测补充后的生产 App 固定 SHA-256 为 `303413e5fe06a3ef0ebcf1a251c81168d53b433dc7ac6de466b89f2c915c2737`，完整三包指纹见 `software-focus/pins.json`。APK 二进制不复制到公开记录目录。

模拟器测试使用正式 Native RPC、普通 Activity、独立接收端回调与真实 Shizuku 服务，不使用云模型，不以测试注入替代生产键盘后端。最后一次 5037 设备检查无已连接 Thor，因此本轮不声称已安装真机或完成 Citra 游戏目标。

数据库、凭据、原始会话历史和未净化诊断仅保留在忽略的 build 目录。此目录的源码哈希是所选变更文件清单，不是整个仓库的原子快照。

最终键盘组件（第三组固定产物）已通过：149.781 秒，文字及按键两个阶段均通过。独立接收端确认 Unicode 替换／插入／清空、拒绝场景、自然过期和跨 Run 隔离；Enter 后先重新观察并正式激活目标字段，再通过 Agent 可读的 focused／textSelection 验证 Ctrl+A 和 Backspace。

原始 Activity 日志确认 Enter 和 Ctrl+A 组合的 6 条键事件，来源 257、设备 -1，CTRL 松开时修饰状态归零。Backspace 的实际删除由独立 TextWatcher 确认，Activity 未观察到其原始 DOWN/UP，不将系统接受注入混称为每个按键原始事件均被 Activity 接收。前两次组件失败保留独立结果，不转记为通过。

同一组最终产物还通过动态输入回归：动态旧截图可点击，真实滑动产生 1376px 滚动；真实控件移位、弹窗及滚动后的旧帧仍拒绝。没有将本轮结果扩展为云模型自主操作、真机或游戏验收。

`emulator/` 保留三轮独立组件结果及固定指纹，前两次失败未转记为通过。最终恢复核对通过：126 个运行均终态，全部原始会话与五表记录／内容、选中会话、原始配置、授权、无障碍和系统设置保留，运行服务退出，Shizuku 恢复测试前的停止状态；内置 adb-operations 的预期版本升级单独记录，不回写覆盖新版手册。
