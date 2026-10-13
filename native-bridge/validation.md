# 本模块验收记录

日期：2026-10-13（用户时区 Asia/Shanghai）。这是 Native Bridge 的独立验收，不是仓库公共 PoC 或 Thor 真机验收。机器可读结果和当前 debug 产物 SHA-256 见 [validation-evidence.json](validation-evidence.json)。

## 已执行

| 项目 | 结果 | 证据 |
|---|---|---|
| Kotlin/JVM + MockWebServer | **32 tests / 0 failures / 0 errors / 0 skipped** | `core/build/test-results/test/TEST-dev.thor.bridge.{TransportTest,GatewayTest}.xml`、HTML 测试报告 |
| JS Fetch 适配器 | **12 tests / 12 pass**，Node 23.7.0 | `npm --prefix js test`；无 npm 外部依赖 |
| 自有 loopback HTTP fixture | **4 tests / pass** | `python3 -m unittest discover -s fixtures`；自行绑定 ephemeral port，无未知服务 |
| Android library | **debug AAR build pass** | `android/build/outputs/aar/android-debug.aar` |
| Android 独立宿主 | **debug APK build pass** | `host/build/outputs/apk/debug/host-debug.apk`；`aapt dump permissions` 验证只有 INTERNET |
| Android lint | **0 errors** | library 3 warnings、host 4 warnings，见下文 |
| 依赖锁定/校验 | **pass** | Gradle module locks、npm lock、`gradle/verification-metadata.xml`、Wrapper distribution SHA-256；最终构建启用依赖校验 |

执行命令（在本目录）：

```sh
./gradlew :core:test :android:assembleDebug :host:assembleDebug :android:lintDebug :host:lintDebug
npm --prefix js test
python3 -m unittest discover -s fixtures
```

构建环境：Gradle 8.14.3 在 Microsoft OpenJDK 21.0.7 运行，核心编译/测试使用已安装 Microsoft JDK 17.0.15；Android SDK 35，build-tools 35.0.0；AGP 8.10.1、Kotlin 2.2.10。源码与依赖锁都在模块内，无根 workspace 配置。

## 覆盖的行为

TransportTest（16 项）通过真实本地 socket 和 MockWebServer 检查：中文/emoji 分块保持原始字节、sequence 连续；400/429/500 body 和响应头；无 credit 不交付、有限 credit、幂等 abort 和唯一终态；消费者停滞总时限；请求/响应大小与重复 ID；通用 gzip 解码及 header 一致；凭据绑定和 302 不跟随；逐跳 path 复核、POST 不重放；搜索撤销不取消 model、旧 epoch 无法新 start；连接失效、伪造 Session 不能取消别人的请求；HEAD 无 body 无需 credit；中途断流；header/credential/method/path 约束；混合 DNS 私网答案与保留地址拒绝；自签名 HTTPS 证书拒绝，错误仅返回 code。

GatewayTest（16 项）检查：可信 origin/main-frame/当前文档/连接 metadata；默认能力及 Session 隔离；伪造 Session、lease、未知工具和 schema；参数和资源/快照/后端拒绝；关闭再开启旧调用不复活、重复 call 不重放；排队后撤销零新副作用；分段执行中撤销、已提交副作用如实报告；目标变化/后端丢失在提交前拒绝；过期 deadline/停止 Run/新 lease；UTF-8 输出预算和执行器超时；队列 32 槽上限；后端恢复后未发布工具仍拒绝；提交异常 outcome=unknown；租约过期/连接死亡旧 Run 拒绝；UI 待交付数据的独立每请求/全局预算；已知凭据值不允许进入工具参数且结果遮蔽。

JS 测试检查 base64 与二进制 codec、UTF-8 跨块、需求驱动 credit、HTTP 错误 Response、多值 header、string/bytes/ArrayBuffer/Request、序列化 body/header 合并、不支持的上传/选项和 body 上限、AbortSignal/迟到消息、bodyUsed 和 clone（并发/顺序消费、慢分支 256 KiB 限制）、坏 sequence/流错误、HEAD/204/205/304、frame 长度边界，以及 Request body 停滞的 timeout/桥关闭取消。

fixture 测试检查实际独立页面所用的 UTF-8 stream、400/500 body、dummy auth、302 固定目标、原始字节 echo 和 HEAD。Native/JS 集成页面已经随 APK 打包，但本次没有在 WebView 内执行它。

## lint 信息

library 的 3 个 warning 是新 Gradle/WebKit 版本提示及启用 JavaScript 的通用风险提示。本工程固定已经实际编译的版本；JS 只在固定本地文档、精确 origin、禁远程资源与 CSP 下使用，依然不能从静态 lint 认定没有 XSS。

host 的 4 个 warning 是 target API 35 非最新、Android 12+ backup rule 建议，以及两条测试宿主文案国际化提示。宿主禁 backup、无真实凭据或用户数据库，是 debug fixture；这些不作为生产安全/发布验收通过。Gradle 也报告未来 Gradle 9 的 deprecated feature 提示；当前 Wrapper 固定 8.14.3。

## 未执行与待验

`adb devices` 当时没有连接设备；没有启动模拟器，没有执行 instrumentation，也没有真实模型请求或真实 API Key。

- 实际 WebView：String/ArrayBuffer feature probe、模块脚本/标准 Fetch primitives、真实 base64 fallback、iframe/origin/navigation 隔离、renderer 退出、主线程待交付队列撤销与内存峰值。
- Thor：WebView provider/version、固件、双屏/焦点、系统权限、真实设备感知/输入/Shell/文件行为。此模块没有实现这些 executor，mock 结果不能作为平台送达证据。
- Pi/pi-ai：锁定依赖源码与 SDK 实际 Fetch 子集兼容、两类模型协议、图片/工具回传、模型语义重试、投影撤销。
- 正式 App：Native 持久化、Keystore、设置和网络 profile 修订、真实工具注册和看门狗、审计写入/重启 interrupted 处理、产品 UI 状态镜像。

可用 [README 的运行步骤](README.md) 在设备运行自有页面；后续执行应另记录设备/固件/WebView 版本和结果，不覆盖这份“本地已测、实机未测”的事实。
