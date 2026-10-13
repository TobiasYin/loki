package dev.thor.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A serial Native host keeps the lease across Activity rotation, never across process recovery. */
class Host private constructor(context: Context) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val store = SqliteStore(context.applicationContext)
    private val files = AttachmentFiles(context.applicationContext)
    private lateinit var coordinator: Coordinator
    private val runtime: RuntimePort = SimulatedRuntime()
    private var steps = emptyList<Step>(); private var cursor = 0; private var boundRun: String? = null
    private var listener: ((JSONObject) -> Unit)? = null
    private var listenerOwner: Any? = null
    fun attach(owner: Any, callback: (JSONObject) -> Unit) { listenerOwner = owner; listener = callback }
    fun detach(owner: Any) { if(listenerOwner === owner) { listenerOwner = null; listener = null } }
    fun isAttached(owner: Any) = listenerOwner === owner
    init { executor.execute {
        try { coordinator = Coordinator(store, attachments = files); files.cleanup(coordinator.state.getJSONArray("attachments").objects()) } catch(_: Exception) { /* Preserve files and report a bounded Native recovery error. */ }
        publish()
    } }
    fun command(command: JSONObject) = executor.execute {
        if(!::coordinator.isInitialized) { publish(); return@execute }
        try {
            when(command.getString("op")) {
                "ready" -> Unit
                "new" -> coordinator.newSession()
                "select" -> coordinator.select(command.getString("sessionId"))
                "rename" -> coordinator.rename(command.getString("sessionId"), command.getString("title"))
                "archive" -> coordinator.archive(command.getString("sessionId"), command.optBoolean("archived", true))
                "delete" -> { coordinator.delete(command.getString("sessionId")); files.cleanup(coordinator.state.getJSONArray("attachments").objects()) }
                "capability" -> coordinator.capability(command.getString("sessionId"), command.getString("capability"), command.getBoolean("enabled"))
                "send" -> {
                    val scenario = command.optString("scenario", "success"); require(scenario in setOf("success", "failure", "waiting", "unknown", "long", "many"))
                    val prompt = command.getString("text")
                    boundRun = coordinator.start(command.getString("sessionId"), prompt, scenario, contextRevision())
                    steps = runtime.plan(prompt, scenario); cursor = 0; schedule(boundRun!!)
                }
                "stop" -> coordinator.cancel(command.getString("runId"))
                "approve" -> { coordinator.approve(command.getString("runId")); schedule(command.getString("runId")) }
                "expanded" -> coordinator.expanded(command.getString("runId"), command.getBoolean("expanded"))
                "seed" -> coordinator.seedHistory()
                else -> error("不支持的桥命令")
            }
            publish()
        } catch(error: Exception) { if(error is PersistenceFailure) boundRun = null; publish("操作被拒绝：检查会话、活动运行、输入长度或授权状态；没有调用真实服务。") }
    }
    private val profiles = context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private fun contextRevision() = profiles.getInt("revision", 0)
    private fun schedule(rid: String) {
        executor.schedule({
            if(boundRun != rid || coordinator.active()?.getString("id") != rid || coordinator.active()?.getString("status") == "awaiting_user") return@schedule
            if(cursor >= steps.size) return@schedule
            val step = steps[cursor++]
            try {
                val running = coordinator.step(rid, step); publish()
                if(running && step.type != "wait") schedule(rid)
            } catch(_: Exception) { boundRun = null; runCatching { coordinator.cancel(rid, "存储或模拟适配器出错；运行停止") }; publish("存储或适配器失败，已停止后续事件；恢复时不会重放") }
        }, 180, TimeUnit.MILLISECONDS)
    }
    fun interrupt(reason: String) = executor.execute { if(::coordinator.isInitialized) coordinator.active()?.let { coordinator.interrupt(it.getString("id"), reason) }; publish() }
    private fun publish(error: String? = null) {
        val view = if(::coordinator.isInitialized) coordinator.snapshot() else obj("fatal" to "本地存储恢复失败。请检查磁盘空间或重新打开应用；历史文件保留，未执行任何动作。"); if(error != null) view.put("error", error)
        main.post { listener?.invoke(view) }
    }
    companion object {
        @Volatile private var instance: Host? = null
        fun get(context: Context): Host = instance ?: synchronized(this) { instance ?: Host(context).also { instance = it } }
    }
}
