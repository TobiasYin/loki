# Shizuku、设备 Shell 与配置文件

## 权限与后端

[事实] Shizuku UserService 可以运行于启动后端的身份：ADB 启动通常是 shell UID 2000，Root 启动则可能是 UID 0。官方 API 指南推荐 UserService，不能照搬旧 newProcess 方案。[Shizuku API](https://github.com/RikkaApps/Shizuku-API)。

[决定] 首版优先无 Root 的 Shizuku 路线，允许用户按官方引导启用/授权；不内置 ADB 服务、不声称普通 app 的 Runtime.exec 自带 shell 权限。检测服务存活、授权、实际 UID；意外 Root 身份不静默作为无 Root 测试结果，首版默认不使用 Root 后端。

无线调试配对、重启后的重新激活等由真实设备行为验证。[官方设置指南](https://shizuku.rikka.app/guide/setup/)。独立 APK 仍可能需要用户安装 Shizuku；这项外部前置条件必须在首次开启相关工具时显示。

## 文件可达性

| 路径类别 | 可能访问方式 | 不得做的推断 |
|---|---|---|
| Thor Agent 自己的私有目录 | 应用自身 API | 不把会话库/凭据暴露成模型 file 工具 |
| 用户选择的文档/导出配置 | SAF Uri 授权 | Uri 不是任意本地路径，权限可撤销，未必支持原子 rename |
| 共享存储 | 普通权限/SAF/Shizuku，按实际路径测试 | 不能从目录名推断读写一定可用 |
| Android/data 等受限位置 | Shell 路线可能可读/写，受版本/挂载/策略限制 | 不承诺所有固件均可访问 |
| 其他应用 /data/user/0 私有目录 | 一般普通 app/shell 无权；个别 debuggable 场景另论 | 不把 Shizuku 视为 Root；不能保证任意模拟器配置可读 |
| 系统分区/设备节点 | 特权、SELinux/挂载等限制 | 不以 adb_debug 开启作为可写证明 |

依据：[Android 存储](https://developer.android.com/training/data-storage)。无权限时优先提示模拟器自带导出/导入或用户选择可访问配置，不编造路径、不猜包名、不自动申请 Root。

## 调试工具契约

| 工具 | 关键参数 | 结果与限制 |
|---|---|---|
| debug.shell | command（Native 已审查单条设备命令）、timeout 默认 15s/最大 60s | stdout/stderr、exitCode/signal、duration、truncated；合计输出默认 64 KiB；不支持永久 daemon |
| debug.logs | package/process 过滤、since/duration、maxBytes | 有限日志与来源；默认脱敏，权限不足明确返回 |
| files.list | 已授权根范围、path、limit 默认 100 | 条目、类型、大小、分页标识；不递归无限遍历 |
| files.read | path 或 grantRef、offset、maxBytes 默认 64 KiB | 文本编码、内容/hash/截断；二进制首版只返回元数据 |
| files.backup | 目标、expectedHash | backupId、路径/Uri引用、原 hash、大小、时间、权限元信息 |
| files.write | 目标、expectedHash、backupId、完整内容或经审查变更 | 已校验的新 hash、恢复信息、atomic 标志；初值最大 1 MiB |
| files.restore | backupId、expectedCurrentHash | 恢复前副本、新 hash、可验证状态；冲突则停 |

上述模型文件工具全部依赖 adb_debug（即使物理读取可通过 SAF），普通聊天附件存储属于应用内部功能，不以此绕过调试开关。

## 配置变更事务

1. 定位：用户或已验证应用适配器提供实际路径；读取 stat/授权/格式，检查符号链接与规范路径，拒绝越出允许根。
2. 读原文并 hash；探明模拟器是否会在退出/运行中覆写配置。必要时用户手动停止应用；不擅自强停丢失游戏进度。
3. 生成变更说明和差异，保留未知字段/编码/换行。首版结构化文件写默认需要用户对具体差异确认，批准绑定 path＋旧 hash＋新内容 hash，有变化则失效。
4. 写前备份成功且可读回才继续；记录来源、大小、mtime、hash、相关版本及权限。备份在 Agent 私有区保存密文，不能仅存远端模型摘要。
5. 再次核对 expectedHash、授权 epoch 和资源锁；可支持时同目录临时写→flush→rename，保持原权限并验证。SAF/provider 无原子替换时明确 nonAtomic，采用有备份的可恢复流程；失败不得宣称回滚保证。
6. 写后读回、格式校验与 hash 校验；模拟器运行效果属于另一次验证，不以写成功代表生效。
7. 恢复先备份当前版本；若当前文件已变化则阻止静默覆盖；恢复后读回并记录。撤销权限后不在后台继续自动恢复写入，用户可通过明确的恢复交互重新授权。

备份初始策略：每目标保留最近 10 份，总预算 100 MiB，达到预算先询问清理或拒绝新写；不无声删除唯一恢复点。数值待实测，策略需要设置页可见。

## 设备命令分级

实现与真机验收见 [ADB 与 Thor 配置](adb-policy-acceptance.md)。debug.command_policy 可读取规则或检查具体命令；debug.shell 只执行已审查 argv。亮度、风扇、刷新率等限定常用调整是白名单，无需 UI 批准；敏感读取与按键分配变更是灰名单，每次精确确认；危险或未知形式禁止。不能通过用户确认运行黑名单命令。

Thor 独立屏幕亮度由 debug.thor_brightness 固定接口处理，避免错误地把副屏百分比存储值当作背光控制。完整操作手册为内置 adb-operations Skill，目录只发布元数据，具体参考资料由 Agent 按需读取。文件写入继续使用已有备份、指纹与页面审批。

## 验证门槛

POC-08 在用户指定模拟器的真实配置上做“读取→备份→无害变更→恢复”闭环，并记录权限和文件系统类型。首次只用测试配置副本，不碰游戏存档。原文件不存在、空间不足、断进程、符号链接替换、并发覆写、权限撤销都必须覆盖。

## 合成配置的预验证

独立 [Input Lab](input-lab.md) 可在后续阶段提供合成配置及应用内读回状态，先验证 hash、备份/恢复和冲突处理，再测试真实模拟器。其应用私有目录仍遵循普通 Android 沙箱；不得为测试便利向 Shell 开后门，测试 grant/导出也不证明真实模拟器可访问。输入/事件日志和封存报告不是 Shell 测试可改写的配置目标。
