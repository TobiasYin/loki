# Luna 输出预算验证证据

AYN Thor / Android 13，2026-10-17，真实保存的 OpenCode Go Key 和设备网络。报告只含固定标签、布尔值和数值，不含 Key、用户原文、模型正文、响应 ID、headers 或 encrypted content。

- `physical-luna-budget.json`：原全局 2048 与官方自动 128000 的两个真实请求；同一合成问题、35 个工具声明，零工具执行。两者均 completed，不能声称旧失败已完整复现。
- `physical-luna-runtime.json`：正常产品 Runtime 两轮均通过，实际 SDK 预算 128000，追问复用同一 Pi session，零工具调用。测试结束后删除自有会话。
- `comparison-instrumentation.txt`：协议对照单项最终通过，16.953 秒。
- `initial-instrumentation.txt`：初次 suite 原样保留。第一项因设备已休眠，45 秒内聊天页面不可见而失败，未发送请求；唤醒后第二项普通 Runtime/追问通过。测试随后添加临时保持亮屏和清理，第一项重跑通过。该文件不是 clean suite pass。
- `preservation.json`：原数据库所有列、所选会话及四份配置文件字节一致；安装 APK 与固定构建哈希一致。
- `apk-sha256.json`：应用与测试 APK SHA-256；普通 Runtime 通过后仅测试 APK 的临时保持亮屏及清理有调整，生产 APK 一直一致。
- `software.json`：软件校验汇总。

历史原始故障仅有固定 `MODEL_OUTPUT_LIMIT / length` 标签，缺失 token usage。输出预算被旧隐式 2048 压低是已确认的配置问题；无法追溯其具体 token 分配。新增每轮安全计量可用于下一次故障精确诊断，不通过把超限当成功或重放截断工具来隐藏问题。
