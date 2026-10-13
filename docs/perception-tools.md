# 上屏最小动态上下文与按需工具

## L0：默认注入的唯一设备快照

[决定] 感知开启且可用时，只提供：deviceClass=AYN Thor、targetDisplay 的逻辑角色 upper、当前应用 label/package（未知可为 null）、observedAt、validity，以及有效能力摘要。完整 Build、应用版本、Activity、窗口标题、UI 文本、电池、日志和截图都不进入默认块。

目标 L0 ≤1 KiB 文本，单块替换。capabilityEpoch/displayEpoch 是内部控制字段，不必每次暴露给模型；快照标识可以在工具返回中供后续动作绑定。环境变化监听只维护必要元数据，不顺便遍历 UI 树或截图。

感知关闭：保留静态产品身份和能力状态，移除上屏应用名/包名及其动态更新。系统授权不可用：返回“未知/无权限”，不依赖最近一个应用名称当作实时事实。诊断 UI 可按用户操作显示更详细系统信息，不自动注入模型。

## Display 与应用识别

按 [双屏映射](displays-input.md) 的上屏 displayId 读取可交互窗口，区分应用窗口、系统弹窗、输入法和叠层。可以无法确定唯一应用；此时 L0 标记 ambiguous，不拿全局前台或下屏 Agent 包名代替。

[事实] API 30 的 getWindowsOnAllDisplays 能按屏获取可交互窗口，但不保证得到所有任务/隐藏窗口。需要相应无障碍配置；根节点可能为 null。应用版本查询受包可见性影响。[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[包可见性](https://developer.android.com/training/package-visibility)。

不提供名为“全系统运行应用列表”的普通感知工具。首版用 window_info 表达可观察上屏窗口；需要 dumpsys 的任务诊断必须归调试能力。Accessibility 事件 className 不能无条件当真实 Activity 类名。

## 公共结果约定

所有工具独立注册；返回 source、observedAt、displayRole/displayId（相关时）、snapshotId、availability、truncated。空内容与错误分开：游戏 UI 树只有 SurfaceView 可是成功但 empty，不应编造节点；SECURE_CONTENT、DISPLAY_GONE、RATE_LIMITED 等返回可理解错误。应用信息不猜包名，返回版本原值而非模型推测。

下表参数和预算是拟议契约，具体平台映射见 PoC；不包含任何实现代码。

| 工具 | 输入与默认值 | 输出 | 失败/限制 | 验收 |
|---|---|---|---|---|
| perception.app_info | target=current_upper 或已明确包名；fields 可选版本/安装信息 | label、package、versionName、longVersionCode、安装/更新时刻（可得时） | 包不可见、已卸载、权限不足分别返回；不扫描全设备应用 | 版本查询零截图；与系统应用信息比对 |
| perception.window_info | target=upper；maxWindows=16 | 当前可观察窗口 id/type/layer/focus/bounds；Activity 只在有可靠来源时返回，否则 null＋source | 多窗口/弹窗/节点失效；不承诺所有任务 | 下屏输入时仍按上屏枚举；标注不完整 |
| perception.ui_tree | windowId 可选；maxDepth=5、maxNodes=80、maxChars=12000 | 文本、角色、可操作属性、bounds、节点引用、截断/空树原因 | 密码字段隐藏；自绘游戏缺树；不缓存长期节点句柄 | 上限生效；Surface 空树不误报“无画面” |
| perception.capture_screen | target=upper；region 可选；maxEdge=1280、format=jpeg、quality=80 | 图像附件＋实际尺寸、mime、时间、屏幕/裁剪变换、frameId | secure flag、显示失效、系统频率限制、内存限制 | 只在工具调用时发生；只能截选定上屏 |
| perception.device_info | sections 从 system/displays/resources 选择，默认 system | Android/API/型号；请求时才含显示/电池/普通内存状态 | build 标识可脱敏；无序列号/账号清单 | 普通 SDK 路径，不偷用 Shell |
| debug.device_diagnostics | sections 从日志/任务/输入诊断选择，输出/时间有界 | 特权诊断摘要、来源和权限身份 | 仅调试 ON；各命令可用性不同 | 调试关闭完全不注入 |

app_info 的包查询范围首选实际需要的可见性声明与可见包。是否使用 QUERY_ALL_PACKAGES 需后续证明必要性；不上 Google Play 并不取消 Android 的平台限制或最小权限设计。

## 截图专项

[事实] Accessibility 提供指定 display 的截图 API；能力声明、系统版本和安全窗口会影响结果。MediaProjection 需要用户同意，其授权生命周期和显示内容选择不等同于无障碍截图。[截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))、[MediaProjection](https://developer.android.com/media/grow/media-projection)。

[决定] 首版优先 Accessibility；MediaProjection 是待单独验证的备用方案，不后台自动弹出授权、不绕过安全窗口。截图失败不自动改用 Shell；只有调试授权下另行调用相应诊断路径。

图片拟议压缩后上限 2 MiB，达到上限逐步降尺寸/质量并报告实际值；不能无限重试。底层截图缓冲完成转换即释放；传输采用附件引用与有界分块，模型请求构造时才读图，图像数据不进入 L0。模型不支持图像时不执行图片闭环，并在设置验证时提前拒绝该模型配置。

裁剪/缩放/旋转记录可逆坐标变换。返回给模型的像素坐标必须先映射回原显示逻辑坐标；输入工具校验 frameId、displayEpoch、目标应用与布局是否仍有效。不能用缩略图坐标直接点击物理屏。

## 结果保留与按需重读

截图、UI 树和窗口数据在当前 Run 中有短暂时效，不能当长期事实。压缩时优先替换为来源明确的摘要/附件引用；后续需要再次观察时重新调用，而不是默认循环截图。历史截图查看属于本地 UI，不自动触发上传。

对“画面是什么”可截图；对“版本多少”只查 app_info；对“为什么闪退”先查需要的设备/应用信息，再由调试工具读取限定日志。工具选择基于任务，不把这一顺序强制成每次固定流水线。

## 统一感知测试靶场

[Thor Input Lab](input-lab.md) 提供应用版本、可访问原生控件、自绘视觉区域、动态场景修订、颜色/文字/计数及受保护窗口场景。POC-02 使用其已知真值核对 app_info/window_info/ui_tree/capture_screen；视觉测试真值保留在测试 runner，不直接注入 Agent prompt。测试通过仍需核对真实游戏；自绘空树不是整个无障碍 API 失效的证明。
