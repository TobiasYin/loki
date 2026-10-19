# 项目开发规范

## Commit Message

生成 Commit Message 时，根据变更内容选择 `feat:`、`debug:`、`chore:`、`fix:` 等合适前缀。

## 默认开发与验收环境

后续任务默认使用本项目专用的 Thor 双屏模拟器。涉及 APK 安装、运行调试、初始化、设置页、聊天界面、生命周期、屏幕路由及 Android 仪器测试时，优先在这套环境完成验证。

- AVD：`thor_agent_dual_api36`。
- 默认 ADB 序列号：`emulator-5560`。
- 系统镜像：Android 16 / API 36 / ARM64。
- 上屏：display **0**，横屏 **1920 × 1080**，**369 dpi**。
- 下屏：display **4**，横屏 **1240 × 1080**，**369 dpi**。
- 聊天与主界面默认放在下屏 display **4**，上屏应用及观察目标放在 display **0**。

开始设备相关工作前，从项目根目录运行：

```sh
python3 scripts/thor-emulator.py
```

该脚本负责创建或复用专用 AVD，并核对实际 Android display ID、尺寸和密度。无窗口环境可加 `--headless`。默认端口被其他 AVD 占用时，可使用 `--port 5562` 等空闲偶数端口；后续命令必须使用启动结果中的真实序列号，屏幕角色仍为 0 / 4。

### 执行要求

1. 所有面向设备执行的 ADB 命令显式指定 `-s`，安装或执行测试前核对 AVD 身份和模拟器标识。不得使用未指定设备的 `adb`、`adb -e` 或“连接的第一台设备”作为默认目标。
2. 使用脚本输出及 `build/emulator/thor-project/displays.json` 核对双屏配置。直接从 AVD 管理器启动后，也须运行脚本完成核对。布局不符时先恢复专用模拟器，不得用错误屏幕编号继续验收。
3. 保持规范中的 display 0 / 4 及尺寸、密度。现有测试若固定为其他屏幕角色，先适配测试或显式参数；不得为迁就测试把这套模拟器改成单屏、任意 overlay 或另一组 ID。
4. 安装、重启和测试保留已有聊天、Provider 配置及 Keystore 凭据。测试数据使用合成内容；需要临时变更配置时记录原值并在结束后恢复。不得默认清空应用数据、擦除或重建已有 AVD。
5. 默认不向 Thor 真机安装或执行测试。用户明确要求真机验收、或任务必须验证物理硬件时，单独说明范围并按对应授权执行；模拟器结论与真机结论分开记录。
6. 纯文档或不涉及 Android 行为的修改按变更范围验证即可；涉及界面或设备行为时，不能仅以编译、JVM、DOM 测试替代模拟器验收。记录实际 AVD、序列号、屏幕角色、安装包和测试结果。
7. 这套环境对齐了 Thor 的屏幕 ID、显示尺寸和逻辑密度。它不能替代 Android 13 厂商固件、实体手柄、真机麦克风、HDR、刷新率等硬件专项验收。

常用安装与启动命令：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am start --display 4 -n dev.thor.agent/.MainActivity
```

完整环境说明见 [项目专用 Thor 双屏模拟器](docs/project-thor-emulator.md)。
