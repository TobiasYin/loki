# 独立验收记录

验收日期：2026-10-13（Asia/Shanghai）。仅对 `app/` 本地工程和确定性模拟模式验收；没有 Thor、模型 Key、Pi 或真实设备工具集成。测试中仅使用固定的测试凭据，没有调用用户模型/搜索服务。

## 环境与产物

- macOS arm64，Microsoft OpenJDK 17.0.15；Node.js 23.7.0。
- Gradle 8.14.3、AGP 8.10.1、Kotlin 2.2.10、compile/target SDK 35、min SDK 28、Build Tools 35.0.0；全部依赖固定并提供局部锁文件。
- 独立 AVD `thor_app_validation`，Android 16 / API 36 / arm64-v8a，WebView 133.0.6943.137。AVD 数据存 `app/build/avds/`；验收使用独立 adb 服务端口 5038，设备 emulator-5582。
- Debug APK：`build/outputs/apk/debug/thor-agent-host-debug.apk`，包名 `dev.thor.agent.demo`。
- 当前已测试 APK SHA-256：`9c98c0ae00f18490c5bc8b3e1d6cc63b7980dcc23f18f86717dfbc77356f8a0f`。这是本机 Debug 构建校验值，不保证其他机器重建二进制相同。
- 测试 APK：`build/outputs/apk/androidTest/debug/thor-agent-host-debug-androidTest.apk`。

## 结果

| 检查 | 结果 | 证据范围 |
|---|---|---|
| `assembleDebug` | 通过，APK 实际安装/启动 | Kotlin、assets、Manifest 和 WebKit 依赖完整编译 |
| `testDebugUnitTest` | 16/16 通过 | 会话默认与隔离、持久化恢复、单活动 Run、切会话归属、撤销再开启、迟到事件、工具配对/顺序、等待批准、失败/未知、归档删除、长回答/200 条历史/50 会话、展开状态、预算与 monotonic timeout、来源/主 frame/页面/消息大小校验、写失败回滚、执行仅一次及附件失败不误报成功 |
| `npm test` | 7/7 通过 | 不可信文本不生成 HTML/脚本/远程图片；折叠错误/未知仍可见；跨会话活动入口；意愿/不可用原因分离；精确批准对象；长列表；存储恢复失败提示。此项为 jsdom DOM 验证，不证明像素布局 |
| Android instrumentation | 6/6 通过 | 真实 Android SQLite 关闭/重开恢复与级联删除、Keystore AES-GCM 密文/Native 读取、外键拒绝及事务回滚、附件 SHA/孤儿清理、实际 WebView 与 Native 桥完整工具/流式/落盘/终态折叠/手动展开、未知命令拒绝和远程页面无桥 |
| `lintDebug` | 0 errors / 13 warnings | 固定版本更新提示、target 35、必须使用 JS 的安全提醒、前置 WebView feature guard 的静态分析提示及中文文案本地化提醒。没有隐藏/禁用 lint errors |
| 依赖审计与仓库范围 | npm 安装时 0 vulnerabilities；修改仅 `app/` | 没有根 Gradle/npm workspace、runtime/或其他模块编译依赖 |

JUnit XML/HTML 在忽略的 `build/test-results/testDebugUnitTest/`、`build/reports/tests/testDebugUnitTest/`。Lint 详细报告在 `build/reports/lint-results-debug.html` 和 `.txt`。构建原始输出在 `build/evidence/build.txt`。可提交的设备端日志见 [android-instrumentation.txt](validation/android-instrumentation.txt)，DOM 日志见 [web-tests.txt](validation/web-tests.txt)。

[聊天与已结束工具轨迹截图](validation/chat.png) 从此应用自己的 Android WebView 绘制生成；用于检查文字、折行、主题与工具历史展示，不混入其他应用窗口，也不作为系统栏/键盘或 Thor 双屏证据。终态自动折叠由实际 WebView 测试单独断言，截图保留手动查看历史时的展示。

## 已修复的验收发现

- 长历史消息的 `runId=null` 不能用强制字符串查找当前 Assistant，否则新 Run 会异常；已改为可空关联查询并覆盖长历史后继续运行。
- Android 15+ edge-to-edge 系统栏不能只依靠 WebView 自身 padding；改为 Native 容器处理 systemBars/IME insets。
- Activity 监听者具有 owner 身份，旧 Activity 销毁不能移除新 Activity 的事件订阅。
- 实际 WebView 测试必须等待新会话列表更新后发送，避免在旧 Native 快照中选择错误会话；WebView 方法全部在 UI 线程调用。
- 工具结果文本只调用 DevicePort 一次；附件写成功后才提交结果，附件失败不会保存 completed 假结果。

## 不包含的验证

没有 Thor 下屏定位、双屏/旋转密度、IME 与真实键盘/焦点路由、上屏不中断、真机字体放大、无障碍、Shizuku、触摸/手柄、Shell、文件写入/配置备份、真实网络/搜索、两种模型协议探测、Pi Agent 循环、模型投影/压缩、StrongBox 或硬件安全级别证据。

恢复测试覆盖 Native 数据库重新打开与恢复入口、intent 中断的 unknown 结果与不重放；没有完成所有 Android kill/renderer/断电/磁盘故障注入组合。长列表有功能断言，没有 Thor 性能/耗电/帧率基线。原四图未取得，没有像素对照。未来对接差异和缺失功能见 [integration-notes.md](integration-notes.md)。
