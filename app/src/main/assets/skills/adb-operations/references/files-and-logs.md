# 共享存储与应用日志排查

仅在任务需要读取文件或排查应用日志时读取本页。使用模型工具名 `files_list`、`files_stat`、`files_find`、`files_read` 和 `debug_app_logs`；这些结构化只读工具依赖会话已开启的 ADB 调试和已授权 Shizuku shell UID 2000，不需要逐次 UI 批准。权限未开启或服务未就绪时如实说明，不能自行修改权限。不要要求用户为了读真实日志先创建 `Download/ThorToolPack` 测试目录。

## 可读范围与后端

- `files_list`、`files_read` 默认 `backend="auto"`。绝对共享存储路径（如 `/sdcard/...`、`/storage/emulated/<当前用户编号>/...`、系统实际允许读取的 `/storage/<卷标>/...` 可移除卷）走 Shizuku；相对路径保留宿主合成配置目录的兼容行为。若操作真实共享存储，优先提供绝对路径，避免把合成测试配置当成游戏配置。
- 显式 `backend="shizuku"` 时，`.` 和相对路径以当前用户共享存储根为起点。`files_stat`、`files_find` 固定使用共享存储 Shizuku 只读后端，`files_find` 的默认 `path="."` 也是当前用户共享存储根。
- Android/data 的实际可读性受系统和 OEM 权限约束；一次成功只能证明本次路径可读。出现不存在或拒绝访问时按返回结果报告。shell 身份不能证明 `/data/data`、`/data/user`、其他应用私有目录或整张 SD 卡全部可读。
- 文件读取不会扩大 `files_backup/write/restore` 的范围；那些事务工具仍只支持宿主明确批准的合成配置目录。不要为读取日志调用写入、恢复、安装、任意 Shell 或提权工具。

## 按证据逐步定位

1. **确认应用与包名。** 用当前屏幕的应用信息或 `perception_find_apps` 进行定向查找。区分游戏本体、Mod loader 和模拟器，不用完整安装列表代替定向定位。
2. **定位目录。** 从已知应用共享目录开始 `files_list`，用 `offset` 和 `limit` 翻页；`offset` 为 0–10000，`limit` 为 1–100。若需要发现外置存储，用 `path="/storage"` 查看已配置且当前可访问的主存储与外置卷虚拟列表。不要把一页未包含目标解释为目录里没有目标。
3. **必要时搜索。** 用 `files_find` 在相关目录按 `name` 忽略大小写搜索文件名子串，**不是 glob 或正则**。例如 `name="SMAPI"`，不要写 `*.txt`。`maxDepth` 为 0–8（默认 4），`maxEntries` 为 1–10000（默认 2000），`limit` 为 1–100（默认 50）。检查返回的截断、遍历限额和路径错误；扫描未完成不能声称找不到日志。优先缩小搜索根和深度，避免扫描整个共享存储。
4. **先取元数据，再读尾部。** `files_stat` 确认路径、类型、大小等。`files_read` 使用 `tail=true,maxBytes=16384` 先读取最后一段；`tail=true` 要求 `offset=0`。`maxBytes` 上限 65536。明确所见只是日志尾部，不能把截断片段称为完整文件。
5. **按实际字节范围补前文。** 若需版本、初始化阶段或异常之前的信息，用 `tail=false,offset=0` 读取开头，或按返回的实际字节范围继续分段。offset 是字节偏移，不是行号；不要假定多字节 UTF-8 内容每字一字节。日志可能正在追加，注意文件大小、修改时间与读取范围变化，不能将两次变化中的文件拼接冒充同一时刻快照。
6. **再取应用运行日志。** 用 `debug_app_logs` 的确切 `packageName` 读取当前 PID 的有界 logcat，`maxLines` 为 1–2000（默认 300），`maxBytes` 为 1–65536（默认 65536）。它不会启动或重启应用，也不会清空日志；不接受自由 Shell 参数。进程未运行、无匹配日志和截断均须如实说明，不回退读取全设备无关日志。空结果不能排除历史错误或证明应用正常。

## SMAPI 示例

2026-10-18 在用户 Thor 上发现的路径为：

```text
/sdcard/Android/data/abc.smapi.gameloader/files/ErrorLogs/SMAPI-latest.txt
```

这是本次设备证据，不是所有 SMAPI 安装必定使用的包名或目录。后续先确认当前应用身份，再按当前实际目录定位。

示例只读步骤：

```json
{"tool":"files_list","arguments":{"path":"/sdcard/Android/data/abc.smapi.gameloader/files","limit":50,"offset":0}}
{"tool":"files_stat","arguments":{"path":"/sdcard/Android/data/abc.smapi.gameloader/files/ErrorLogs/SMAPI-latest.txt"}}
{"tool":"files_read","arguments":{"path":"/sdcard/Android/data/abc.smapi.gameloader/files/ErrorLogs/SMAPI-latest.txt","tail":true,"maxBytes":16384}}
{"tool":"debug_app_logs","arguments":{"packageName":"abc.smapi.gameloader","maxLines":300,"maxBytes":65536}}
```

已读片段若只停在 `Loading mod metadata...` 或 `Loading mods...`，只能说明最后记录到该阶段。没有异常堆栈、明确系统记录或其他证据，不得断言 OOM、死锁、确定性死循环，亦不得声称已经排除 manifest 或版本兼容问题。没有成功读取实际日志时，先报告访问阻碍，不以合成配置、猜测或屏幕卡住替代底层错误证据。

## 报告与数据边界

说明读取的真实路径、应用包名、读取范围、观察到的最后阶段、明确错误、尚未证实的假设和下一步。引用最少必要的日志行；账号、令牌、设备标识、存档和无关私人信息不写入 Skill 或配置。不把本地日志发送到网络、搜索服务或外部网站；用户如明确要求分享，另按其指定范围处理。优先保留应用运行与用户文件现状，不通过重启、删除 Mod 或改动存档来代替证据采集。
