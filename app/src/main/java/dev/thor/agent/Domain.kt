package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
fun id(): String = UUID.randomUUID().toString()
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
val terminal = setOf("completed", "failed", "cancelled", "interrupted")
val capabilities = listOf("perception", "search", "screen_control", "debug")

/** Domain entities stay separate from any provider's transcript. No credentials are accepted here. */
class PersistenceFailure(cause: Exception) : RuntimeException("本地存储失败；已停止执行", cause)
interface NativeStorePort {
    fun load(): JSONObject
    fun save(state: JSONObject)
}
interface AttachmentPort { fun createText(sessionId: String, runId: String?, text: String): JSONObject }
interface DevicePort {
    fun availability(capability: String): String
    fun execute(tool: String): String
}
class SimulatedDevice : DevicePort {
    override fun availability(capability: String) = when (capability) {
        "perception" -> "模拟就绪 · 无真实无障碍授权"
        "search" -> "真实搜索不可用 · 未接入后端；模拟示例可用"
        "screen_control" -> "模拟就绪 · 触摸/手柄实机未验证"
        else -> "模拟就绪 · 无真实 Shell/文件权限"
    }
    override fun execute(tool: String) = when (tool) {
        "app_info" -> "模拟应用：Thor Input Lab，版本 demo；没有读取真实设备"
        "read_ui" -> "模拟界面：音量 60%，设置面板；没有遍历真实 UI"
        "write_config" -> "仅模拟提交；没有写入设备配置"
        else -> "仅模拟结果"
    }
}
data class Step(val type: String, val text: String = "", val tool: String = "", val capability: String = "perception")
interface RuntimePort { fun plan(prompt: String, scenario: String): List<Step> }
class SimulatedRuntime : RuntimePort {
    override fun plan(prompt: String, scenario: String): List<Step> {
        val tools = listOf(Step("start", tool="app_info"), Step("result", tool="app_info"), Step("start", tool="read_ui"), Step("result", tool="read_ui"))
        val answer = when (scenario) {
            "long" -> (1..45).joinToString("\n\n") { "第 $it 段 · 这是确定性长回答，用来检查滚动、换行与流式增量。https://example.invalid/very/long/path 示例不会联网。" }
            else -> "这是模拟 Agent 的回答。已按当前会话能力完成模拟工具事件，没有调用模型、搜索服务或真实设备。\n\n你可以切换会话、关闭上屏感知或停止运行，观察可复现的状态变化。"
        }
        val body = answer.chunked(28).map { Step("delta", it) }
        return when (scenario) {
            "failure" -> listOf(Step("start", tool="app_info"), Step("failure", "模拟工具失败：读取被拒绝", tool="app_info"))
            "waiting", "unknown" -> listOf(Step("wait", "模拟修改配置：volume 60 → 70；仅此对象可批准，不执行真实写入。", "write_config", "screen_control")) +
                listOf(Step("start", tool="write_config", capability="screen_control"), Step(if (scenario == "unknown") "unknown" else "result", tool="write_config", capability="screen_control")) + body + Step("complete")
            "many" -> (1..15).flatMap { listOf(Step("start", tool="app_info"), Step("result", tool="app_info")) } + body + Step("complete")
            "long" -> body + Step("complete")
            else -> tools + body + Step("complete")
        }
    }
}

