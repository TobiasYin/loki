# 测试与验收入口

本仓库包含 Runtime、Native Bridge、设备授权、界面、独立 Input Lab 接收端及 Android instrumentation 测试。测试计划见 [专项文档](../docs/testing.md)，最新 0.6 实机结果见 [验收记录](../docs/release-0.6-acceptance.md) 和 [归档证据](release-0.6-evidence/README.md)。历史目录保留各自的版本与证据范围，不代表最新安装包的结果。

- JVM 测试位于各模块 `src/test`；JS 测试位于 `runtime/test`、`app/web-tests` 和 Native Bridge 的测试目录。
- 产品 Android 测试位于 `app/src/androidTest`。模拟器 fixture 测试会写入合成配置，不得直接用于用户真机；`Physical*` 真实设备测试明确要求 `physicalAcceptance=true`，保留用户凭据和历史。
- [Input Lab](input-lab-app/README.md) 为独立 UID、独立进程的上屏接收应用。接收端原始事件与发送端工具结果分别记录；debug runner 不进入 release APK。产品正式运行不依赖安装 Input Lab。
- `scripts/physical-resume.py`、`scripts/physical-voice.py` 用于已授权、已连接的 Thor 专项回归。实机测试串行执行，避免不同场景干扰输入与焦点。每次使用新的输出目录保留失败记录。

接收器完整故障矩阵、物理基线资格和游戏兼容性仍按各自专项记录判定，不因某次自动化 `pass` 自动升级。
