# 数据存储、保留与恢复

## 单一事实源

[决定] Android Native 私有存储持有会话与运行数据；SQLite/Room 是候选。WebView 内存只缓存视图，localStorage/IndexedDB 不作为会话或凭据的权威数据库。附件以独立文件保存，数据库保存引用和 hash。

| 实体 | 主要字段（逻辑设计，不是 SQL） |
|---|---|
| Session | id、title、created/updatedAt、archivedAt、四能力值、capabilityEpoch、modelProfileId |
| Message | id、sessionId、runId、role、文本/附件引用、状态、来源权限标签 |
| Run | id、sessionId、触发消息、状态、modelRevision、start/end、stopReason、registryVersion |
| RunEvent | id、runId、sequence、type、toolCallId、时间、脱敏摘要与详情引用 |
| ToolInvocation | toolCallId、toolId/version、参数 hash/受控详情、epoch、目标、intent/结果状态 |
| ContextSnapshot | requestId、runId、历史范围、L0/工具版本、附件 manifest、估算用量、hash |
| Attachment | id、mime、路径、大小、hash、来源权限、关联 Run、过期时间 |
| Summary | id、源消息范围/hash、生成模型修订、内容、来源权限、策略版本 |
| ModelProfile/SearchProfile | 配置元数据、revision、credentialRef、probe 结果 |
| ConfigBackup | backupId、目标引用、原 hash、加密副本引用、元数据、写入/恢复关系 |

表名只是领域模型，正式 schema 及索引在后续开发时根据查询模式设计。所有 ID 和 sequence 必须支撑去重及跨对象关联；外键/事务防止聊天显示“成功”却缺工具结果。

## 文件布局（设备端拟议）

应用私有区分 conversation database、attachments、config-backups、bounded diagnostics。路径不暴露为模型可直接访问地址；模型只能通过授权工具得到必要结果。WebView asset 目录只读，与用户内容分离。

API Key 密文放私有存储，由 Android Keystore 中的密钥加解密；Keystore 不直接保存任意 API 文本。[Keystore 官方说明](https://developer.android.com/privacy-and-security/keystore)。硬件支持级别需实际查询；不承诺 Thor 有 StrongBox，也不声称运行中解密后的 key 永不进入内存。

## 默认保留策略（设计初值）

| 数据 | 默认策略 |
|---|---|
| 用户/Assistant 文本 | 本地保留到用户删除；归档只影响列表 |
| 工具事件摘要 | 随 Session；长 stdout/UI 树详情保留 7 天 |
| 截图/原始诊断附件 | 24 小时后可清理；用户主动保留除外；引用失效清楚显示 |
| ContextSnapshot | 保留 manifest/hash，默认不保留完整请求体 |
| 搜索缓存 | 30 分钟新鲜度，过期可清理 |
| 配置备份 | 每目标最近 10 份、合计 100 MiB；满时拒绝新写或用户清理，不自动删除唯一恢复点 |
| 故障日志 | 环形有界、脱敏，初值 10 MiB/7 天 |

“本地完整可追溯”指保留事件和引用，不承诺永久保存每一张原图和每个原始字节。UI 折叠不删除数据，保留策略清理则显示已过期。用户可延长保留；日志/文件可能敏感，默认不云同步、不遥测上传。

## 删除、导出、系统备份

删除 Session 时先停止活动 Run，事务移除消息/事件及无其他引用附件；配置备份有独立恢复价值，提示并默认单独保留到用户处理。导出默认为中文可读聊天和动作摘要，图像/日志/配置文件需单独勾选，凭据永不导出。

未来配置 Android 自动备份/设备迁移规则时，明确排除 API Key 密文、密钥引用、截图、日志及配置备份；会话是否加入系统备份默认关闭，需后续明确决定。不能依赖文件删除提供闪存安全擦除保证。

## 故障恢复

Run intent 与结果分开写入；崩溃后未知副作用保持 unknown。临时附件先写后校验再发布引用；启动清理无引用临时文件。数据库迁移有版本、备份和失败回退；升级失败不能清空历史后伪称成功。

Keystore 密钥丢失/设备迁移后密文不可读时要求重新配置 key，不能丢弃会话；配置备份不可解密则标记不可恢复，保留诊断元信息。磁盘不足时停止新附件/配置写入，能显示既有聊天并告知原因。