/** All mutations run on the host's serial executor. Steps carry a Native-bound runId and epoch. */
class Coordinator(private val store: NativeStorePort, private val device: DevicePort = SimulatedDevice(), private val clock: () -> Long = System::currentTimeMillis, private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 }, private val attachments: AttachmentPort? = null) {
    var state = store.load(); private set
    init {
        for (key in listOf("sessions", "messages", "runs", "events", "attachments")) if (!state.has(key)) state.put(key, JSONArray())
        recover()
        if (sessions().isEmpty()) newSession()
        if (sessions().none { it.getString("id") == state.optString("selected") && it.isNull("archivedAt") }) {
            state.put("selected", sessions().firstOrNull { it.isNull("archivedAt") }?.getString("id"))
            if (state.isNull("selected")) newSession()
        }
        persist()
    }
    private fun persist() { try { store.save(state) } catch (error: Exception) { state = store.load(); throw PersistenceFailure(error) } }
    fun sessions() = state.getJSONArray("sessions").objects()
    fun runs() = state.getJSONArray("runs").objects()
    fun events() = state.getJSONArray("events").objects()
    fun active() = runs().firstOrNull { it.getString("status") !in terminal }
    fun session(sid: String) = sessions().firstOrNull { it.getString("id") == sid } ?: error("会话不存在")
    private fun run(rid: String) = runs().firstOrNull { it.getString("id") == rid } ?: error("运行不存在")
    private fun touch(s: JSONObject) { s.put("updatedAt", clock()) }
    fun newSession(): String {
        val sid = id(); val now = clock()
        state.getJSONArray("sessions").put(obj("id" to sid, "title" to "新会话", "createdAt" to now, "updatedAt" to now, "archivedAt" to null,
            "capabilities" to obj("perception" to true, "search" to true, "screen_control" to false, "debug" to false), "capabilityEpoch" to 0, "modelProfileId" to "demo"))
        state.put("selected", sid); persist(); return sid
    }
    fun select(sid: String) { require(session(sid).isNull("archivedAt")) { "请先恢复归档会话" }; state.put("selected", sid); persist() }
    fun rename(sid: String, title: String) { require(title.isNotBlank() && title.length <= 80); session(sid).put("title", title.trim()); touch(session(sid)); persist() }
    fun archive(sid: String, archive: Boolean = true) {
        require(active()?.getString("sessionId") != sid) { "此会话正在运行，请先停止" }
        session(sid).put("archivedAt", if (archive) clock() else JSONObject.NULL); touch(session(sid))
        if (archive && state.optString("selected") == sid) {
            val other = sessions().firstOrNull { it.isNull("archivedAt") }
            if (other == null) newSession() else state.put("selected", other.getString("id"))
        }
        persist()
    }
    fun delete(sid: String) {
        require(active()?.getString("sessionId") != sid) { "先停止活动运行" }
        val rids = runs().filter { it.getString("sessionId") == sid }.map { it.getString("id") }.toSet()
        for (key in listOf("sessions", "messages", "runs", "events", "attachments")) {
            state.put(key, JSONArray(state.getJSONArray(key).objects().filter { row ->
                if (key == "sessions") row.getString("id") != sid else row.optString("sessionId") != sid && row.optString("runId") !in rids
            }))
        }
        if (state.optString("selected") == sid) {
            state.put("selected", sessions().firstOrNull { it.isNull("archivedAt") }?.getString("id"))
            if (state.isNull("selected")) newSession()
        }
        persist()
    }
    fun capability(sid: String, cap: String, enabled: Boolean) {
        require(cap in capabilities)
        val s = session(sid); if (s.getJSONObject("capabilities").getBoolean(cap) == enabled) return
        s.getJSONObject("capabilities").put(cap, enabled); s.put("capabilityEpoch", s.getLong("capabilityEpoch") + 1); touch(s)
        active()?.takeIf { it.getString("sessionId") == sid }?.let { r ->
            event(r, "authorization", "能力变更：$cap ${if(enabled) "开启" else "撤销"}；旧授权不能复活")
            // Conservative first edition: terminate the whole run on revocation; a new run uses fresh epoch.
            if (!enabled) finish(r, "cancelled", "能力已撤销；此运行已取消，仅后续新运行可使用新授权")
        }
        persist()
    }
    fun start(sid: String, prompt: String, scenario: String, modelRevision: Int = 0): String {
        require(active() == null) { "设备已有活动运行，请先停止" }
        require(prompt.isNotBlank() && prompt.length <= 8000) { "输入限 1–8000 字符" }
        val s = session(sid); require(s.isNull("archivedAt")); val rid = id(); val user = id()
        state.getJSONArray("messages").put(obj("id" to user, "sessionId" to sid, "runId" to rid, "role" to "user", "text" to prompt, "status" to "completed", "attachments" to JSONArray(), "sources" to JSONArray(), "createdAt" to clock()))
        state.getJSONArray("messages").put(obj("id" to id(), "sessionId" to sid, "runId" to rid, "role" to "assistant", "text" to "", "status" to "streaming", "attachments" to JSONArray(), "sources" to JSONArray(), "createdAt" to clock()))
        val r = obj("id" to rid, "sessionId" to sid, "triggerMessageId" to user, "status" to "preparing", "modelRevision" to modelRevision, "modelProfileId" to "demo", "startAt" to clock(), "startElapsedMs" to monotonic(), "endAt" to null,
            "stopReason" to "", "registryVersion" to "demo-1", "epoch" to s.getLong("capabilityEpoch"), "scenario" to scenario, "expanded" to true)
        state.getJSONArray("runs").put(r); event(r, "preparing", "确定性模拟运行；不请求真实模型")
        if (s.getString("title") == "新会话") s.put("title", prompt.take(24)); touch(s); persist(); return rid
    }
    private fun event(r: JSONObject, type: String, text: String, toolCallId: String? = null, outcome: String = "", tool: String = "", cap: String = "") {
        val sequence = events().count { it.getString("runId") == r.getString("id") } + 1
        state.getJSONArray("events").put(obj("id" to id(), "runId" to r.getString("id"), "sessionId" to r.getString("sessionId"), "sequence" to sequence,
            "type" to type, "summary" to text.take(2048), "toolCallId" to toolCallId, "outcome" to outcome, "toolId" to tool, "capability" to cap, "wallTime" to clock(), "elapsedMs" to (monotonic() - r.optLong("startElapsedMs", monotonic())).coerceAtLeast(0)))
    }
    private fun assistant(rid: String) = state.getJSONArray("messages").objects().first { it.optString("runId") == rid && it.getString("role") == "assistant" }
    private fun pending(r: JSONObject) = events().lastOrNull { e -> e.getString("runId") == r.getString("id") && e.getString("type") == "tool_start" &&
        events().none { it.optString("toolCallId") == e.optString("toolCallId") && it.getString("type") == "tool_result" } }
    fun step(rid: String, step: Step): Boolean {
        val r = run(rid); if (r.getString("status") in terminal || r.getString("status") == "awaiting_user") return false
        if (monotonic() - r.optLong("startElapsedMs", monotonic()) > 600_000) { finish(r, "failed", "达到 10 分钟运行上限"); persist(); return false }
        val s = session(r.getString("sessionId"))
        when (step.type) {
            "delta" -> {
                val m = assistant(rid); val text = m.getString("text") + step.text
                if (text.length > 65536) finish(r, "failed", "回答达到 64 KiB 字符上限") else { m.put("text", text); r.put("status", "generating") }
            }
            "start", "wait" -> {
                if (r.getLong("epoch") != s.getLong("capabilityEpoch") || !s.getJSONObject("capabilities").getBoolean(step.capability)) {
                    finish(r, "failed", "工具授权不可用：${step.capability}；开启后请发起新运行")
                } else if (events().count { it.getString("runId") == rid && it.getString("type") == "tool_start" } >= 30) {
                    finish(r, "failed", "达到 30 个模拟工具上限")
                } else if (step.type == "wait") {
                    r.put("status", "awaiting_user"); event(r, "awaiting_user", step.text, tool=step.tool, cap=step.capability)
                } else {
                    r.put("status", "executing_tools"); event(r, "tool_start", "${toolLabel(step.tool)} · 模拟执行中", id(), "pending", step.tool, step.capability)
                }
            }
            "result", "failure", "unknown" -> {
                val intent = pending(r) ?: error("缺少工具 intent")
                val outcome = when(step.type) { "failure" -> "failed"; "unknown" -> "unknown"; else -> "completed" }
                val resultText = when(step.type) { "failure" -> step.text; "unknown" -> "模拟提交后确认丢失；副作用未知，不能宣称已改变设备"; else -> device.execute(step.tool) }
                // Publish a result only after its optional file has been written successfully. Execute once.
                if (outcome == "completed") attachments?.createText(r.getString("sessionId"), rid, resultText)?.let { attachment ->
                    state.getJSONArray("attachments").put(attachment)
                    assistant(rid).getJSONArray("attachments").put(attachment.getString("id"))
                }
                event(r, "tool_result", resultText, intent.getString("toolCallId"), outcome, step.tool, step.capability)
                if (outcome != "completed") finish(r, "failed", if(outcome == "unknown") "存在未知副作用，需要检查；不自动重放" else step.text)
            }
            "complete" -> finish(r, "completed", "")
            else -> error("未知事件")
        }
        persist(); return r.getString("status") !in terminal
    }
    fun approve(rid: String) { val r = run(rid); require(r.getString("status") == "awaiting_user"); require(r.getLong("epoch") == session(r.getString("sessionId")).getLong("capabilityEpoch")); r.put("status", "executing_tools"); event(r, "approved", "已批准当前模拟配置差异；没有真实写入"); persist() }
    fun cancel(rid: String, reason: String = "用户停止；已完成结果保留") { val r = run(rid); if (r.getString("status") in terminal) return; r.put("status", "stopping"); event(r, "stopping", "正在停止"); finish(r, "cancelled", reason); persist() }
    fun interrupt(rid: String, reason: String) { finish(run(rid), "interrupted", reason); persist() }
    fun expanded(rid: String, expanded: Boolean) { run(rid).put("expanded", expanded); persist() }
    private fun finish(r: JSONObject, status: String, reason: String) {
        if (r.getString("status") in terminal) return
        pending(r)?.let { event(r, "tool_result", "执行被打断；没有结果确认，副作用未知（模拟）", it.getString("toolCallId"), "unknown", it.getString("toolId"), it.getString("capability")) }
        r.put("status", status).put("endAt", clock()).put("stopReason", reason).put("expanded", false)
        assistant(r.getString("id")).put("status", status); event(r, status, reason.ifBlank { "模拟运行完成" })
    }
    private fun recover() { runs().filter { it.getString("status") !in terminal }.forEach { finish(it, "interrupted", "宿主/进程已退出；运行中断，不重放任何动作") }; persist() }
    fun snapshot(): JSONObject = JSONObject(state.toString()).apply { getJSONArray("attachments").objects().forEach { it.remove("path") } }.put("availability", obj(*capabilities.map { it to device.availability(it) }.toTypedArray())).put("mode", "deterministic-demo")
    fun seedHistory() {
        require(active() == null)
        val sid = state.getString("selected")
        repeat(100) { i -> for (role in listOf("user", "assistant")) state.getJSONArray("messages").put(obj("id" to id(), "sessionId" to sid, "runId" to null, "role" to role, "text" to "历史 ${i+1} · $role · 本地长历史模拟示例", "status" to "completed", "attachments" to JSONArray(), "sources" to JSONArray(), "createdAt" to clock())) }
        repeat(49) { newSession() }; state.put("selected", sid); persist()
    }
    companion object { fun toolLabel(tool: String) = when(tool) { "app_info" -> "查看应用版本"; "read_ui" -> "读取上屏界面"; "write_config" -> "修改配置"; else -> tool } }
}
