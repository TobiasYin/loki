# 官方参考、证据基线与先前方案修正

## 核对说明

访问日期统一为 **2026-10-13（Asia/Shanghai）**。来源优先官方文档与维护者源码；不用搜索结果镜像/博客证明 API。网页内容会变化，源码可用时提供固定提交链接。

Pi 旧地址 `github.com/badlogic/pi-mono` 的 GitHub API/原始文件读取在本次重定向到 `earendil-works/pi`；读取提交为 `7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6`，提交元数据时间为 `2026-10-12T23:37:22Z`。这只是核对基线，不是宣布 npm 包已验证或项目依赖已安装。

## Pi 源码证据

| ID | 固定来源 | 本次确认的有限结论 |
|---|---|---|
| P1 | [Agent README](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/agent/README.md) | 请求准备、消息转换、工具事件与工具集合更新机制存在；当前 transcript 的系统/工具声明需注意 |
| P2 | [Agent 接口源码](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/agent/src/agent.ts) | AgentOptions 暴露 prepareRequest/transformContext/convertToLlm/streamFn 等接入点 |
| P3 | [pi-ai README](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/ai/README.md) | 有 Browser Usage 和按入口缩小打包范围的说明；不能等同 Thor 已通过 |
| P4 | [pi-ai 类型](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/ai/src/types.ts) | ProviderRequestOptions.fetch 存在；类型注释说明其不接管 WebSocket |
| P5 | [Responses API 实现](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/ai/src/api/openai-responses.ts) | options.fetch 进入 SDK 客户端；协议/SDK 请求在 JS 层 |
| P6 | [Messages API 实现](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/ai/src/api/anthropic-messages.ts) | 目标 HTTP 路径支持传入 fetch；token 分类/附加行为仍需占位方案验证 |
| P7 | [Agent 包元数据](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/agent/package.json) | 新 scope @earendil-works/pi-agent-core、version 1.2.0、Node engine 声明 |
| P8 | [AI 包元数据](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/ai/package.json) | 新 scope @earendil-works/pi-ai、version 1.2.0；依赖列表不等于浏览器全部会执行 |
| P9 | [Agent loop 源码](https://github.com/earendil-works/pi/blob/7d75a0e3eb273dff753d49c066ea29fbe7b3b6f6/packages/agent/src/agent-loop.ts) | 转换/上下文流水线确实存在；最终行为按固定版本契约验证 |

原始源码通过官方 raw.githubusercontent.com 读取，未保存第三方代码到项目；未下载仓库、未安装 npm 包、未执行源码。

## Android、Shizuku、网络与扩展

| ID | 官方来源 | 支持的事实/需注意的边界 |
|---|---|---|
| A1 | [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) | 按 display 的可交互窗口/截图及手势 API；不是所有任务/所有像素可访问保证 |
| A2 | [GestureDescription.Builder](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder) | setDisplayId API 30；只能证明能表达目标显示 |
| A3 | [ActivityOptions](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int)) | 目标显示启动选项，仍有显示/系统权限限制 |
| A4 | [Display support / Per-display focus](https://source.android.com/docs/core/display/multi_display/displays) | 系统焦点配置与显示输入路由，不能据双屏推定独立焦点 |
| A5 | [Input routing](https://source.android.com/docs/core/display/multi_display/input-routing) | 物理输入和显示关联属于平台行为 |
| A6 | [游戏控制器输入](https://developer.android.com/games/sdk/game-controller/controller-input) | 接收端按钮/轴不同；不是跨应用注入许可 |
| A7 | [包可见性](https://developer.android.com/training/package-visibility) | 应用信息查询可能被过滤 |
| A8 | [文件存储概览](https://developer.android.com/training/data-storage) | 私有与共享数据边界；SAF 授权不等于任意路径权限 |
| A9 | [MediaProjection](https://developer.android.com/media/grow/media-projection) | 用户同意和生命周期约束；上屏选择须实测 |
| A10 | [WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat) | 受 origin 规则约束的消息监听，需要 feature 检查 |
| A11 | [WebMessageCompat](https://developer.android.com/reference/androidx/webkit/WebMessageCompat) | 消息数据接口；实际二进制桥可用性仍依 WebView 特性 |
| A12 | [Native bridge 风险](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges) | 远程/不可信页面与原生桥组合的安全风险 |
| A13 | [Android Keystore](https://developer.android.com/privacy-and-security/keystore) | 保护加密密钥；硬件支持需检查，不是任意秘密字符串存储接口 |
| S1 | [Shizuku API/开发指南](https://github.com/RikkaApps/Shizuku-API) | UserService 身份取决于 backend；旧 newProcess 不作推荐方案 |
| S2 | [Shizuku 设置指南](https://shizuku.rikka.app/guide/setup/) | 用户需启动/授权服务，设备步骤与可用性仍需验证 |
| I1 | [AOSP InputManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/input/InputManager.java) | 注入入口涉及隐藏 API 与 INJECT_EVENTS；不能当普通 SDK 方法 |
| I2 | [AOSP InputShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/input/InputShellCommand.java) | 有 gamepad/joystick source 标签；不证明真实设备轴/身份/路由 |
| I3 | [AOSP VirtualDeviceManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/companion/virtual/VirtualDeviceManager.java) | 虚拟设备有特权/角色约束，不是假定可用的替代输入通道 |
| N1 | [OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision) | 图片输入表达与模型支持要求 |
| N2 | [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling) | 工具交互协议，不替代兼容网关实测 |
| N3 | [Claude Vision](https://platform.claude.com/docs/en/build-with-claude/vision) | 图片输入能力/格式 |
| N4 | [Claude Tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview) | 工具定义、调用与结果机制 |
| N5 | [Claude Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming) | 流式事件由 JS/Pi 处理 |
| W1 | [Brave Web Search](https://api-dashboard.search.brave.com/app/documentation/web-search) | 搜索 API 候选，不承诺区域可达、价格或额度 |
| W2 | [Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search) | 搜索备选接口 |
| W3 | [SearXNG Search API](https://docs.searxng.org/dev/search_api.html) | 自建实例 API 和格式配置限制 |
| M1 | [MCP Tools，2025-06-18 版本](https://modelcontextprotocol.io/specification/2025-06-18/server/tools) | 工具发现/调用适配边界；引用特定版本，不宣称这是最新版 |

AOSP 链接是读取时 main，而非 Thor ROM 对应源码；今后 PoC 要记录真实固件并寻找相应分支/行为证据。本次不依据 AYN 营销页写死屏幕像素、系统版本、displayId 或手柄后端。

## 对先前口述方案的修正

| 先前容易误解的说法 | 本次修正 |
|---|---|
| 直接沿用 @mariozechner/pi-* 和旧示例 | 官方当前地址/scope/接口已变化，基于固定提交核对，正式版本后续冻结 |
| 浏览器支持意味着 Thor WebView 可直接运行 | 只确认候选路径；打包、SDK、Fetch、CSP、生命周期和内存都需 PoC |
| prepareRequest 会自动解决环境刷新和压缩 | 钩子只是接入点，需由 Thor 生成唯一环境块并保留协议完整性 |
| 改 agent.state.tools 就完成权限撤销 | 工具注入外还必须有 Native epoch/lease 和即时执行检查；历史声明不能复活 |
| 当前前台应用就是上屏应用 | 按 display/window 识别；下屏聊天可能拥有全局焦点 |
| 可以读取所有运行应用和精确 Activity | 普通可观察窗口有限；Activity 不可靠时为 null；详细任务诊断归调试 |
| 手柄 keyevent 成功就等于真实手柄支持 | 必须验证 source/device/轴/组合输入、模拟器过滤与上屏路由 |
| Shizuku 可以改任意模拟器配置 | shell ≠ Root；路径、权限、格式、覆写与恢复逐目标证明 |
| 开启 Shell 仍能靠其他开关强隔离 | Shell 是高权限通道，能跨越其余产品能力边界，UI 必须明确告知 |
| 搜索 ON 就开箱可搜 | 还需要搜索后端、凭据、可达性和额度；缺失时不可用 |
| 截图只要本地路径就会被模型理解 | 图像须经 Pi 正确编码；工具图片回传是独立验收项 |
| 运行结束折叠后，模型上下文也变小 | UI 与模型投影解耦，压缩策略必须另外执行 |
| Native 可以理解 SSE 后转文本 | Native 只传通用 HTTP 字节，所有模型事件由 Pi/JS 解释 |
| Key 在 Native 即可完全无泄漏 | 通用凭据引用仍需验证 SDK 行为、日志和跨 origin 约束 |
| 四张图已经被本次完整参考 | 图片未随读取返回；当前只依据文字布局，视觉细节待核对 |

## 没有完成的验证

没有任何 Thor 实测、实际模型/搜索调用、npm 发布包验证、依赖安装、APK 构建或产品实现。所有性能预算、接口命名和目录分工均为设计，不是上游 API 合同或实测结果。

## 上屏测试 APK 的补充官方依据

访问日期：2026-10-13。以下只证明公开接收接口/时基与访问控制设计有依据，不证明 Thor 上注入/焦点已通过。

| ID | 官方来源 | 用途与边界 |
|---|---|---|
| L1 | [Activity](https://developer.android.com/reference/android/app/Activity) | touch/key/generic motion 分发与焦点回调；只观察本应用送达事件 |
| L2 | [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent?authuser=00) | 多指、历史样本、轴和 uptime 事件时间；按 API 版本选择字段 |
| L3 | [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent) | action、键值、重复、设备/来源与时序元数据 |
| L4 | [InputDevice](https://developer.android.com/reference/android/view/InputDevice) | 来源集合、设备描述和轴范围；不能据此认证输入发送方 |
| L5 | [SystemClock](https://developer.android.com/reference/android/os/SystemClock?authuser=383) | uptime 与 elapsedRealtime 深睡语义不同，测量必须统一时基 |
| L6 | [跨应用权限控制](https://developer.android.com/training/permissions/restrict-interactions) | 测试组件的签名权限/调用边界；正式证书/UID 配置仍需验证 |

接收字段、LAB 用例、报告状态和测试控制方法是 Thor 的拟议契约，不是 Android 现成测试协议。完整设计见 [Input Lab](input-lab.md)。
