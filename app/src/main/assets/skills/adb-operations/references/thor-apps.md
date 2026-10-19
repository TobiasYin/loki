# 查找并在当前屏幕打开应用

用户要求打开某个应用或游戏时，先用 `perception_find_apps(query="用户给的应用名")` 查找，默认最多 10 项，可指定 limit=1–20。query 必须为非空的 1–100 字符。匹配应用名称、包名和公开 launcher 类名；例如查 Citra 可以命中包名 `io.github.lime3ds.android`、launcher 类名含 `org.citra` 的 Lime3DS。这里只返回匹配的、当前可见的已启用公开 MAIN/LAUNCHER 应用，不列出完整安装清单。

从结果取真实 packageName，然后调用 `apps_launch(packageName="查到的包名")`。这属于当前选定屏幕的操作，需要本会话的 screen_control；查找需要 screen_perception。两者使用 PackageManager 与正常 launcher Intent，不依赖 ADB、不执行 Shell，也无需因启动应用而请求灰名单批准。不要为了找一个指定应用改用 `pm list packages -3` 或其他完整清单读取；那些命令仍遵守原来的灰名单。

启动只接受 packageName，Native 自行选择该包的已启用、已导出且调用者有权限的 MAIN/LAUNCHER activity，并使用当前任务选定的校准屏幕角色（默认上屏，可先 perception_select_screen 选下屏）。不能传任意 activity/component、URI、extras、displayId 或 task/data 清理选项；不能把 Loki 自己启动到操作目标屏幕。应用可能根据自己的启动模式复用已有任务。

`submitted=true` 只说明已经提交标准启动，`foregroundVerified=false` 不表示应用已成功到前台。启动后立即重新读取选定屏幕 app/window/UI 或截图，确认真实包名和画面，再决定下一次输入。系统拒绝、缺少 launcher、屏幕角色／授权变化时照实报告，不通过 Shell 或更改焦点设置绕过。

## 返回与应用内菜单

需要 Android 返回，或某个应用通过 Android Back 打开菜单时，使用 `input_navigate(action="back")`。这是通用导航，只发送一次固定 BACK 的 down/up，不是手柄事件；`input_press_button(MODE)` 不等于 Android Back，猜测边缘手势也不能替代应用的返回回调。

导航需要本会话 screen_control、当前选定屏幕的快照、该选定显示器的 focused application 与 focused window 均属于目标应用（显式路由的导航不要求全局焦点位于该屏），以及已经授予的 Shizuku shell 后端。Native 显式设置并核验显示路由；不会修改焦点锁。不能指定任意 keyCode、HOME、displayId、命令、长按、重复或其他参数。按下后即发送释放，停止时仍完成同一按键的释放清理；服务被 SIGKILL 的极短 down/up 间隙无法作绝对释放保证。`submitted` 仅证明提交，返回／菜单是否成功必须重新观察上屏确认；导航可能切换应用，因此不要继续使用旧树、旧坐标或旧截图。
