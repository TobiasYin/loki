package dev.thor.agent

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import dev.thor.bridge.Authority
import dev.thor.bridge.Binding
import dev.thor.bridge.Purpose
import dev.thor.bridge.NetworkProfile
import dev.thor.bridge.NetworkPolicy
import dev.thor.tools.*
import dev.thor.tools.diagnostic.*
import dev.thor.integration.DeviceAuthority
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Owns product state, Native leases and audited RPC. No model protocol parsing. */
class ProductHost(private val context: Context, private val notify: (JSONObject) -> Unit) : AutoCloseable {
    val authority = Authority(dev.thor.bridge.MonotonicClock { SystemClock.elapsedRealtime() })
    private val serial = Executors.newSingleThreadExecutor()
    private val deviceWorker = Executors.newSingleThreadExecutor()
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private val store = SqliteStore(context, "product.db")
    private var state = store.load()
    private var binding: Binding? = null
    private var lastError: String? = null
    private var storageFailed = false
    @Volatile private var closing = false
    private var connection: String? = null
    private var snapshot: Snapshot? = null
    private val deviceAuthority = DeviceAuthority(authority) { snapshots.update(displayFacts(context)) }
    private val snapshots = Snapshots(clock) { ThorAccessibilityService.instance?.target(it) ?: Target(null, "unknown", "no-accessibility") }
    private val displayManager=context.getSystemService(android.hardware.display.DisplayManager::class.java)
    private var displayState=displayFacts(context)
    private val displayListener=object:android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id:Int)=changed()
        override fun onDisplayRemoved(id:Int)=changed()
        override fun onDisplayChanged(id:Int)=changed()
        private fun changed() {val facts=displayFacts(context);if(facts != displayState) {displayState=facts;snapshots.update(facts);revokeResources()} }
    }
    private val debug = DebugConnection(context) { revokeResources() }
    private val local = LocalFiles(File(context.filesDir, "approved-synthetic-config").toPath())
    private val backups = EncryptedVault(File(context.filesDir, "config-backups").toPath(), backupKey(), Clock { System.currentTimeMillis() })
    private val tx = ConfigTransactions(local, backups) { bytes -> JSONObject(bytes.toString(Charsets.UTF_8)); Unit }
    private val backend = AndroidBackend(context, clock, snapshots, debug, local, tx)
    private val schemas = InputSchemas(context)
    private val contract = context.assets.open("toolpack-v1.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("tools").objects().associateBy { t -> t.getString("toolId") } }
    private val gateway = Gateway(deviceAuthority, backend, snapshots, clock)
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val nativeVault = CredentialVault(context)
    private val revision = prefs.getInt("revision", 0)
    private val ref = "model@$revision"
    private val credentialRef = "model@$revision"
    private var searchUrl: String? = null
    private var searchConfigured = false
    private val networkCalls = mutableSetOf<String>()
    private val secret = runCatching { nativeVault.readNative("model") }.getOrNull()
    private val searchSecret = runCatching { nativeVault.readNative("search") }.getOrNull()
    private val redactor = dev.thor.bridge.CredentialVault(buildMap { secret?.let { put(credentialRef,it) }; searchSecret?.let { put("search@$revision",it) } })
    val policy: NetworkPolicy
    private var model: JSONObject? = null
    private val prepared = mutableMapOf<String, Prepared>()
    private data class Prepared(val e: Envelope, val args: Map<String, Any?>, val fingerprint: String,
        val approvalId: String?, val approved: CompletableFuture<Boolean>, val approval: Approval?, val summary: String)
    private var probeToken: String? = null
    @Volatile private var probeRun: String? = null
    private var probeTools = 0
    private var probeImageReads = 0
    private var publishedTools = emptySet<String>()
    private val probeHeads = java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val probeCompleted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun auditHttp(start: dev.thor.bridge.HttpStart,event: dev.thor.bridge.HttpEvent) {
        if(start.envelope.purpose != Purpose.configuration_test || start.envelope.binding.runId != probeRun)return
        when(event) {
            is dev.thor.bridge.HttpEvent.Head -> probeHeads[event.requestId]=event.status
            is dev.thor.bridge.HttpEvent.End -> if(probeHeads[event.requestId] in 200..299)probeCompleted.add(event.requestId)
            is dev.thor.bridge.HttpEvent.Error -> probeCompleted.remove(event.requestId)
            else -> Unit
        }
    }
    private val capNames = mapOf("perception" to dev.thor.bridge.Capability.screen_perception, "search" to dev.thor.bridge.Capability.web_search, "screen_control" to dev.thor.bridge.Capability.screen_control, "debug" to dev.thor.bridge.Capability.adb_debug)
    init {
        for (key in listOf("sessions", "messages", "runs", "events", "attachments")) if (!state.has(key)) state.put(key, JSONArray())
        for (r in rows("runs")) if (r.optString("status") !in terminal) r.put("status", "interrupted").put("stopReason", "进程恢复：不重放任何副作用")
        for (s in rows("sessions")) authority.restoreSession(s.getString("id"), enabled(s), s.optLong("capabilityEpoch", 1).coerceAtLeast(1))
        if (rows("sessions").isEmpty()) newSession()
        if (rows("sessions").none { it.getString("id") == state.optString("selected") && it.isNull("archivedAt") }) state.put("selected", rows("sessions").first().getString("id"))
        persist()
        val profiles = mutableListOf<NetworkProfile>()
        try {
            val base = prefs.getString("url", "")!!.trimEnd('/')
            val u = URI(base)
            val localDebug = BuildConfig.DEBUG && u.scheme == "http" && u.host == "127.0.0.1"
            require((u.scheme == "https" || localDebug) && u.userInfo == null && u.rawQuery == null && u.fragment == null && !u.host.isNullOrBlank())
            require(!prefs.getString("model", "").isNullOrBlank() && secret != null)
            val protocol = if (prefs.getInt("protocol", 0) == 0) "openai-responses" else "anthropic-messages"
            val path = if (protocol == "openai-responses") "${u.path.trimEnd('/')}/responses" else "${u.path.trimEnd('/').removeSuffix("/v1")}/v1/messages"
            val defaultName = if(protocol == "openai-responses") "Authorization" else "x-api-key"
            val defaultTemplate = if(protocol == "openai-responses") "Bearer {{credential}}" else "{{credential}}"
            val headerName = prefs.getString("headerName", "").orEmpty().ifBlank { defaultName }
            val headerTemplate = prefs.getString("headerTemplate", "").orEmpty().ifBlank { defaultTemplate }
            require(headerName.matches(Regex("[A-Za-z0-9-]+")) && headerTemplate.split("{{credential}}").size == 2 && !headerTemplate.contains('\r') && !headerTemplate.contains('\n'))
            val header = mapOf(headerName to headerTemplate)
            profiles.add(NetworkProfile(ref, "${u.scheme}://${u.rawAuthority}", setOf(path), setOf("POST"), setOf(Purpose.model, Purpose.configuration_test), credentialRef, header, explicitLocal = localDebug))
            val passed = prefs.getInt("probeRevision", -1) == revision && prefs.getString("probe", "") == "passed"
            model = obj("profileId" to ref, "revision" to revision, "label" to prefs.getString("name", "模型"), "protocol" to protocol,
                "baseUrl" to base, "modelId" to prefs.getString("model", ""), "credentialRef" to credentialRef, "contextWindow" to prefs.getInt("contextBudget",32768), "maxOutput" to prefs.getInt("maxOutput",2048),
                "lastProbe" to obj("revision" to revision, "textStream" to passed, "imageInput" to passed, "toolCalling" to passed, "imageToolRoundTrip" to passed))
        } catch (_: Exception) { model = null }
        profiles.add(NetworkProfile("public-web", null, emptySet(), setOf("GET"), setOf(Purpose.web_fetch)))
        try {
            require(prefs.getString("searchBackend", "")?.lowercase() == "brave" && searchSecret != null)
            val raw = prefs.getString("searchUrl", "")!!.trimEnd('/'); val u = URI(raw)
            val localDebug = BuildConfig.DEBUG && u.scheme == "http" && u.host == "127.0.0.1"
            require((u.scheme == "https" || localDebug) && u.userInfo == null && u.query == null && u.fragment == null)
            require(u.path.endsWith("/res/v1/web/search"))
            profiles.add(NetworkProfile("search@$revision", "${u.scheme}://${u.rawAuthority}", setOf(u.rawPath), setOf("GET"), setOf(Purpose.web_search), "search@$revision", mapOf("X-Subscription-Token" to "{{credential}}"), explicitLocal = localDebug))
            searchConfigured = true; searchUrl = raw
        } catch(_: Exception) { searchConfigured = false }
        policy = NetworkPolicy(profiles, redactor)
        ThorAccessibilityService.invalidated = { revokeResources() }
        displayManager.registerDisplayListener(displayListener,android.os.Handler(android.os.Looper.getMainLooper()))
    }
    private fun backupKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val alias = "thor-product-backups-v1"
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun rows(key: String) = state.getJSONArray(key).objects()
    private fun session(sid: String) = rows("sessions").single { it.getString("id") == sid }
    private fun run(rid: String) = rows("runs").single { it.getString("id") == rid }
    private fun enabled(s: JSONObject) = capNames.filter { s.getJSONObject("capabilities").optBoolean(it.key) }.values.toSet()
    private fun persist() { try { store.save(state) } catch (e: Exception) { authority.stopRun(); deviceAuthority.stop(); prepared.values.forEach { it.approved.complete(false) }; storageFailed=true;binding=null;prepared.clear();state=store.load();for(r in rows("runs"))if(r.optString("status") !in terminal)r.put("status","interrupted").put("stopReason","本地存储失败；执行已停止，重启不重放");throw PersistenceFailure(e) } }
    private fun newSession(): String {
        val sid = id(); authority.createSession(sid)
        state.getJSONArray("sessions").put(obj("id" to sid, "title" to "新会话", "createdAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis(), "archivedAt" to null,
            "capabilities" to obj("perception" to true, "search" to true, "screen_control" to false, "debug" to false), "capabilityEpoch" to 1))
        state.put("selected", sid); return sid
    }
    fun bootstrap(c: String): JSONObject { connection = c; return obj("connectionId" to c) }
    private fun bound(c: String, j: JSONObject): Binding {
        require(c == connection); val b = binding ?: error("No active run")
        require(j.getString("sessionId") == b.sessionId && j.getString("runId") == b.runId)
        if (j.has("leaseId")) require(j.getString("leaseId") == b.lease)
        if (j.has("capabilityEpoch")) require(j.getLong("capabilityEpoch") == b.capabilityEpoch)
        authority.validate(b); return b
    }
    fun rpc(c: String, request: JSONObject): CompletableFuture<JSONObject> {
        val future = CompletableFuture<JSONObject>()
        if(closing) {future.completeExceptionally(IllegalStateException("Closed host"));return future}
        serial.execute {
            try {
                require(c == connection && request.toString().length <= 3 * 1024 * 1024)
                val op = request.getString("op"); require(!storageFailed || op == "view"); val data = request.optJSONObject("data") ?: JSONObject()
                if (op == "tool.execute") { execute(c, data, future); return@execute }
                val value = when (op) {
                    "view" -> view()
                    "ui" -> ui(c, data)
                    "begin" -> begin(c, data)
                    "authority" -> { bound(c, data); authoritySnapshot(data.optBoolean("refresh")) }
                    "tool.prepare" -> prepare(c, data)
                    "network.tool.begin" -> networkTool(c, data)
                    "tool.cancel" -> { bound(c, data); prepared[data.getString("requestId")]?.approved?.complete(false); authority.stopRun(); obj() }
                    "persist" -> save(c, data)
                    "attachment.get" -> { val b = bound(c, data); val a = rows("attachments").singleOrNull { it.getString("id") == data.getString("id") && it.getString("sessionId") == b.sessionId }; if (b.runId == probeRun && a != null) probeImageReads++; if(a != null) {
                        require(a.getLong("expiresAt") > System.currentTimeMillis())
                        require(a.getJSONArray("source").let { src -> (0 until src.length()).all { dev.thor.bridge.Capability.valueOf(src.getString(it)) in authority.enabled(b) } })
                    }; a ?: obj() }
                    "attachment.put" -> { error("Only Native image capture/picker may create attachments") }
                    "end" -> end(c, data)
                    else -> error("Unsupported RPC")
                }
                future.complete(value)
            } catch (e: Exception) {
                lastError = if(request.optString("op") == "begin") "发送失败：请先配置模型并完成图片/工具探测；无模拟回答。" else "操作被拒绝：检查授权、活动运行或本地存储。"
                future.complete(obj("error" to if(e is dev.thor.tools.ToolFailure) e.code.name else "IO_ERROR")); publish()
            }
        }
        return future
    }
    private fun begin(c: String, d: JSONObject): JSONObject {
        require(binding == null && prefs.getInt("revision",-1) == revision); lastError = null; val sid = d.getString("sessionId"); val s = session(sid); require(s.isNull("archivedAt"))
        val profile = model ?: error("Configure model first")
        val probe = d.optString("probeToken").isNotEmpty() && d.optString("probeToken") == probeToken
        require(probe || prefs.getString("probe", "") == "passed" && prefs.getInt("probeRevision", -1) == revision)
        if (probe) { probeToken = null; probeTools = 0; probeImageReads = 0;probeHeads.clear();probeCompleted.clear() }
        val pendingImages = s.optJSONArray("pendingAttachments") ?: JSONArray()
        val text = d.getString("text"); require(text.isNotBlank() && text.length <= 8000)
        val b = authority.beginRun(sid, c, buildMap { put(if(probe) Purpose.configuration_test else Purpose.model,setOf(ref)); if(!probe) { put(Purpose.web_fetch,setOf("public-web")); if(searchConfigured)put(Purpose.web_search,setOf("search@$revision")) } })
        binding = b; networkCalls.clear(); deviceAuthority.bind(b); probeRun = if(probe) b.runId else null; snapshot = null
        val r = obj("id" to b.runId, "sessionId" to sid, "status" to "queued", "expanded" to true, "startedAt" to System.currentTimeMillis(), "probe" to probe,
            "modelProfileRevision" to revision, "capabilityEpoch" to b.capabilityEpoch, "contexts" to JSONArray())
        state.getJSONArray("runs").put(r)
        state.getJSONArray("messages").put(obj("id" to id(), "sessionId" to sid, "runId" to b.runId, "role" to "user", "text" to text, "attachments" to pendingImages, "status" to "complete"))
        state.getJSONArray("messages").put(obj("id" to id(), "sessionId" to sid, "runId" to b.runId, "role" to "assistant", "text" to "", "attachments" to JSONArray(), "status" to "streaming"))
        s.put("pendingAttachments",JSONArray()); persist(); publish()
        val input=JSONArray().put(obj("type" to "text","text" to text))
        if(probe)input.put(obj("type" to "attachment","attachmentId" to addProbeImage(b))) else for(i in 0 until pendingImages.length())input.put(obj("type" to "attachment","attachmentId" to pendingImages.getString(i)))
        r.put("turn",obj("turnId" to id(),"runId" to b.runId,"messages" to JSONArray().put(obj("kind" to "user","content" to input)),"source" to JSONArray(),"observedAt" to System.currentTimeMillis(),"complete" to false));persist()
        val ttl = authority.remainingLeaseMs(b)
        return obj("lease" to obj("sessionId" to sid, "runId" to b.runId, "leaseId" to b.lease, "capabilityEpoch" to b.capabilityEpoch, "expiresAt" to System.currentTimeMillis() + ttl),
            "network" to obj("binding" to bindingJson(b), "purpose" to if(probe) "configuration_test" else "model", "profileRef" to ref, "credentialRef" to credentialRef, "deadlineMs" to clock.now() + ttl, "timeoutMs" to ttl),
            "history" to JSONArray(rows("runs").filter { it.getString("sessionId") == sid && it.getString("id") != b.runId && it.has("turn") && !it.optBoolean("probe") }.map { it.getJSONObject("turn") }),
            "input" to input)
    }
    private fun bindingJson(b: Binding) = obj("sessionId" to b.sessionId, "runId" to b.runId, "lease" to b.lease, "connectionId" to b.connectionId, "capabilityEpoch" to b.capabilityEpoch)
    private fun authoritySnapshot(refresh: Boolean): JSONObject {
        val b = binding ?: error("No active run"); authority.validate(b);require(prefs.getInt("revision",-1)==revision)
        snapshots.update(displayFacts(context))
        if (refresh || snapshot == null) {
            // Preserve a valid frame/snapshot identity across model requests; changed/expired resources get a new identity.
            snapshot = snapshot?.let { old -> runCatching { snapshots.validate(old.id) }.getOrNull() } ?: runCatching { snapshots.capture() }.getOrNull()
        }
        val available = JSONObject(); val allowed = mutableSetOf<String>()
        for (t in ToolPack.descriptors.values) {
            var a = backend.availability(t.toolId)
            if(b.runId == probeRun) a = a.copy(status = Status.BACKEND_MISSING, reason = "配置探测不发布设备工具")
            if (t.targetRequired && (snapshot == null || t.effect == Effect.INPUT && snapshot?.target?.validity != "valid")) a = a.copy(status = Status.TEMPORARILY_UNAVAILABLE, reason = "先校准上下屏")
            available.put(t.toolId, obj("status" to a.status.name.lowercase(), "reason" to a.reason))
            if (a.status == Status.READY && dev.thor.bridge.Capability.valueOf(t.capability.name.lowercase()) in authority.enabled(b)) allowed.add(t.toolId)
        }
        deviceAuthority.publish(b, allowed)
        available.put("web.search", obj("status" to if(searchConfigured && b.runId != probeRun) "ready" else "backend_missing", "reason" to "Brave 搜索需独立配置/key"))
        available.put("web.fetch", obj("status" to if(b.runId != probeRun) "ready" else "backend_missing", "reason" to "只读取公开 HTTPS；Native 拒绝私网"))
        val enabled = JSONObject(); for(cap in dev.thor.bridge.Capability.entries) enabled.put(cap.name, cap in authority.enabled(b))
        val profile = JSONObject(model.toString())
        val probed = prefs.getString("probe", "") == "passed" && prefs.getInt("probeRevision", -1) == revision
        profile.put("lastProbe", obj("revision" to revision, "textStream" to probed, "imageInput" to probed, "toolCalling" to probed, "imageToolRoundTrip" to probed))
        if (b.runId == probeRun) {
            profile.put("lastProbe", obj("revision" to revision, "textStream" to true, "imageInput" to true, "toolCalling" to true, "imageToolRoundTrip" to true))
            available.put("probe.picture", obj("status" to "ready"))
        }
        publishedTools = allowed.toSet() + if(b.runId == probeRun) setOf("probe.picture") else if(dev.thor.bridge.Capability.web_search in authority.enabled(b)) setOf("web.fetch") + if(searchConfigured)setOf("web.search") else emptySet() else emptySet()
        val l0 = obj("snapshotId" to (snapshot?.id ?: "no-target:${b.runId}"))
        if (dev.thor.bridge.Capability.screen_perception in authority.enabled(b)) {
            l0.put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            snapshot?.target?.packageName?.let { l0.put("upperApp", obj("packageName" to it, "label" to it)); l0.put("displaySnapshotId", snapshot!!.id) }
        }
        return obj("session" to obj("sessionId" to b.sessionId, "title" to session(b.sessionId).getString("title"), "model" to profile, "enabled" to enabled, "capabilityEpoch" to b.capabilityEpoch),
            "lease" to obj("sessionId" to b.sessionId, "runId" to b.runId, "leaseId" to b.lease, "capabilityEpoch" to b.capabilityEpoch, "expiresAt" to System.currentTimeMillis() + authority.remainingLeaseMs(b)), "availability" to available, "l0" to l0)
    }
    private fun toolEnvelope(c: String, j: JSONObject): Envelope {
        val b = bound(c, j); require(j.getString("bridgeVersion") == "0.1")
        val remaining = (j.getLong("deadline") - System.currentTimeMillis()).coerceAtMost(authority.remainingLeaseMs(b)).coerceAtMost(60000)
        require(remaining > 0)
        return Envelope(b.sessionId, b.runId, j.getString("toolCallId"), j.getString("toolId"), b.capabilityEpoch, clock.now() + remaining,
            if(snapshot != null) j.getString("targetSnapshotId") else null, j.getString("schemaVersion").toInt(), j.getString("requestId"))
    }
    private fun prepare(c: String, j: JSONObject): JSONObject {
        val e = toolEnvelope(c, j); val args = j.getJSONObject("arguments").args()
        if(e.toolId in contract) {
            require(j.getJSONArray("resources").toString() == contract.getValue(e.toolId).getJSONArray("resources").toString())
        }
        require(rows("events").any { it.getString("runId") == e.runId && it.optString("toolCallId") == e.toolCallId && it.getString("type") == "tool_intent" })
        require(e.requestId !in prepared && prepared.size < 32)
        if(e.toolId == "probe.picture") { require(e.runId == probeRun && args.isEmpty()); return obj() }
        if(e.toolId in setOf("web.search","web.fetch")) {
            val b = bound(c,j); require(e.runId != probeRun && dev.thor.bridge.Capability.web_search in authority.enabled(b))
            require(args.keys == setOf(if(e.toolId == "web.search") "query" else "url"))
            val value = args.values.single() as? String ?: error("String required")
            require(value.length in 1..if(e.toolId == "web.search") 400 else 8192)
            if(e.toolId == "web.search")require(searchConfigured)
            require(e.requestId !in networkCalls && e.toolCallId !in networkCalls)
            require(networkCalls.size < 64); networkCalls.add(e.requestId);networkCalls.add(e.toolCallId)
            prepared[e.requestId] = Prepared(e,args,hash(j.getJSONObject("arguments").toString().toByteArray()),null,CompletableFuture.completedFuture(true),null,"")
            return obj()
        }
        deviceAuthority.validate(e); schemas.validate(e.toolId, args)
        require(redactor.redact(j.getJSONObject("arguments").toString()) == j.getJSONObject("arguments").toString())
        val needsApproval = ToolPack.descriptors.getValue(e.toolId).effect in setOf(Effect.WRITE, Effect.ARBITRARY_SHELL)
        var approval: Approval? = null; var summary = ""
        if (e.toolId == "debug.shell") summary = "运行设备 Shell（可跨越其他能力边界）\n${args.getValue("command")}"
        if (e.toolId in setOf("files.write", "files.restore")) {
            val path: String; val desired: ByteArray; val expected: String
            if(e.toolId == "files.write") { path = local.path(args.getValue("path") as String).toString(); desired = (args.getValue("content") as String).toByteArray(); expected = args.getValue("expectedHash") as String }
            else { val backup = backups.load(args.getValue("backupId") as String); path = backup.path; desired = backup.bytes; expected = args.getValue("expectedCurrentHash") as String }
            val before = local.read(path); requireTool(hash(before) == expected, Code.HASH_CONFLICT, "审批前文件已变化")
            requireTool(before.size + desired.size <= 65536, Code.LIMIT_EXCEEDED, "完整差异超出审阅预算")
            approval = Approval(path, expected, hash(desired))
            summary = "合成配置目录：$path\n旧 SHA-256：$expected\n新 SHA-256：${hash(desired)}\n--- 原内容 ---\n${before.toString(Charsets.UTF_8)}\n--- 新内容 ---\n${desired.toString(Charsets.UTF_8)}"
        }
        val p = Prepared(e, args, hash(j.getJSONObject("arguments").toString().toByteArray()), if(needsApproval) id() else null, CompletableFuture(), approval, summary)
        prepared[e.requestId] = p
        if(!needsApproval) p.approved.complete(true)
        if(needsApproval) run(e.runId).put("approval", obj("id" to p.approvalId, "requestId" to e.requestId, "summary" to redactor.redact(summary), "status" to "pending"))
        persist(); publish(); return obj("awaiting" to needsApproval, "approvalId" to p.approvalId)
    }
    private fun networkTool(c: String,j: JSONObject): JSONObject {
        val b = bound(c,j); val e = toolEnvelope(c,j); require(e.toolId in setOf("web.search","web.fetch") && dev.thor.bridge.Capability.web_search in authority.enabled(b))
        val p = prepared.remove(e.requestId) ?: error("Unprepared network tool")
        require(p.e.toolId == e.toolId && p.e.toolCallId == e.toolCallId && p.fingerprint == hash(j.getJSONObject("arguments").toString().toByteArray()))
        val search = e.toolId == "web.search"
        return obj("url" to if(search) searchUrl else p.args.getValue("url"), "context" to obj("binding" to bindingJson(b), "purpose" to if(search) "web_search" else "web_fetch",
            "profileRef" to if(search) "search@$revision" else "public-web", "credentialRef" to if(search) "search@$revision" else null,
            "deadlineMs" to p.e.deadline, "timeoutMs" to (p.e.deadline-clock.now()).coerceAtLeast(1)))
    }
    private fun execute(c: String, j: JSONObject, future: CompletableFuture<JSONObject>) {
        val e = toolEnvelope(c, j)
        if (e.toolId == "probe.picture") {
            require(e.runId == probeRun && j.getJSONObject("arguments").length() == 0); probeTools++
            future.complete(obj("content" to JSONArray().put(obj("type" to "attachment", "attachmentId" to addProbeImage(binding!!))), "observedAt" to System.currentTimeMillis(), "source" to "native-probe", "truncated" to false, "outcome" to "observed")); return
        }
        val p = prepared[e.requestId] ?: error("Tool not prepared")
        require(p.e.toolCallId == e.toolCallId && p.e.runId == e.runId && p.e.toolId == e.toolId && p.e.snapshotId == e.snapshotId && p.e.capabilityEpoch == e.capabilityEpoch)
        require(p.fingerprint == hash(j.getJSONObject("arguments").toString().toByteArray()))
        deviceWorker.execute {
            try {
                val permitted = p.approved.get((p.e.deadline - clock.now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                requireTool(permitted, Code.PERMISSION_MISSING, "用户拒绝审批")
                deviceAuthority.validate(p.e)
                backend.approval = p.approval
                backend.shellApproval = if(p.e.toolId == "debug.shell") hash((p.args.getValue("command") as String).toByteArray()) else null
                val result = gateway.execute(p.e, p.args)
                serial.execute {
                    try {
                        bound(c, j)
                        val data = result.data.toMutableMap(); val content = JSONArray()
                        val path = data.remove("attachmentRef") as? String
                        if(path != null && result.error == null) {
                            val file = File(path); require(file.canonicalPath.startsWith(File(context.cacheDir, "tool-frames").canonicalPath + "/"))
                            val bytes = file.readBytes(); require(bytes.size <= 2097152)
                            val aid = id(); val a = obj("id" to aid, "attachmentId" to aid, "sessionId" to e.sessionId, "runId" to e.runId, "mimeType" to "image/jpeg", "data" to Base64.encodeToString(bytes, Base64.NO_WRAP), "observedAt" to System.currentTimeMillis(), "expiresAt" to System.currentTimeMillis()+600000, "source" to JSONArray(listOf("screen_perception")), "size" to bytes.size, "hash" to hash(bytes))
                            state.getJSONArray("attachments").put(a); persist(); content.put(obj("type" to "attachment", "attachmentId" to aid))
                        }
                        content.put(obj("type" to "text", "text" to redactor.redact(JSONObject(json(data + mapOf("message" to result.message)).toString()).toString())))
                        future.complete(obj("content" to content, "observedAt" to (System.currentTimeMillis() - (clock.now()-result.observedAt).coerceAtLeast(0)), "source" to result.source, "truncated" to result.truncated,
                            "evidenceRef" to (data["backupId"]?.let {"backup:$it"} ?: "native-event:${e.runId}:${e.requestId}"), "error" to result.error?.name, "outcome" to if(result.outcome == "not_submitted") "observed" else result.outcome))
                    } catch (_: Exception) { future.complete(obj("error" to "STALE_AUTHORIZATION", "content" to JSONArray(), "observedAt" to System.currentTimeMillis(), "source" to "native", "truncated" to false, "outcome" to "unknown")) }
                }
            } catch(e: Exception) { future.complete(obj("error" to (e as? ToolFailure)?.code?.name.orEmpty().ifEmpty { "CANCELLED" }, "content" to JSONArray(), "observedAt" to System.currentTimeMillis(), "source" to "native", "truncated" to false, "outcome" to "observed")) }
            finally { backend.approval = null; backend.shellApproval = null }
        }
    }
    private fun save(c: String, d: JSONObject): JSONObject {
        val b = binding ?: error("No run"); require(c == connection && d.getString("sessionId") == b.sessionId && d.getString("runId") == b.runId)
        // Terminal/cancellation events can persist after Native has revoked the execution lease.
        val kind = d.getString("kind"); val value = d.getJSONObject("value"); val r = run(b.runId)
        require(redactor.redact(value.toString()) == value.toString())
        when(kind) {
            "event", "commit" -> {
                val event = if(kind == "commit") d.getJSONObject("event") else value
                require(event.getString("sessionId") == b.sessionId && event.getString("runId") == b.runId)
                val sequence = rows("events").count { it.getString("runId") == b.runId } + 1
                require(event.getInt("sequence") == sequence)
                val copy = JSONObject(event.toString()).put("id", event.getString("eventId")).put("summary", event.getJSONObject("data").toString())
                for(key in listOf("toolId","outcome","error")) if(event.getJSONObject("data").has(key))copy.put(key,event.getJSONObject("data").get(key))
                state.getJSONArray("events").put(copy)
                val data = event.getJSONObject("data")
                if(event.getString("type") == "text_delta") rows("messages").single { it.getString("runId") == b.runId && it.getString("role") == "assistant" }.let { m -> val text = m.getString("text") + data.getString("text"); require(text.length <= 65536); m.put("text", text) }
                if(kind == "commit") { require(value.getString("sessionId") == b.sessionId && value.getString("runId") == b.runId); r.put("status", value.getString("state")); r.put("runtime", value); if(value.getString("state") in terminal) r.put("expanded", false) }
            }
            "run" -> { require(value.getString("runId") == b.runId && value.getString("sessionId") == b.sessionId); r.put("runtime", value).put("status", value.getString("state")) }
            "context" -> { require(value.getInt("modelProfileRevision") == revision && value.getLong("capabilityEpoch") == b.capabilityEpoch); require(value.getJSONArray("effectiveTools").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } == publishedTools); r.getJSONArray("contexts").put(value) }
            "turn" -> { require(value.getString("runId") == b.runId); r.put("turn", value) }
            else -> error("Unsupported persistence method")
        }
        persist(); publish(); return obj()
    }
    private fun end(c: String, d: JSONObject): JSONObject {
        val b = binding ?: error("No run"); require(c == connection && d.getString("runId") == b.runId && d.getString("sessionId") == b.sessionId)
        val r = run(b.runId)
        if(r.optString("status") !in terminal) r.put("status", "failed").put("stopReason", "Runtime 未完成；不重放")
        if(b.runId == probeRun) {
            val turn = r.optJSONObject("turn")
            val passed = r.getString("status") == "completed" && probeCompleted.size >= 2 && probeTools > 0 && probeImageReads >= 2 && r.getJSONArray("contexts").length() >= 2 && turn?.optBoolean("complete") == true && rows("events").any { it.getString("runId") == b.runId && it.getString("type") == "text_delta" }
            check(prefs.edit().putString("probe", if(passed) "passed" else "failed").putInt("probeRevision", revision).commit())
            r.put("probePassed", passed)
        }
        authority.stopRun(); deviceAuthority.stop(); backend.release(b.runId); prepared.values.forEach { it.approved.complete(false) }; prepared.clear(); binding = null; probeRun = null
        rows("messages").filter { it.getString("runId") == b.runId }.forEach { it.put("status", "complete") }
        persist(); publish(); return obj()
    }
    private fun ui(c: String, d: JSONObject): JSONObject {
        when(d.getString("op")) {
            "ready" -> Unit
            "new" -> { newSession(); persist() }
            "select" -> { require(session(d.getString("sessionId")).isNull("archivedAt")); state.put("selected", d.getString("sessionId")); persist() }
            "rename" -> { val title = d.getString("title"); require(title.isNotBlank() && title.length <= 80); session(d.getString("sessionId")).put("title", title); persist() }
            "expanded" -> { run(d.getString("runId")).put("expanded", d.getBoolean("expanded")); persist() }
            "archive", "delete" -> {
                val sid = d.getString("sessionId"); require(binding?.sessionId != sid)
                if(d.getString("op") == "archive") session(sid).put("archivedAt", if(d.getBoolean("archived")) System.currentTimeMillis() else JSONObject.NULL)
                else for(key in listOf("sessions", "messages", "runs", "events", "attachments")) state.put(key, JSONArray(rows(key).filter { if(key == "sessions") it.getString("id") != sid else it.getString("sessionId") != sid }))
                if(rows("sessions").none { it.getString("id") == state.optString("selected") && it.isNull("archivedAt") }) newSession()
                persist()
            }
            "capability" -> {
                val sid = d.getString("sessionId"); val cap = capNames.getValue(d.getString("capability")); authority.setEnabled(sid, cap, d.getBoolean("enabled"))
                val s = session(sid); s.getJSONObject("capabilities").put(d.getString("capability"), d.getBoolean("enabled")); s.put("capabilityEpoch", authority.sessionEpoch(sid))
                if(binding?.sessionId == sid) stop("能力已变更；旧 lease 不会复活")
                persist()
            }
            "stop" -> { require(d.getString("runId") == binding?.runId); stop("用户停止") }
            "approve", "reject" -> {
                val b = binding ?: error("No run"); require(d.getString("runId") == b.runId); authority.validate(b)
                val p = prepared.values.single { it.approvalId == d.getString("approvalId") && it.e.runId == b.runId }
                require(!p.approved.isDone && clock.now() < p.e.deadline)
                deviceAuthority.validate(p.e)
                run(b.runId).getJSONObject("approval").put("status", if(d.getString("op") == "approve") "approved" else "rejected")
                persist(); p.approved.complete(d.getString("op") == "approve")
            }
            else -> error("Unsupported UI command")
        }
        publish(); return view()
    }
    fun stop(reason: String) { authority.stopRun(); deviceAuthority.stop(); prepared.values.forEach { it.approved.complete(false) }; binding?.let { backend.release(it.runId); run(it.runId).put("stopReason", reason) } }
    fun interrupt(reason: String) { if(closing)return;runCatching { serial.execute { stop(reason); binding?.let { run(it.runId).put("status", "interrupted"); persist() }; binding = null; connection = null; publish() } } }
    private fun revokeResources() { if(closing)return;runCatching { serial.execute { binding?.let { authority.invalidateResources(it.sessionId); stop("系统授权或后端失效"); persist(); publish() } } } }
    fun calibrate(upper: Int, lower: Int) { snapshots.update(displayFacts(context)); snapshots.calibrate(upper, lower); revokeResources() }
    fun bindDebug() = debug.requestAndBind()
    fun requestProbe(): String { require(binding == null && model != null); val token = id(); probeToken = token; return token }
    fun requestImagePicker(): String { require(binding == null);return selectedSession() }
    fun addUserImage(sid: String,uri: android.net.Uri): CompletableFuture<JSONObject> {
        val future=CompletableFuture<JSONObject>()
        serial.execute {
            try {
                val s=session(sid);require(s.isNull("archivedAt"))
                val pending=s.optJSONArray("pendingAttachments") ?: JSONArray();require(pending.length()<2 && rows("attachments").sumOf {it.optLong("size")} < 100L*1024*1024)
                val bytes=context.contentResolver.openInputStream(uri)!!.use { input -> val bounded=Budgets.read(input,16*1024*1024);require(!bounded.truncated);bounded.bytes }
                val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                require(options.outWidth in 1..16384 && options.outHeight in 1..16384)
                var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>1280)sample*=2
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,android.graphics.BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("Invalid image")
                val output=java.io.ByteArrayOutputStream();try {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,output)} finally {bitmap.recycle()}
                val image=output.toByteArray();require(image.size<=2097152);val aid=id()
                state.getJSONArray("attachments").put(obj("id" to aid,"attachmentId" to aid,"sessionId" to sid,"runId" to null,"mimeType" to "image/jpeg","data" to Base64.encodeToString(image,Base64.NO_WRAP),"size" to image.size,"hash" to hash(image),"observedAt" to System.currentTimeMillis(),"expiresAt" to System.currentTimeMillis()+30L*86400000,"source" to JSONArray()))
                pending.put(aid);s.put("pendingAttachments",pending);persist();publish();future.complete(obj("attachmentId" to aid))
            } catch(e:Exception) {future.completeExceptionally(e)}
        }
        return future
    }
    fun prepareForRunner(text: String): CompletableFuture<JSONObject> {
        check(BuildConfig.DEBUG)
        val future = CompletableFuture<JSONObject>()
        serial.execute { try { future.complete(begin(connection ?: error("No connection"), obj("sessionId" to selectedSession(), "text" to text))) } catch(e: Exception) { future.completeExceptionally(e) } }
        return future
    }
    fun selectedSession(): String = state.getString("selected")
    private fun addProbeImage(b: Binding): String {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.RED)
        val output = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val aid = id(); val bytes = output.toByteArray()
        state.getJSONArray("attachments").put(obj("id" to aid, "attachmentId" to aid, "sessionId" to b.sessionId, "runId" to b.runId, "mimeType" to "image/png", "data" to Base64.encodeToString(bytes, Base64.NO_WRAP), "observedAt" to System.currentTimeMillis(), "expiresAt" to System.currentTimeMillis()+180000, "source" to JSONArray(listOf("screen_perception")), "size" to bytes.size, "hash" to hash(bytes)))
        persist(); return aid
    }
    fun view(): JSONObject {
        val v = JSONObject(state.toString())
        if(storageFailed)v.put("fatal","本地存储失败；已停止网络和设备执行。历史保留，修复存储后重新打开；不会重放副作用。")
        v.put("attachments", JSONArray(rows("attachments").map { JSONObject(it.toString()).apply { remove("data"); remove("path") } }))
        v.put("runs", JSONArray(rows("runs").map { JSONObject(it.toString()).apply { remove("turn"); remove("contexts"); remove("runtime") } }))
        v.put("availability", obj("perception" to if(ThorAccessibilityService.instance == null) "无障碍未授权；设备信息可用" else "需校准显示；按工具检查",
            "search" to if(searchConfigured) "Brave 搜索已配置；公开网页读取可用" else "搜索未配置；公开 HTTPS 网页读取可用", "screen_control" to "触摸需校准/无障碍；手柄 unsupported", "debug" to "Shell: ${debug.availability().reason}；逐项审批；文件仅合成目录"))
        v.put("activeRunId", binding?.runId ?: JSONObject.NULL)
        v.put("error", lastError ?: JSONObject.NULL)
        v.put("modelStatus", if(model == null) "模型未配置" else if(prefs.getString("probe", "") == "passed" && prefs.getInt("probeRevision", -1) == revision) "配置探测已通过" else "配置未测试/失败，请在 Native 设置运行图片与工具探测")
        return v
    }
    private fun publish() = notify(view())
    override fun close() {
        if(closing)return;closing=true
        displayManager.unregisterDisplayListener(displayListener)
        authority.stopRun(); prepared.values.forEach { it.approved.complete(false) }
        ThorAccessibilityService.invalidated = null
        serial.execute { stop("宿主关闭"); binding?.let { run(it.runId).put("status", "interrupted"); persist() }; binding = null; deviceAuthority.close(); store.close() }
        debug.close(); deviceWorker.shutdownNow(); serial.shutdown()
    }
}
