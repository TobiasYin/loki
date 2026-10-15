> 2026-10-13 整合状态：已进入根工程正式软件集成；以下独立任务证据保留为历史参考。当前产品接线、复现入口与本次验证见 [中文集成报告](../docs/integration-report.md)，不能将旧模块通过直接当作集成通过。

# Thor Native Bridge 0.1

这是 `native-bridge/` 内独立的 Android/Kotlin 工程。已实现通用 HTTP 字节桥、受信 WebView 连接和 Native 最终工具网关，附 JS Fetch 子集、MockWebServer 测试及独立 APK 宿主。它不依赖仓库的 `app/`、`runtime/`、`tools/`，不接入 Pi、不解析模型 JSON/SSE，不提供真实设备工具。

本地 JVM/JS 测试和 Android 构建已执行；实际 WebView、Thor 以及 Pi SDK 对接尚未验收。详细证据见 [validation.md](validation.md)，契约及后续适配见 [integration-notes.md](integration-notes.md)。

## 安装、测试与构建

需要 JDK 17（核心 toolchain 固定为 17；本次 Gradle 运行于 JDK 21）、Node >=22、Android SDK platform 35 / build-tools 35.0.0。工程内 Gradle Wrapper 固定 8.14.3，下载 checksum 已登记。依赖来自 Google Maven / Maven Central / Gradle Plugin Portal，版本、Gradle 锁文件及 artifact SHA-256 均在本目录内；JS 无外部 npm 依赖。

```sh
cd native-bridge
# 设置 ANDROID_HOME，或在本目录的 local.properties 写 sdk.dir=/你的/Android/sdk
./gradlew :core:test :android:assembleDebug :host:assembleDebug :android:lintDebug :host:lintDebug
npm --prefix js ci
npm --prefix js test
python3 -m unittest discover -s fixtures
```

核心依赖为 Kotlin 2.2.10、AGP 8.10.1、OkHttp/MockWebServer/测试 TLS helper 4.12.0、AndroidX WebKit 1.14.0、JUnit 4.13.2；都使用实际下载及编译过的版本，不追随动态版本。

产物和报告：

- `host/build/outputs/apk/debug/host-debug.apk`：独立宿主，包名 `dev.thor.bridge.host`。
- `android/build/outputs/aar/android-debug.aar`：WebView 桥 library；消费方同时需要 `core` 与声明的依赖。
- `core/build/libs/core.jar`：JVM 核心。
- `core/build/reports/tests/test/index.html`：Kotlin 测试报告。
- `android/build/reports/lint-results-debug.html`、`host/build/reports/lint-results-debug.html`：lint 报告。

`build/`、本机 SDK 路径和缓存均局部忽略；生成物不提交到 Git。

## 在 Android 上运行独立宿主

在工作站启动 loopback fixture；不访问真实模型、不需要 API Key。APK 中唯一凭据是公开测试字符串 `fixture-secret`。

```sh
python3 fixtures/server.py
```

另一个终端（`adb` 必须指向待测设备；多设备时加 `-s SERIAL`）：

```sh
adb reverse tcp:8877 tcp:8877
adb install -r host/build/outputs/apk/debug/host-debug.apk
adb shell am start -n dev.thor.bridge.host/.MainActivity
```

页面通过 `WebViewAssetLoader` 的固定本地 HTTPS origin 加载。`bridge.hello` 绑定 Native 签发的连接和 Run；若支持 ArrayBuffer，会发送 `[0,255,42]` 的真实消息探针，收到确认才切换到二进制 frame，否则使用有上限的 base64。没有 `WEB_MESSAGE_LISTENER` 或必要 JS 标准能力时关闭桥，不降级到 `addJavascriptInterface`。

点“运行本地 HTTP 检查”，页面检查中文/emoji 跨块、400/500 body、AbortSignal、携带 fixture 凭据的 302 不被自动跟随，最后调用 `mock.echo`。可检查服务端的 `/auth` 验证通用 header 替换。页面日志只有固定的结果摘要。Run 有 175 秒 deadline；返回前台会重建宿主与新租约，网络和工具检查需用户再次点击，旧调用不会重放。离开前台、renderer 退出或非法主文档导航都会终止当前连接。

宿主仅 debug manifest 允许显式登记的 `127.0.0.1:8877` HTTP fixture；release 默认禁用 cleartext，该 fixture 页面只用于 debug 验收。Native 配置之外的 URL/method/path/profile 都被拒绝。生产应用使用自己配置的 HTTP/HTTPS profile 和 Native 私有凭据存储，不复用测试配置。2026-10-15 按用户要求，正式 Agent 已允许系统 DNS/代理、内网和 fake-IP；此处独立诊断宿主的 debug fixture 配置不代表正式 Agent 的网络限制。

## 目录与边界

| 目录 | 实现 |
|---|---|
| `core/` | Authority、不可变网络 profile、CredentialVault、HTTP transport、有限队列工具网关；纯 JVM 验收 |
| `android/` | 精确 origin / main-frame / WebView 身份与当前文档检查；消息 codec；禁远程资源的 WebView 宿主接口 |
| `js/` | Fetch 适配器、二进制 frame codec、有缓冲边界的 clone 与 Node 契约测试；打包成 library assets |
| `host/` | 最小独立 Android 宿主、本地 JS 页面、无副作用 mock 工具 |
| `fixtures/` | 只监听 loopback 的 HTTP 服务 |

HTTP 默认单块 32 KiB、credit 最大 256 KiB、请求 body 16 MiB、响应 32 MiB；连接 15 秒、读写空闲 60 秒、总时限 180 秒，且不超过 Native Run 租约与信封 deadline。最多八个 HTTP 请求，分别验证所属 Run；当前桥连接最多 512 个请求 ID，终态后也不能重用。工具单执行线程、32 个排队槽、单 Run 最多 512 个 call/request ID、结果最大 64 KiB。错误显式返回，不静默丢块。

网关先检查 Native 已发布的工具集合、Session/Run/lease/epoch、严格参数、资源 snapshot 与后端状态，入队后在每个 `ExecutionContext.step` 内再次检查，短副作用提交与撤销共用授权锁。停止后尚未提交的段不得执行；已经提交的动作报告 `submitted`，提交发生异常报告 `unknown`。这不是设备系统调用回滚保证。

## 核对的官方资料

- [WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat)：精确 allowed origin、main-frame 回调及 feature gate。
- [WebMessageCompat](https://developer.android.com/reference/androidx/webkit/WebMessageCompat)：String / ArrayBuffer 消息类型。
- [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)：本地 HTTPS 资源。
- [Android Native bridge 风险](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)：桥不应向不受信文档开放。
- [锁定 OkHttp 4.12.0 的客户端源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt) 与 [通用 gzip interceptor](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/BridgeInterceptor.kt)：默认 TLS 验证及压缩 header/body 一致性。实现显式关闭自动 redirect、连接重试、系统 proxy、cookie 与 cache，响应不按模型语义重试。
