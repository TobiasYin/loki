# Android Device ToolPack 与手动诊断宿主

`tools/` 是独立 Kotlin/Android 工程，版本 `0.1.0`，包名 `dev.thor.tools.diagnostic`。可在本目录安装依赖、构建、执行 JVM 测试并安装诊断 APK；不依赖仓库其他模块、Pi、模型服务或 Input Lab。

本版提供真实 Android API 执行器和明确标识的本地 **TEST 授权端口**。独立验收不等于产品 Native 网关、Runtime 或 Thor 固件的跨模块集成已经完成。已验证范围与待验范围见 [verification.md](verification.md)，对接差异见 [integration-notes.md](integration-notes.md)。

## 构建与依赖

固定版本：Gradle **8.14.3**、Android Gradle Plugin **8.10.1**、Kotlin **2.2.10**、Shizuku API/provider **13.1.5**、JUnit **4.13.2**。Android compile/target SDK **35**，Build Tools **35.0.0**，min SDK **30**，JDK **17**。AGP 的官方兼容要求是 Gradle 至少 8.11.1、JDK 17，参见 [官方说明](https://developer.android.com/build/releases/agp-8-10-0-release-notes)。选择 SDK 35 是本版固定测试配置，不是已验证支持所有新系统。

SDK 位置通过环境变量 `ANDROID_HOME` 或本目录不入 Git 的 `local.properties` 提供：

```properties
sdk.dir=/your/android/sdk
```

在 `tools/` 中运行：

```sh
./gradlew :core:test :diagnostic:assembleDebug :diagnostic:lintDebug :diagnostic:assembleRelease
python3 scripts/check-artifacts.py
# 或
./scripts/verify.sh
```

首次构建需要访问 Google Maven、Maven Central 和 Gradle distribution。依赖解析锁文件分别在 `core/gradle.lockfile`、`diagnostic/gradle.lockfile`；`gradle/verification-metadata.xml` 固定实际下载的构件 SHA-256，wrapper 固定 distribution SHA-256。更新依赖应明确检查版本及来源后重生成这些文件。校验和用于防止构件静默变化，不表示对依赖源码做过完整安全审计。

产物：

- 可安装的 Debug：`diagnostic/build/outputs/apk/debug/diagnostic-debug.apk`。
- 未签名 Release：`diagnostic/build/outputs/apk/release/diagnostic-release-unsigned.apk`；不提供发布签名密钥。
- JVM 报告：`core/build/reports/tests/test/index.html`。
- lint 报告：`diagnostic/build/reports/lint-results-debug.html`。

显式选定测试设备安装运行：

```sh
adb -s DEVICE_SERIAL install -r diagnostic/build/outputs/apk/debug/diagnostic-debug.apk
adb -s DEVICE_SERIAL shell am start -n dev.thor.tools.diagnostic/.DiagnosticsActivity
# Debug 专用：启动宿主、检查默认开关、Keystore、真实 Android 文件系统配置事务与不可用能力
./scripts/run-smoke.sh DEVICE_SERIAL
```

`run-smoke.sh` 要求 `adb`、`rg` 在 PATH。它只使用传入的 serial，不自动选择设备。Debug 冒烟入口不进入 Release APK。当前环境没有成功启动隔离模拟器，因此这 7 项设备检查**尚未执行**，不能引用为通过证据。

## 已实现工具与实际后端

| 工具 | 后端与范围 |
|---|---|
| `perception.app_info` | 上屏当前应用或明确可见包的 PackageManager 信息；版本查询不截图；无法区分隐藏和卸载时明确报“不可见或不存在” |
| `perception.window_info` | Accessibility 按选定 display 枚举可观察窗口，有界 16 个；Activity 返回 null，不用事件 className 推断 |
| `perception.ui_tree` | 指定上屏窗口；默认深度 5、80 节点、12000 字符；隐藏密码文本/描述；空树与错误分开 |
| `perception.device_info` | 普通 SDK 的 system/displays/resources，默认 system；不调用 Shell，无序列号或账号枚举 |
| `perception.capture_screen` | Accessibility `takeScreenshot(displayId)`；可裁剪，JPEG，最长边 1280，压缩后最多 2 MiB；安全窗口/限频/显示失效分别报错，不回退 Shell |
| `input.tap/long_press/swipe` | Accessibility `dispatchGesture`、指定 display，单指、1–32 点、最长 5s；`display_logical` 或绑定 `frameId` 的 `frame_pixels` |
| `input.release_inputs` | 只查询/释放本 Run 所有资源的安全清理语义；没有持久手柄资源。已提交手势没有公开即时取消 API，未收到回调则返回 unknown 并阻止继续触摸 |
| `input.press_button/set_axes` | **UNSUPPORTED**；没有验证过的原生手柄注入后端；没有键盘、触摸或 Shell 替代成功路径 |
| `debug.shell` | 真正 Shizuku UserService、实际 UID 2000；默认 15s、最多 60s，stdout/stderr 合计最多 64 KiB；每次审批确切命令；非零退出码为错误 |
| `files.list/read` | 默认明确批准的宿主 `synthetic-config/`；显式 `backend=shizuku` 可通过 UserService 读取固定 `/storage/emulated/0/Download/ThorToolPack`，先由用户建立该目录；不推断 Android/data 或应用私有目录可达 |
| `files.backup/write/restore` | 仅合成 JSON 配置目录；hash/CAS、具体差异审批、加密备份、同目录临时写/flush/原子替换、读回校验；恢复先备份当前版本 |

静态工具描述和输入 schema 在 [contracts/toolpack-v1.json](contracts/toolpack-v1.json)，打包进 APK，原生入口真正执行类型、枚举、字段及数量校验。每次结果含时间、source/实际后端、availability、truncated、outcome 和相关 snapshotId/display 信息。

## 手动诊断流程

1. 启动宿主，感知和搜索意愿默认 ON，屏幕操作和 ADB 调试默认 OFF。搜索显示未配置/本模块不提供。权限开关在进程内保存为测试状态，不代表产品 Session 持久化。
2. 在系统设置显式开启此 APK 的无障碍服务。屏幕角色不硬编码为 0/1：通过校准按钮查看 display ID、名字、尺寸和旋转，分别确认 upper/lower。单屏设备不能完成双屏校准。每次进程重启、角色或显示几何变化重新确认。
3. “Observe minimal upper context” 仅观察最小应用事实和能力摘要，不截图、不查版本、不遍历 UI 树；快照 TTL 10s。未知或多应用时不猜包名。
4. 选择工具，编辑 JSON，执行并查看输入、结构化结果、错误和后端。开启屏幕操作后，明确给出逻辑坐标或先截图、再在快照 10s 内使用 frameId 与缩略图像素。过期帧/角色/目标/布局会拒绝。
5. 用户自行安装并以 ADB 启动 Shizuku，点“Request/bind”。如果弹出授权，授权后再次点 Bind。检查实际 UserService UID；Root/其他 UID 被拒绝。通过 `debug.shell` 的默认 `id` 测试身份，命令仍需具体审批。
6. 配置闭环：`files.read sample.json` → 复制 hash → `files.backup` → 复制 backupId → `files.write`（保留未知字段）并审阅完整前后文本/旧新 hash → `files.restore` 并审阅恢复差异。schema 只接受 JSON object；写入内容原样保留，不自动重序列化编码/换行。
7. STOP、撤销能力、宿主离开前台、显示失效或 binder 死亡会停止 Run，拒绝后续动作，尝试结束受管 Shell 子进程。已发生副作用返回 submitted/unknown；不自动恢复配置或重放输入。

共享目录读示例（需要开启调试、绑定 shell UID UserService、路径实际可读）：

```json
{"path":"test.json","backend":"shizuku","maxBytes":65536,"offset":0}
```

任意 Shell 可以跨越屏幕感知、操作、联网及文件能力边界；关闭这些开关无法把 Shell 沙箱化。其写入不享受结构化配置的备份保护。宿主持续显示这一风险，Shell 审批也重复说明。所有文件工具即使由普通应用权限执行，仍要求 `adb_debug`。

## 资源和安全限制

- 一个活动 Run；设备资源串行；Run 最长 60s、最多 32 次调用；拒绝 requestId/toolCallId 重放。能力 epoch 单调增长，OFF→ON 不复活旧调用。
- v1 授权交集：有效 Run/Session、epoch、工具有效集合、schema、目标快照、系统后端、资源锁；在短暂真实提交处再作原子授权检查。
- 快照容量 32；截图只在调用时生成，最多 8 份私有 cache 附件，每份 2 MiB；原始截图分配限制 16MP。裁剪/缩放变换与 display 旋转一并返回；几何不符拒绝。
- 合成配置内容上限为 1 MiB 减 4 KiB 备份封套预留；手动完整差异审批上限 64 KiB，过大拒绝，防止审阅截断后仍批准。只写已存在普通文件，路径越界和符号链接拒绝；不支持非原子替换时拒绝。
- 备份 AES-GCM，AndroidKeyStore 256-bit 密钥；路径、原 hash、内容、时间、size/mtime/permissions/source 在认证加密封套中。每目标最多 10 份、总预算 100 MiB，满额拒绝新写；不静默删除恢复点。当前无备份清理 UI，卸载会丢失密钥与恢复点。
- 普通文本结果显示做有限敏感字段脱敏；这不是所有秘密的自动识别保证。二进制 read 只给元信息；截断的 Shizuku 文件结果给 prefixHash，不伪称完整文件 hash。
- Shell 有直接子进程的超时/取消句柄及 UserService 独立 60s 租约看门狗、host binder 死亡清理。没有经过验证的进程组终止路径，**descendantsMayRemain=true / terminationConfirmed=false**；永久 daemon 不属于支持范围，任意命令可能逃逸，出现 unknown 应手动停 Shizuku。

## 后续 Thor / Input Lab 验收

无需等待 Input Lab 开发即可使用此 APK；待测试接收端独立安装上屏后，按 [thor-validation.md](thor-validation.md) 收集 API 提交、真实接收事件和场景效果。没有这些证据，不能把工具回调成功写成 Thor 注入成功、手柄兼容或真实模拟器配置生效。
