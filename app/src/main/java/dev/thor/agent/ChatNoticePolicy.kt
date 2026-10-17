package dev.thor.agent

/** A tool receipt or cancellation must not become a persistent chat-wide error. */
internal object ChatNoticePolicy {
    fun rpcFailure(previous: String?, operation: String, code: String, storageFailed: Boolean, modelConfigured: Boolean): String? {
        if (storageFailed) return "本地存储不可用，历史已保留。"
        if (operation == "tool.prepare" || operation in setOf("tool.cancel", "authority") && code in setOf("STALE_AUTHORIZATION", "CANCELLED")) return previous
        if (operation == "begin") return if (!modelConfigured) "模型未配置：请在设置填写 API Key 和服务地址。" else "任务暂未启动：检查当前会话是否仍有任务，或查看模型设置中的具体错误。"
        return "操作被拒绝：检查授权、活动运行或本地存储。"
    }
}
