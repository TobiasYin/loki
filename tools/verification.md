# 本模块验证记录

日期：2026-10-13（Asia/Shanghai）。工作目录限定本 worktree 的 `tools/`，未修改根 README、公共 docs、其他模块或主检出。没有推送、发布、合并、真实模型服务调用或密钥上传。

## 已执行通过

环境：macOS arm64；Gradle 8.14.3；JDK 17.0.15 toolchain（宿主可用 JDK 21）；AGP 8.10.1；Kotlin 2.2.10；SDK/target 35；Build Tools 35.0.0。Shizuku API/provider 13.1.5。

```sh
./gradlew --offline --console=plain :core:test :diagnostic:assembleDebug :diagnostic:lintDebug :diagnostic:assembleRelease
python3 scripts/check-artifacts.py
```

`--offline` 是依赖已经安装后的验证方式；新环境首次构建不应使用它。

- **31 个 JVM 测试，0 failures/0 errors**。测试后端明示 fake，只证明 Core 授权/快照/预算和文件/Shell 逻辑；本地 Shell 测试使用 macOS `/bin/sh`，不代表 Android shell 行为或 Shizuku 权限。
- Debug 和 unsigned Release APK 构建成功，包括真实 Accessibility 指定 display 截图/手势 API、AIDL UserService、Keystore 代码和固定 schema 校验器编译。
- `lintDebug` **0 errors、4 warnings**：固定 target SDK 35 未追踪最新系统；三处手动诊断英文 UI 的 SetTextI18n。没有关闭 lint error 检查。Gradle 提示插件的 Gradle 9 兼容弃用，本项目 wrapper 固定 8.14.3。
- `check-artifacts.py` 验证 APK 内 schema 与源契约相同、17 个工具 ID 与注册表一致且无重复、Debug 包含设备冒烟类而 Release 不包含、JVM 与 lint 报告无错误。
- `apksigner verify --verbose` 验证 Debug APK 的 v2 签名成功（1 signer）。Release 产物未签名。
- `apkanalyzer manifest print` 核对 Release 没有 instrumentation，没有通用 tool 导出 IPC；无障碍服务受 BIND_ACCESSIBILITY_SERVICE 保护；Shizuku provider 受 INTERACT_ACROSS_USERS_FULL 保护；allowBackup=false 且关闭数据备份/迁移；未声明 INTERNET 或 QUERY_ALL_PACKAGES。

## JVM 覆盖证据

`core/src/test/kotlin/dev/thor/tools/CoreTest.kt` 覆盖：

1. 默认最小权限、effective tools、跨 Session/Run、未知工具/schema、deadline、旧 epoch OFF→ON 不复活、调用重放、32 调用 Run 预算、单 Run 和设备锁。
2. 撤销丢弃迟到读结果、已提交取消为 unknown、取消触发资源清理、最后原子提交检查拒绝旧授权、动作自身布局变化不会伪称“未提交”。
3. 两个不同 display 人工校准、旋转/几何/移除/包/布局/TTL 失效、32 快照容量、过期输入零调用后端。
4. 路径/时长/NaN/越界和点数预算、裁剪缩放坐标独立真值、字节预算精确 EOF vs truncated、有限秘密脱敏。
5. 路径越界、符号链接与父链接、不存在目标；加密备份持久化/重开/篡改认证失败；hash/具体审批/备份匹配；未知字段及 CRLF 保留；原子写/读回/恢复前备份；并发覆写/链接替换/取消零提交；写后读回失败保留恢复点；quota 满额拒绝且不删除；文件格式/大小拒绝。备份元信息包括原 size/mtime/permissions/source。
6. Shell 合计输出硬上限、stdout/stderr、非零退出、超时、取消和已脱离 child 不拖住输出 drain；明确不声称所有 descendants 已终止。

测试临时目录在本工程 `core/build/tmp-tests/`，不产生仓库外测试项目。

## 未执行与环境限制

已创建本任务本地隔离 AVD 配置（不读取或改变已有任务模拟器状态），尝试启动 API 36 Google Play arm64 镜像。启动失败：主机可用约 **3725.5 MiB**，镜像最小 userdata 需要约 **7372.8 MiB**；缩小配置分区仍受镜像最低要求限制。因此 **APK 启动、7 项 Android 冒烟、Keystore 实际加解密与 Android 文件系统运行尚未执行**。Debug runner 及 `scripts/run-smoke.sh <explicit-serial>` 已提供并编译；不能把这些检查计入 30 个已通过 JVM 测试。

无 Thor 实机/真实 Shizuku 会话/接收端证据，以下全部待验：

- Thor display 对应关系、下屏宿主放置、上屏应用识别、截图 buffer 几何/安全窗口/限频、真实触摸送达、焦点/输入法/旋转/OEM 差异与性能。
- Shizuku UserService 实际启动/UID、授权撤销与 binder/host 死亡、Android 子进程/SELinux/文件可达性。没有应用 UID 的 Shell fallback。
- 原生手柄按钮/轴/设备/source/指定物理屏注入：工具保持 **UNSUPPORTED**；未宣称满足真实手柄需求。
- SAF、Android/data 或真实模拟器私有配置及跨 UID 安全事务写入：不开放。合成配置逻辑通过不表示模拟器配置读写生效。
- Native bridge/正式 Capability Store/Session 持久化、Pi/Runtime 工具错误和附件协议、Input Lab 接收与场景报告：没有跨模块集成。

下一步的具体实机用例见 [thor-validation.md](thor-validation.md)，接口和安全限制见 [integration-notes.md](integration-notes.md)。

## 当前构建产物摘要

本地 APK 的大小/哈希见下方构件记录；它们只标识这次构建。Debug 签名由本机开发证书产生，其他机器重构不保证字节级相同。构建目录不入 Git，源码、配置、锁文件、校验 metadata 和验证脚本入 Git。

| 产物 | 字节数 | SHA-256 |
|---|---:|---|
| `diagnostic/build/outputs/apk/debug/diagnostic-debug.apk` | 2896831 | `20aff1f2e0389c5def1e7c919bdf744cd6a0a55a6f7bb817bc3ca4bbd80c0dd2` |
| `diagnostic/build/outputs/apk/release/diagnostic-release-unsigned.apk` | 2352369 | `ccc21699212d0e8ddcbbbff24ad8e921032ef1b71e42a23f06061eb6daef9604` |
