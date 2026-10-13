# Thor Agent Android 宿主 · 独立模拟版

`app/` 是可独立安装依赖、构建、测试的 Kotlin Android 应用；聊天页面打包在本目录的 APK assets 中。**所有 Agent 回答、工具、设备结果均为确定性模拟**。没有 Pi、真实模型、搜索、Shell、触摸、手柄或其他模块的编译/运行依赖。原四张参考图不可用，页面依据文字设计，没有做像素对照。

## 构建与运行

需要 JDK 17、Android SDK Platform 35 / Build Tools 35.0.0，首次构建可访问 Google Maven、Maven Central 与 Gradle 官方分发。Gradle 8.14.3（校验 SHA-256）、AGP 8.10.1、Kotlin 2.2.10、WebKit 1.12.1 固定版本；传递依赖由 `gradle.lockfile` 固定。没有根级工程配置。可通过 `ANDROID_HOME` 或本目录忽略的 `local.properties` 指定 SDK。

```sh
cd app
export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # macOS；其他系统设置本机 JDK 17
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
adb -s <设备序列号> install -r build/outputs/apk/debug/thor-agent-host-debug.apk
adb -s <设备序列号> shell am start -n dev.thor.agent.demo/dev.thor.agent.MainActivity
```

UI DOM 测试额外需要 Node.js 22 或更新版本：

```sh
npm ci --ignore-scripts
npm test
```

设备端测试（仅向明确指定的测试设备安装两个 APK）：

```sh
adb -s <设备序列号> install -r build/outputs/apk/androidTest/debug/thor-agent-host-debug-androidTest.apk
adb -s <设备序列号> shell am instrument -w -r dev.thor.agent.demo.test/androidx.test.runner.AndroidJUnitRunner
```

普通 adb 端口为 5037。本次验收独立模拟器使用 `adb -P 5038 -s emulator-5582`，AVD 临时文件位于忽略的 `app/build/`，避免干扰已有模拟器。应用仅保证前台执行；离开前台会中断运行，Activity 旋转保留当前租约，进程恢复不重放。

## 演示操作

- 顶部 `☰` 打开会话抽屉，新建、切换、重命名、归档/恢复、删除；活动会话有运行标记。归档/删除活动会话需要先停止。
- 在输入区选择场景并发送任意非空问题。默认 `多工具成功`：查看应用版本 → 读取模拟界面 → 流式回答。结束自动折叠本次轨迹，历史手动展开保持。
- `工具失败` 会显示错误；`长回答` 可验证流式与滚动；`30 项事件` 产生 15 对工具开始/结果事件。
- `等待批准`、`未知副作用` 需要先在本会话勾选 `屏幕操作` 并确认边界说明。等待卡显示明确的模拟配置差异，可批准或取消；未知结果停在失败终态，警示不会被折叠隐藏。
- 运行中打开抽屉新建/切换会话：上方始终显示原运行所属会话与停止入口。一个设备仅一个活动 Run；发送按钮在活动运行存在时提供停止。
- 运行中关闭 `上屏感知`：Native 增加 capabilityEpoch、取消运行，未确认工具保持 unknown。重新开启不会复活旧租约。
- 抽屉 `加载长历史示例` 经确认后，为当前会话添加 200 条消息及 49 个新会话，用于长列表与折行验证。
- `⚙` 是 Native 设置：模型显示名/协议/HTTPS URL/模型 ID/Key、搜索后端/HTTPS URL/Key。保存标记未测试，不启用真实连接。空 Key 保留已有秘密，删除有明确勾选；不回填秘密到 WebView。

## 结构与数据

| 文件 | 职责 |
|---|---|
| `Domain.kt` | Session / Message / Run / RunEvent 的领域状态；RuntimePort、NativeStorePort、DevicePort、AttachmentPort；模拟执行计划与 Native 授权 |
| `Host.kt` | 单线程串行调度、180ms 模拟增量、Activity 生命周期和恢复入口 |
| `Storage.kt` | SQLite v1 外键与事务、附件 hash/引用、Keystore AES-GCM 凭据 |
| `MainActivity.kt` / `BridgePolicy.kt` | 本地 HTTPS WebView、精确 origin/main-frame/页面/大小校验、Native 连接设置 |
| `src/main/assets/chat/` | 不依赖 npm 的 HTML/CSS/JS 聊天 UI，全部用户内容通过 textContent 呈现 |
| `src/test/`, `src/androidTest/`, `web-tests/` | 领域测试、实际 Android SQLite/Keystore/WebView 测试、DOM 投影测试 |

SQLite `sessions/runs/messages/events/attachments` 分表；Run 与触发消息关联，事件具备连续 sequence、toolCallId、wallTime、monotonic elapsed、outcome；数据库是唯一事实源。工具 intent/结果即时事务提交，模拟文本增量每 180ms 提交；启动把所有非终态 Run 标为 interrupted。SQLite 写入失败会回滚内存视图，调度停止，不能报告存储成功。模型完整 payload 不存库。

附件为每次模拟成功工具生成的有界文本文件（64 KiB 上限），保存 MIME、SHA-256、尺寸、来源、Run、过期时间；路径不发给 WebView。过期/孤儿文件在启动或删除时清理，引用仍能说明过期。凭据独立存私有 SharedPreferences 的 AES-GCM 密文，密钥在 Android Keystore；密文绑定用途，API Key 不进入聊天桥。应用不申请 INTERNET 或设备工具权限、不允许系统备份、不启用生产 WebView 调试。强盒/硬件安全级别没有实机证据。

已完成的验证、截图与未验证项见 [validation.md](validation.md)，未来适配差异见 [integration-notes.md](integration-notes.md)。独立验收不代表跨模块已集成。
