# SMAPI 日志能力真机调查（2026-10-18）

本轮只读取会话与设备证据，没有安装或重启应用，没有改动游戏、Mod、存档、权限和系统设置。采集完成后已明确通知用户可以断开设备；后续不再调用 ADB。文件读取功能修复尚未实施，本文保留后续开发与真机验收所需依据。

## 目标会话

- 标题：`上品的 SMAPI 卡在了这个 loading mod 帮我排查一下它底层报了什么错？`
- Session：`4ff31867-7f6a-4bbf-9514-ee25c6bf41e6`
- Run：`8571740f-2beb-4e0c-b4f0-4cf9a82052e1`，状态 completed。
- 会话已开启感知、联网、屏幕控制和 ADB 调试，不能把失败归因于用户未开启 ADB 能力。

工具记录中两次共享存储读取均失败：

| 源事件序号 | 工具与参数 | 结果 |
|---|---|---|
| 127 起的调用 | `files.list(backend=shizuku,path=/sdcard)` | `PERMISSION_MISSING`，`Create/grant diagnostic directory first` |
| 252 起的调用 | `files.list(backend=shizuku,path=/storage/emulated/0/Android/data/abc.smapi.gameloader/files)` | 同上 |
| 255 起的调用 | `files.list(path=.)` | 只返回宿主合成目录中的 `settings.json` |
| 323 起的调用 | `files.read(path=settings.json)` | 读取的是宿主测试配置，不是 SMAPI 文件 |

会话还检查了 `logcat -d -t 300`、`logcat -d`、`ls /sdcard` 等命令的策略，但没有真正的 `debug.shell` 调用。这些命令形式在当前 ShellCommandPolicy 中没有被允许。

## 实际文件与系统可读性

日志真实路径：

```
/sdcard/Android/data/abc.smapi.gameloader/files/ErrorLogs/SMAPI-latest.txt
```

- 使用实际 ADB shell UID 2000 读取成功，退出码 0，文件为 1,066 字节。
- 文件 SHA-256：`35c77664253bb753d3238698c426d2dab1aea99dc446a56e9459e60ca6c48fdc`。
- `stat` 返回：大小 1066、mtime `1792328804`、mode `660`；对应本次日志最后时间 21:06:44。
- 采集前后文件字节完全一致。
- Shizuku server 当时也在运行，UID 2000。尚未更改文件后端，不能把 ADB 读取成功等同于已经验证新版 UserService 工具成功。
- 固定测试根 `/storage/emulated/0/Download/ThorToolPack` 实际不存在。

目前 `DebugUserService.kt` 的文件操作会先检查该固定根是否存在，再使用 LocalFiles 限制在此根内。真实日志所在目录因此根本没有被尝试读取。失败提示是我们工具策略的结果，不能据此断言 Android 或 Shizuku 无权读取目标。

## 日志确实说明了什么

实际版本：SMAPI `4.3.2.1`，Launcher `1.1.7`，Stardew Valley `1.6.15.3 build 26177`。

日志最后两行：

```
[21:06:44 DEBUG SMAPI] Loading mod metadata...
[21:06:44 DEBUG SMAPI] Loading mods...
```

文件没有 ERROR、异常堆栈或明确标记失败的 Mod。Mods 根目录只列出一个 Mod：Android Consolizer，manifest 版本 `4.0.2`，入口 `AndroidConsolizer.dll`，同目录另有 `AndroidControllerFix.dll`。声明的 MinimumApiVersion 为 `4.0.0`；Generic Mod Config Menu 被声明为可选依赖。

SMAPI 进程当时仍存在，PID 20860、UID 10160、状态 sleeping、28 个线程、VmRSS 160452 KiB。针对该 PID 的 `logcat -d` 成功执行但返回零行。没有信号、线程转储或重启操作。现有证据不能确定死循环、死锁、OOM 或具体 Mod 的兼容性错误。

原 Agent 把“确定性卡死”“加载元数据已经通过”“不是 manifest 问题”“某类 DLL/OOM 导致挂住”等判断说得过于确定；这些结论应降为待验证假设。也不应在只有一个 Mod 的情况下套用大量 Mod 的二分排查建议，或让用户寻找尚不存在的任意目录授权入口。

## 已明确的能力缺口

1. `files.list/read` 的 Shizuku 后端只面向固定测试根，不能访问真实共享存储，且没有规范化 `/sdcard` 等平台别名。
2. 默认 backend 是 synthetic，工具描述没有把“合成测试配置”和“设备文件”区别解释清楚。
3. Shizuku read 只允许 offset 0，没有日志尾部读取；64 KiB 以上日志无法读后续部分。
4. list 没有稳定分页、类型、大小和修改时间；缺 stat 与有界文件查找。
5. 缺少经过审查的按应用抓取 logcat 的能力；现有 shell 策略不能兜底。
6. 内置 adb-operations Skill 缺文件日志排查流程，也没有要求区分“屏幕观察”“已经读取的文件”和“推测”。

后续应先补共享存储只读访问、元数据、分页与尾部读取，再增加应用范围的日志工具与按需手册。写入、备份、恢复仍保持原范围；不因读取需求开放任意 Shell 写操作。修复后需要通过真实 UserService 与模型工具链复测本路径，再确认游戏问题；离线开发可使用这份证据和模拟日志夹具。

## 本地证据

原始诊断只保存在忽略目录 `build/smapi-investigation/`：`audit.json`（仅目标会话）、`device-evidence.json`、`smapi-latest.redacted.txt`、`smapi-logcat.redacted.txt`、`mod-manifest.json`、`mod-directory.txt`。未保存用户其他会话或凭据，日志仅本地分析，未上传第三方。
