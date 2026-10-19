# 项目专用 Thor 双屏模拟器

本环境是后续任务默认的开发、安装调试与模拟器验收环境；执行要求见项目根目录 [AGENTS.md](../AGENTS.md)。涉及界面或 Android 设备行为时，默认使用此 AVD，聊天主界面在 display 4，上屏目标在 display 0。

2026-10-19。AVD：`thor_agent_dual_api36`，默认序列号：`emulator-5560`。该 AVD 与原有 `android_gui_agent_api36` 独立，应用数据、凭据和虚拟磁盘分别保存。

```sh
python3 scripts/thor-emulator.py
```

脚本创建或复用这个专用 AVD，冷启动并核对 Android 实际分配的显示器 ID、像素尺寸和密度。支持 `--headless`、`--port 5562` 和只创建配置的 `--configure-only`。不会向物理设备执行命令，不修改其他 AVD；端口被其他 AVD 占用时拒绝操作。

| 角色 | Android display ID | 横屏分辨率 | 比例 | 密度 |
| --- | --- | --- | --- | --- |
| 上屏 | 0 | 1920 × 1080 | 16:9 | 369 dpi |
| 下屏 | 4 | 1240 × 1080 | 31:27 | 369 dpi |

对应 Thor 的原生面板是 1080×1920 与 1080×1240，实际使用时均旋转成上表横屏。模拟器直接以横屏尺寸创建，两屏锁定横屏。图形像素和 Android 逻辑 display ID 相同；物理显示端口、HDR、刷新率和厂商固件行为不属于本配置的模拟范围。当前使用本机已安装的 Android 16 / API 36 / ARM64 镜像，Thor 运行 Android 13，系统版本尚未完全一致。

Android 不提供直接指定逻辑 display ID 的普通 AVD 设置。脚本在冷启动后通过模拟器官方多显示器控制台接口热插拔临时显示器，释放编号小于 4 的临时屏幕，最终仅保留 0 与 4。发现异常编号或额外显示器时拒绝继续，避免测试误用错误屏幕。请通过脚本启动，直接从 AVD 管理器启动不能保证下屏为 4。再次运行脚本会验证当前已启动的布局，不重复添加显示器。

首次启动采用全新、稀疏的 QCOW2 data 镜像，使用 SDK 自带 mkfs.ext4 与 qemu-img 创建，不复制原有 AVD 数据。不保存内存快照。模拟器使用 2 核 CPU / 2 GiB RAM；data 逻辑容量 6 GiB，实际占用随安装和应用使用增长。

核对结果写到 `build/emulator/thor-project/displays.json`，运行日志为同目录 `emulator.log`。

```sh
# 构建与安装
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 主界面放到 Thor 对应的下屏
adb -s emulator-5560 shell am start --display 4 -n dev.thor.agent/.MainActivity
```

多显示器尺寸与密度配置参考 [Android 模拟器多显示器说明](https://developer.android.com/studio/run/emulator-extended-controls)。屏幕路由测试应分别以 0 为上屏、4 为下屏，不把单屏 UI 测试视为双屏触控、手柄或真机麦克风验收。
