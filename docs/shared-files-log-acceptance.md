# 共享文件与应用日志能力验收（2026-10-18）

[SMAPI 真机调查](smapi-log-capability-investigation-2026-10-18.md)确认，实际日志可被 shell UID 2000 读取，但 Agent 文件后端限制在不存在的测试目录。工具契约 0.2.0 已改为读取实际共享存储；旧会话和 Pi 上下文没有迁移或改写。

## 工具行为

| 工具 | 行为 |
|---|---|
| files.list | 共享存储目录元数据，按名称排序与 offset/limit 分页；`/storage` 为可访问主存储/外置卷的虚拟根 |
| files.stat | 文件或目录的类型、大小、修改时间 |
| files.find | 忽略大小写的文件名子串查找，限制深度、扫描量与返回数量 |
| files.read | UTF-8 字节分页与 tail，最多 64 KiB；实际范围、nextOffset、片段 hash、截断与变化标记 |
| debug.app_logs | 精确包名及其子进程当前 PID 的有界 logcat；未运行、无日志正常返回 |

list/read 默认 backend=auto：绝对共享路径自动走设备，原有相对路径继续读取 synthetic 配置。显式 shizuku 的相对路径以主共享根为起点；stat/find 固定为共享存储只读工具。支持 `/sdcard`、`/storage/self/primary`、`/mnt/sdcard` 以及已配置的外置卷，根由调用应用的真实 Android 用户确定，不接受模型指定任意根。Shizuku 服务版本升级为 22，避免复用旧实现。

目录与查找结果明确报告扫描、深度、不可读、变化和截断，不承诺运行中目录跨页原子一致。读取检查前后身份/元数据，处理 UTF-8 完整码点边界；不把中文或 Emoji 分段误判为二进制，不把片段 hash 称为完整文件 hash。拒绝越界、任意子路径 symlink 和特殊文件，不创建诊断目录。系统拒绝与文件不存在分别报告。

工具要求 ADB 调试能力和已授权 Shizuku，普通只读调用无需逐次批准。配置 write/backup/restore 保持原 synthetic 范围，Shell 策略没有放宽。结果继续脱敏，并按 JSON 转义后的预算裁剪，防止结构化结果被外层截断。

app_logs 使用固定 ps/logcat argv，没有任意 flags 或 shell -c，不启动应用、不清日志、不回退读取全设备日志。默认 300 行，最大 2,000 行与 64 KiB，扫描预算 8 MiB、总时限 10 秒，异常/取消清理进程。多 PID 明确标识按进程拼接，保留时间戳；空结果不能证明没有历史错误。

## Agent 手册

adb-operations Skill 目录已加入文件/日志任务，通过按需参考页 files-and-logs.md 说明“确认包名 → 定位 → stat → 先读尾部 → 补前文 → 应用日志”。没有实际日志不能拿截图或测试配置替代；没有异常证据不能断言 OOM、死锁或具体 Mod 兼容错误。

## 验证

- 设备核心 JVM 284 项、App JVM 241 项、Runtime 388 项、聊天 Web 137 项测试通过。核心包括共享文件 16 项、应用日志 9 项、自动后端选择 2 项。
- Debug/AndroidTest APK 与 Android lint 构建通过。
- 模拟器真实 Shizuku UID 2000 仪器测试通过：经过 Native schema、能力授权、Gateway、AndroidBackend、Binder/UserService，验证查找、元数据、分页、超过 64 KiB 的中文/Emoji 日志尾部与后续字节、自动 `/sdcard` 后端、虚拟存储根、拒绝范围外路径、缺失文件、legacy 配置、指定应用 logcat 与未运行包。读取前后文件 hash 一致，并验证 Android JSON 转义后结果未超预算。

模拟器使用隔离夹具和临时配置，未访问产品数据库、密钥或真实模型，未操作用户游戏。日志和构建证据在本地忽略目录 build/shared-files-fix/。

按用户结束真机采集、随后可以拔机的安排，本轮未操作或安装真机。重新连接后，仍需通过新版 UserService 与 Agent 任务复测实际 SMAPI 路径；本次修复不表示 SMAPI 加载故障本身已经解决。
