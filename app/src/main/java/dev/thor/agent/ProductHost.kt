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
class ProductHost private constructor(private val context: Context, private val notify: (JSONObject) -> Unit) : AutoCloseable {
    companion object {
        private val storageOwner=java.util.concurrent.Semaphore(1)
        fun create(context: Context,notify:(JSONObject)->Unit):ProductHost {
            check(storageOwner.tryAcquire(1,TimeUnit.SECONDS)) {"Previous storage owner is still closing"}
            return try {ProductHost(context,notify)}catch(t:Throwable){storageOwner.release();throw t}
        }
    }
    val authority = Authority(dev.thor.bridge.MonotonicClock { SystemClock.elapsedRealtime() })
    private val serial = Executors.newSingleThreadExecutor()
    private val deviceWorker = Executors.newSingleThreadExecutor()
    private val networkWorker = Executors.newFixedThreadPool(4)
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private val store = SqliteStore(context, "product.db")
    private var state = store.load()
    private val bindings=linkedMapOf<String,Binding>()
    private class RunScope(val profile:JSONObject) {
        var snapshot:Snapshot?=null
        var pendingPerceptionSnapshot:Snapshot?=null
        var publishedTools=emptySet<String>()
        val networkCalls=mutableSetOf<String>()
    }
    private val scopes=mutableMapOf<String,RunScope>()
    private fun scope(b:Binding)=scopes.getValue(b.runId)
    private fun bindingFor(j:JSONObject)=bindings[j.getString("runId")] ?: error("No run")
    fun hasActiveRuns()=bindings.isNotEmpty()
    private var lastError: String? = null
    private var storageFailed = false
    @Volatile private var closing = false
    private var connection: String? = null
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
    private val catalog=ModelCatalog(context)
    private val revision = prefs.getInt("revision", 0)
    private val ref = "model@$revision"
    private val credentialRef = "model@$revision"
    private var searchUrl: String? = null
    private var searchConfigured = false
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
    private val skills=SkillStore(File(context.filesDir,"skills")).apply {
        for(name in listOf("skill-creator","skill-manager","adb-operations")) {
            val files=linkedMapOf<String,ByteArray>()
            fun load(path: String) {
                val children=context.assets.list("skills/$name/$path").orEmpty()
                if(children.isEmpty())files[path]=context.assets.open("skills/$name/$path").use {it.readBytes()}
                else children.forEach {load(if(path.isEmpty())it else "$path/$it")}
            }
            load("");installBuiltin(files)
        }
    }
    private var skillDetail: JSONObject?=null
    private val skillToolIds=setOf("skills.list","skills.read","skills.save","skills.enable","skills.delete")
    private val probeHeads = java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val probeBodies=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val probeConsumerClosed=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val probeCompleted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun auditHttp(start: dev.thor.bridge.HttpStart,event: dev.thor.bridge.HttpEvent) {
        if(start.envelope.purpose != Purpose.configuration_test || start.envelope.binding.runId != probeRun)return
        when(event) {
            is dev.thor.bridge.HttpEvent.Head -> probeHeads[event.requestId]=event.status
            is dev.thor.bridge.HttpEvent.End -> if(probeHeads[event.requestId] in 200..299)probeCompleted.add(event.requestId)
            is dev.thor.bridge.HttpEvent.Chunk -> if(event.bytes.isNotEmpty())probeBodies.add(event.requestId)
            is dev.thor.bridge.HttpEvent.Error -> {
                probeCompleted.remove(event.requestId)
                // SDKs may close a reader after their terminal SSE marker, before
                // HTTP EOF. Only a completed Run with real 2xx bodies and the
                // independently observed tool/image round trip can use this receipt.
                if(event.code==dev.thor.bridge.Code.CANCELLED && probeHeads[event.requestId] in 200..299 && event.requestId in probeBodies)probeConsumerClosed.add(event.requestId)
                else probeConsumerClosed.remove(event.requestId)
            }
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
            require(u.scheme in setOf("http", "https") && u.userInfo == null && u.rawQuery == null && u.fragment == null && !u.host.isNullOrBlank())
            require(!prefs.getString("model", "").isNullOrBlank() && secret != null)
            val protocol = ModelCatalog.protocols[prefs.getInt("protocol",0).coerceIn(0,2)]
            for(api in ModelCatalog.protocols) {
                val path=when(api) {"openai-responses"->"${u.path.trimEnd('/')}/responses";"openai-completions"->"${u.path.trimEnd('/')}/chat/completions";else->"${u.path.trimEnd('/').removeSuffix("/v1")}/v1/messages"}
                val defaultName=if(api=="anthropic-messages")"x-api-key" else "Authorization"
                val defaultTemplate=if(api=="anthropic-messages")"{{credential}}" else "Bearer {{credential}}"
                val headerName=prefs.getString("headerName","").orEmpty().ifBlank {defaultName}
                val headerTemplate=prefs.getString("headerTemplate","").orEmpty().ifBlank {defaultTemplate}
                require(headerName.matches(Regex("[A-Za-z0-9-]+")) && headerTemplate.split("{{credential}}").size==2 && !headerTemplate.contains('\r') && !headerTemplate.contains('\n'))
                val goSessionHeader=if(u.scheme=="https" && u.host=="opencode.ai" && u.port in setOf(-1,443) && u.path.trimEnd('/')=="/zen/go/v1")"x-opencode-session" else null
                profiles.add(NetworkProfile("$ref:$api","${u.scheme}://${u.rawAuthority}",setOf(path),setOf("POST"),setOf(Purpose.model,Purpose.configuration_test),credentialRef,mapOf(headerName to headerTemplate),nativeSessionHeader=goSessionHeader))
            }
            val passed = prefs.getInt("probeRevision", -1) == revision && prefs.getString("probe", "") == "passed"
            model = obj("profileId" to "$ref:$protocol", "revision" to revision, "label" to prefs.getString("name", "模型"), "protocol" to protocol,
                "baseUrl" to base, "modelId" to prefs.getString("model", ""), "credentialRef" to credentialRef, "contextWindow" to prefs.getInt("contextBudget",32768), "maxOutput" to prefs.getInt("maxOutput",2048),
                "lastProbe" to obj("revision" to revision, "textStream" to passed, "imageInput" to passed, "toolCalling" to passed, "imageToolRoundTrip" to passed))
        } catch (_: Exception) { model = null }
        profiles.add(NetworkProfile("public-web", null, emptySet(), setOf("GET"), setOf(Purpose.web_fetch)))
        try {
            require(prefs.getString("searchBackend", "")?.lowercase() == "brave" && searchSecret != null)
            val raw = prefs.getString("searchUrl", "")!!.trimEnd('/'); val u = URI(raw)
            require(u.scheme in setOf("http", "https") && u.userInfo == null && u.query == null && u.fragment == null)
            require(u.path.endsWith("/res/v1/web/search"))
            profiles.add(NetworkProfile("search@$revision", "${u.scheme}://${u.rawAuthority}", setOf(u.rawPath), setOf("GET"), setOf(Purpose.web_search), "search@$revision", mapOf("X-Subscription-Token" to "{{credential}}")))
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
    private fun isGo()=prefs.getString("provider",null)?.let {it=="opencode-go"} ?: prefs.getString("url","").orEmpty().startsWith("https://opencode.ai/zen/go")
    private fun modelChoices():JSONArray=if(isGo())catalog.models() else JSONArray().put(obj(
        "id" to prefs.getString("model", ""),"name" to prefs.getString("name","自定义模型"),
        "protocol" to ModelCatalog.protocols[prefs.getInt("protocol",0).coerceIn(0,2)],"contextWindow" to prefs.getInt("contextBudget",32768),
        "maxOutput" to prefs.getInt("maxOutput",2048),"input" to JSONArray(if(prefs.getBoolean("supportsImages",true))listOf("text","image") else listOf("text")),"efforts" to JSONArray(listOf("off","minimal","low","medium","high"))))
    private fun selectedProfile(s:JSONObject,required:Boolean=true):JSONObject {
        if(required)require(model!=null) {"Configure model first"}
        val chosen=modelChoices().objects().let {choices->choices.find {it.optString("id")==s.optString("modelId",prefs.getString("model",""))} ?: choices.first()}
        val profile=JSONObject(model?.toString() ?: "{}")
        val protocol=chosen.getString("protocol");val efforts=chosen.getJSONArray("efforts")
        val options=(0 until efforts.length()).map {efforts.getString(it)}
        val effort=s.optString("thinkingEffort").takeIf {it in options} ?: if(isGo() && "high" in options)"high" else options.first()
        profile.put("provider",if(isGo())"opencode-go" else "custom").put("profileId","$ref:$protocol").put("revision",revision).put("modelId",chosen.getString("id")).put("label",chosen.getString("name"))
            .put("protocol",protocol).put("contextWindow",chosen.getInt("contextWindow")).put("maxOutput",minOf(prefs.getInt("maxOutput",2048),chosen.getInt("maxOutput")))
            .put("input",chosen.optJSONArray("input") ?: JSONArray(listOf("text","image"))).put("thinkingEffort",effort).put("efforts",efforts).put("reasoning",chosen.optBoolean("reasoning",effort!="off"))
            .put("thinkingLevelMap",chosen.optJSONObject("thinkingLevelMap")).put("compat",chosen.optJSONObject("compat"))
        return profile
    }
    private fun supportsImages(p:JSONObject)=p.getJSONArray("input").let {a->(0 until a.length()).any {a.getString(it)=="image"}}
    private fun probeKey(p:JSONObject)="probe:$revision:${p.getString("modelId")}:${p.getString("protocol")}:images=${supportsImages(p)}"
    private fun profilePassed(p:JSONObject)=prefs.getBoolean(probeKey(p),false) ||
        p.getString("modelId")==prefs.getString("model","") && p.getString("protocol")==ModelCatalog.protocols[prefs.getInt("protocol",0).coerceIn(0,2)] &&
        prefs.getString("probe","")=="passed" && prefs.getInt("probeRevision",-1)==revision && (!supportsImages(p) || prefs.getBoolean("probeImages",true))
    private fun skillCatalog(s:JSONObject,enabledOnly:Boolean):JSONArray {
        val names=s.optJSONArray("selectedSkills")?.let {a->(0 until a.length()).map {a.getString(it)}.toSet()}
        return JSONArray(skills.catalog(enabledOnly).objects().filter {it.optBoolean("builtin") || names==null || it.getString("name") in names})
    }
    private fun leaseWallDeadline(b:Binding):Long {
        val remaining=authority.remainingLeaseMs(b)
        return if(remaining>9_007_199_254_740_991L-System.currentTimeMillis())9_007_199_254_740_991L else System.currentTimeMillis()+remaining
    }
    private fun persist(currentRun: JSONObject?=null, appendedEvent: JSONObject?=null) { try {
        for(s in rows("sessions")) {s.put("capabilityEpoch",authority.sessionEpoch(s.getString("id")));val granted=authority.sessionEnabled(s.getString("id"));for((name,cap) in capNames)s.getJSONObject("capabilities").put(name,cap in granted)}
        if(currentRun==null)store.save(state) else store.saveRunUpdate(state,currentRun,appendedEvent) } catch (e: Exception) { authority.stopRun(); deviceAuthority.stop(); prepared.values.forEach { it.approved.complete(false) }; storageFailed=true;bindings.clear();scopes.clear();prepared.clear();state=store.load();for(r in rows("runs"))if(r.optString("status") !in terminal)r.put("status","interrupted").put("stopReason","本地存储失败；执行已停止，重启不重放");throw PersistenceFailure(e) } }
    private fun newSession(): String {
        val previous=rows("sessions").singleOrNull {it.getString("id")==state.optString("selected")}
        val caps=previous?.getJSONObject("capabilities")?.let {JSONObject(it.toString())} ?: obj("perception" to true,"search" to true,"screen_control" to false,"debug" to false)
        val sid=id();authority.restoreSession(sid,capNames.filter {caps.optBoolean(it.key)}.values.toSet(),1)
        val next=obj("id" to sid,"title" to "新会话","createdAt" to System.currentTimeMillis(),"updatedAt" to System.currentTimeMillis(),"archivedAt" to null,"capabilities" to caps,"capabilityEpoch" to 1)
        for(key in listOf("modelId","thinkingEffort","selectedSkills"))previous?.opt(key)?.let {next.put(key,jsonClone(it))}
        state.getJSONArray("sessions").put(next);state.put("selected",sid);return sid
    }
    private fun jsonClone(value:Any):Any=when(value){is JSONObject->JSONObject(value.toString());is JSONArray->JSONArray(value.toString());else->value}
    fun bootstrap(c: String): JSONObject { connection = c; return obj("connectionId" to c) }
    private fun bound(c: String, j: JSONObject): Binding {
        requireTool(c == connection,Code.STALE_AUTHORIZATION,"Connection mismatch"); val b = bindings[j.getString("runId")] ?: fail(Code.STALE_AUTHORIZATION,"No active run")
        requireTool(j.getString("sessionId") == b.sessionId && j.getString("runId") == b.runId,Code.STALE_AUTHORIZATION,"Session/Run mismatch")
        if (j.has("leaseId")) requireTool(j.getString("leaseId") == b.lease,Code.STALE_AUTHORIZATION,"Lease mismatch")
        if (j.has("capabilityEpoch")) requireTool(j.getLong("capabilityEpoch") == b.capabilityEpoch,Code.STALE_AUTHORIZATION,"Epoch mismatch")
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
                    "profile.check" -> {
                        val sid=data.getString("sessionId");val p=selectedProfile(session(sid))
                        if(profilePassed(p))obj("probeNeeded" to false) else obj("probeNeeded" to true,"token" to requestProbe())
                    }
                    "ui" -> ui(c, data)
                    "begin" -> begin(c, data)
                    "authority" -> { val b=bound(c,data); authoritySnapshot(b,data.optBoolean("refresh")) }
                    "tool.prepare" -> prepare(c, data)
                    "network.tool.begin" -> networkTool(c, data)
                    "tool.cancel" -> {
                        val b=bound(c,data);val request=data.getString("requestId");val p=prepared[request]
                        if(p!=null) {require(p.e.runId==b.runId && p.e.toolCallId==data.getString("toolCallId"));p.approved.complete(false)}
                        deviceAuthority.cancelTool(b.runId,request)
                        if(p?.approvalId!=null && run(b.runId).optJSONObject("approval")?.optString("id")==p.approvalId) {run(b.runId).getJSONObject("approval").put("status","cancelled");persist();publish()}
                        obj()
                    }
                    "persist" -> save(c, data)
                    "attachment.get" -> {
                        val b = bound(c, data); val a = rows("attachments").singleOrNull { it.getString("id") == data.getString("id") && it.getString("sessionId") == b.sessionId }
                        val available=a != null && a.getLong("expiresAt")>System.currentTimeMillis() && a.getJSONArray("source").let {src ->(0 until src.length()).all {dev.thor.bridge.Capability.valueOf(src.getString(it)) in authority.enabled(b)}}
                        if(available && b.runId==probeRun)probeImageReads++
                        if(available)a!! else obj()
                    }
                    "attachment.put" -> { error("Only Native image capture/picker may create attachments") }
                    "end" -> end(c, data)
                    else -> error("Unsupported RPC")
                }
                future.complete(value)
            } catch (e: Exception) {
                lastError = if(request.optString("op") == "begin") "发送失败：请先配置模型并完成图片/工具探测；无模拟回答。" else "操作被拒绝：检查授权、活动运行或本地存储。"
                val code=when(e) {is ToolFailure ->e.code.name;is dev.thor.bridge.BridgeFailure ->e.code.name;is IllegalArgumentException,is org.json.JSONException ->"INVALID_ARGUMENT";else ->"IO_ERROR"}
                val content=JSONArray()
                if(code=="COMMAND_BLOCKED")content.put(obj("type" to "text","text" to obj("policyVersion" to ShellCommandPolicy.VERSION,"policyTier" to "black","executed" to false,"reason" to (e as ToolFailure).message).toString()))
                if(request.optString("op") == "tool.prepare")future.complete(obj("content" to content,"observedAt" to System.currentTimeMillis(),"source" to "native-gateway","truncated" to false,"outcome" to "observed","error" to code)) else future.complete(obj("error" to code)); publish()
            }
        }
        return future
    }
    private fun begin(c: String, d: JSONObject): JSONObject {
        require(prefs.getInt("revision",-1)==revision);lastError=null; val sid = d.getString("sessionId"); val s = session(sid); require(s.isNull("archivedAt"))
        val profile = selectedProfile(s)
        val probe = d.optString("probeToken").isNotEmpty() && d.optString("probeToken") == probeToken
        require(bindings.values.none {it.sessionId==sid} && bindings.size<8)
        require(probe || profilePassed(profile))
        require(!probe || probeRun==null)
        if (probe) { probeToken = null; probeTools = 0; probeImageReads = 0;probeHeads.clear();probeCompleted.clear();probeBodies.clear();probeConsumerClosed.clear() }
        val pendingImages=if(probe)JSONArray() else s.optJSONArray("pendingAttachments") ?: JSONArray()
        val text=if(probe && !supportsImages(profile))"Configuration probe: text-only model. Call probe_picture once, read its returned text, then answer PROBE_OK." else d.getString("text"); require(text.isNotBlank() && text.length <= 8000)
        val b = authority.beginRun(sid, c, buildMap { put(if(probe) Purpose.configuration_test else Purpose.model,setOf(profile.getString("profileId"))); if(!probe) { put(Purpose.web_fetch,setOf("public-web")); if(searchConfigured)put(Purpose.web_search,setOf("search@$revision")) } },durationMs=if(probe)180_000 else 0)
        bindings[b.runId]=b;scopes[b.runId]=RunScope(profile);deviceAuthority.bind(b);if(probe)probeRun=b.runId
        val r = obj("id" to b.runId, "sessionId" to sid, "status" to "queued", "expanded" to false, "startedAt" to System.currentTimeMillis(), "probe" to probe,
            "modelId" to profile.getString("modelId"),"thinkingEffort" to profile.getString("thinkingEffort"),"contextWindow" to profile.getInt("contextWindow"),"modelProfileRevision" to revision, "capabilityEpoch" to b.capabilityEpoch, "contexts" to JSONArray())
        state.getJSONArray("runs").put(r)
        state.getJSONArray("messages").put(obj("id" to id(), "sessionId" to sid, "runId" to b.runId, "role" to "user", "text" to text, "attachments" to pendingImages, "status" to "complete"))
        state.getJSONArray("messages").put(obj("id" to id(), "sessionId" to sid, "runId" to b.runId, "role" to "assistant", "text" to "", "attachments" to JSONArray(), "status" to "streaming"))
        if(!probe)s.put("pendingAttachments",JSONArray()); persist(); publish()
        val input=JSONArray().put(obj("type" to "text","text" to text))
        if(probe && supportsImages(profile))input.put(obj("type" to "attachment","attachmentId" to addProbeImage(b))) else if(!probe)for(i in 0 until pendingImages.length())input.put(obj("type" to "attachment","attachmentId" to pendingImages.getString(i)))
        r.put("turn",obj("turnId" to id(),"runId" to b.runId,"messages" to JSONArray().put(obj("kind" to "user","content" to input)),"source" to JSONArray(if(dev.thor.bridge.Capability.screen_perception in enabled(s))listOf("screen_perception") else emptyList<String>()),"observedAt" to System.currentTimeMillis(),"complete" to false));persist()
        val ttl = authority.remainingLeaseMs(b)
        return obj("runTimeoutMs" to if(probe)180_000 else 0,"lease" to obj("sessionId" to sid, "runId" to b.runId, "leaseId" to b.lease, "capabilityEpoch" to b.capabilityEpoch, "expiresAt" to leaseWallDeadline(b)),
            "network" to obj("binding" to bindingJson(b), "purpose" to if(probe) "configuration_test" else "model", "profileRef" to profile.getString("profileId"), "credentialRef" to credentialRef, "deadlineMs" to if(probe)clock.now()+ttl else 9_007_199_254_740_991L, "timeoutMs" to if(probe)ttl else 0),
            "history" to JSONArray(rows("runs").filter { it.getString("sessionId") == sid && it.getString("id") != b.runId && it.has("turn") && !it.optBoolean("probe") }.map { previous -> recoverTurnHistory(previous.getJSONObject("turn"),rows("events").filter {it.getString("runId")==previous.getString("id")}) }),
            "input" to input)
    }
    private fun bindingJson(b: Binding) = obj("sessionId" to b.sessionId, "runId" to b.runId, "lease" to b.lease, "connectionId" to b.connectionId, "capabilityEpoch" to b.capabilityEpoch)
    private fun authoritySnapshot(b:Binding,refresh: Boolean): JSONObject {
        val scope=scope(b);authority.validate(b);require(prefs.getInt("revision",-1)==revision)
        var snapshot=scope.snapshot;var pendingPerceptionSnapshot=scope.pendingPerceptionSnapshot
        snapshots.update(displayFacts(context))
        if (refresh || snapshot == null) {
            // Preserve a valid frame/snapshot identity across model requests; changed/expired resources get a new identity.
            snapshot = pendingPerceptionSnapshot?.let { fresh -> runCatching { snapshots.validate(fresh.id) }.getOrNull() }
                ?: snapshot?.let { old -> runCatching { snapshots.validate(old.id) }.getOrNull() } ?: runCatching { snapshots.capture() }.getOrNull()
            pendingPerceptionSnapshot=null
        }
        scope.snapshot=snapshot;scope.pendingPerceptionSnapshot=pendingPerceptionSnapshot
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
        available.put("web.fetch", obj("status" to if(b.runId != probeRun) "ready" else "backend_missing", "reason" to "通过系统网络读取 HTTP/HTTPS，支持内网与代理 DNS"))
        val enabled = JSONObject(); for(cap in dev.thor.bridge.Capability.entries) enabled.put(cap.name, cap in authority.enabled(b))
        val profile=JSONObject(scope.profile.toString())
        val probed=profilePassed(profile)
        profile.put("lastProbe", obj("revision" to revision, "textStream" to probed, "imageInput" to (probed && supportsImages(profile)), "toolCalling" to probed, "imageToolRoundTrip" to (probed && supportsImages(profile))))
        if (b.runId == probeRun) {
            profile.put("lastProbe", obj("revision" to revision, "textStream" to true, "imageInput" to supportsImages(profile), "toolCalling" to true, "imageToolRoundTrip" to supportsImages(profile)))
            available.put("probe.picture", obj("status" to "ready"))
        }
        var publishedTools = allowed.toSet() + if(b.runId == probeRun) setOf("probe.picture") else if(dev.thor.bridge.Capability.web_search in authority.enabled(b)) setOf("web.fetch") + if(searchConfigured)setOf("web.search") else emptySet() else emptySet()
        if(b.runId != probeRun) {
            for(tool in skillToolIds)available.put(tool,obj("status" to "ready"))
            publishedTools=publishedTools+skillToolIds
        }
        scope.publishedTools=publishedTools
        val l0 = obj("snapshotId" to (snapshot?.id ?: "no-target:${b.runId}"))
        if (dev.thor.bridge.Capability.screen_perception in authority.enabled(b)) {
            l0.put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            snapshot?.target?.packageName?.let { l0.put("upperApp", obj("packageName" to it, "label" to it)); l0.put("displaySnapshotId", snapshot!!.id) }
        }
        return obj("session" to obj("sessionId" to b.sessionId, "title" to session(b.sessionId).getString("title"), "model" to profile, "enabled" to enabled, "capabilityEpoch" to b.capabilityEpoch),
            "lease" to obj("sessionId" to b.sessionId, "runId" to b.runId, "leaseId" to b.lease, "capabilityEpoch" to b.capabilityEpoch, "expiresAt" to leaseWallDeadline(b)), "availability" to available, "l0" to l0, "skills" to if(b.runId == probeRun)JSONArray() else skillCatalog(session(b.sessionId),true))
    }
    private fun toolEnvelope(c: String, j: JSONObject): Envelope {
        val b = bound(c, j);val snapshot=scope(b).snapshot; require(j.getString("bridgeVersion") == "0.1" && j.getString("schemaVersion") == "1")
        require(j.getString("requestId").length in 1..100 && j.getString("toolCallId").length in 1..512)
        if(j.getString("toolId") in setOf("web.search","web.fetch","web.request"))require(j.getJSONArray("resources").toString() == "[\"public-web\"]")
        if(j.getString("toolId") == "probe.picture")require(j.getJSONArray("resources").toString() == "[\"fixture\"]")
        if(j.getString("toolId") in skillToolIds)require(j.getJSONArray("resources").toString()=="[\"local-skills\"]")
        val remaining = (j.getLong("deadline") - System.currentTimeMillis()).coerceAtMost(authority.remainingLeaseMs(b)).coerceAtMost(60000)
        require(remaining > 0)
        return Envelope(b.sessionId, b.runId, j.getString("toolCallId"), j.getString("toolId"), b.capabilityEpoch, clock.now() + remaining,
            if(snapshot != null) j.getString("targetSnapshotId") else null, j.getString("schemaVersion").toInt(), j.getString("requestId"))
    }
    private fun prepare(c: String, j: JSONObject): JSONObject {
        val e = toolEnvelope(c, j);val scoped=scopes.getValue(e.runId);val networkCalls=scoped.networkCalls;val publishedTools=scoped.publishedTools; val args = j.getJSONObject("arguments").args()
        if(e.toolId in contract) {
            require(j.getJSONArray("resources").toString() == contract.getValue(e.toolId).getJSONArray("resources").toString())
        }
        require(rows("events").any { it.getString("runId") == e.runId && it.optString("toolCallId") == e.toolCallId && it.getString("type") == "tool_intent" })
        require(e.requestId !in prepared && prepared.values.count {it.e.runId==e.runId}<32)
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
        if(e.toolId in skillToolIds) {
            require(e.toolId in publishedTools && e.runId!=probeRun)
            if(e.toolId=="skills.read")require(skillCatalog(session(e.sessionId),true).objects().any {it.getString("name")==j.getJSONObject("arguments").getString("name")}) {"Skill is not mounted in this session"}
            val preview=skillAction(e.toolId,j.getJSONObject("arguments"),true)
            if(e.toolId=="skills.save")require(preview.toString().toByteArray().size<=65536) {"单次 Skill 变更超过审阅预算，请分批保存"}
            val write=e.toolId in setOf("skills.save","skills.enable","skills.delete")
            val p=Prepared(e,args,hash(j.getJSONObject("arguments").toString().toByteArray()),if(write)id() else null,CompletableFuture(),null,when(e.toolId) {
                "skills.save" -> "保存 ${preview.getString("name")}\n\n${preview.getString("content")}\n\n附带资源：\n"+(j.getJSONObject("arguments").optJSONObject("resources")?.let {r->r.keys().asSequence().joinToString("\n") {path->"$path\n${r.getString(path)}"}} ?: "保留现有资源")
                "skills.enable" -> "${if(j.getJSONObject("arguments").getBoolean("enabled"))"启用" else "停用"} ${j.getJSONObject("arguments").getString("name")}"
                else -> "删除 ${j.getJSONObject("arguments").optString("name")} 及附带资料"
            })
            prepared[e.requestId]=p
            if(write)run(e.runId).put("approval",obj("id" to p.approvalId,"requestId" to e.requestId,"summary" to "Skill 变更：\n${p.summary}","status" to "pending")) else p.approved.complete(true)
            persist();publish();return obj("awaiting" to write,"approvalId" to p.approvalId)
        }
        deviceAuthority.validate(e); schemas.validate(e.toolId, args)
        require(redactor.redact(j.getJSONObject("arguments").toString()) == j.getJSONObject("arguments").toString())
        if(e.toolId in setOf("web.request","files.download")) {
            val decoded=java.net.URLDecoder.decode((args.getValue("url") as String).replace("+","%2B"),"UTF-8")
            requireTool(redactor.redact(decoded)==decoded,Code.PERMISSION_MISSING,"Native credentials cannot be included in tool URLs")
        }
        val shellPolicy=if(e.toolId=="debug.shell")ShellCommandPolicy.classify(args.getValue("command") as String) else null
        requireTool(shellPolicy?.tier!=ShellCommandPolicy.Tier.BLACK,Code.COMMAND_BLOCKED,shellPolicy?.reason.orEmpty())
        val brightness=if(e.toolId=="debug.thor_brightness")ThorBrightnessCommand.parse(args) else null
        val needsApproval = when {
            brightness!=null -> brightness.needsApproval
            shellPolicy!=null -> shellPolicy.tier==ShellCommandPolicy.Tier.GRAY
            ToolPack.descriptors.getValue(e.toolId).effect==Effect.ARBITRARY_SHELL -> fail(Code.COMMAND_BLOCKED,"未配置命令策略的执行器禁止调用")
            else -> ToolPack.descriptors.getValue(e.toolId).effect==Effect.WRITE
        }
        var approval: Approval? = null; var summary = brightness?.summary.orEmpty()
        if(e.toolId=="apps.install") {
            val record=backend.downloads.installPreview(args.getValue("downloadId") as String,args.getValue("sha256") as String)
            val apk=record.getJSONObject("apk")
            summary="安装应用 · 需要本次确认\n${apk.getString("label")} (${apk.getString("packageName")})\n版本 ${apk.optString("versionName")} · ${apk.getLong("versionCode")}\n来源：${record.getString("url")}\n实际下载地址：${record.getString("finalUrl")}\n文件：${record.getString("filename")} · ${record.getLong("size")} 字节\nSHA-256：${record.getString("sha256")}\n签名：${apk.getJSONArray("signerSha256").let {a->(0 until a.length()).joinToString(", "){a.getString(it)}}}\n声明权限：${apk.getJSONArray("requestedPermissions").let {a->(0 until a.length()).joinToString(", "){a.getString(it)}}}\n\n通过 ADB 安装此文件，可能覆盖同签名的旧版本；不允许降级、不自动授予权限。确认仅适用于本次运行和此文件。"
        }
        if (e.toolId == "debug.shell") summary = "灰名单 · 需要本次确认\n${args.getValue("command")}\n\n${shellPolicy!!.reason}\n确认仅适用于当前会话、本次运行和这条具体命令。"
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
            val b=bindingFor(j);val content=if(supportsImages(scope(b).profile))obj("type" to "attachment","attachmentId" to addProbeImage(b)) else obj("type" to "text","text" to "PROBE_TEXT_OK")
            future.complete(obj("content" to JSONArray().put(content), "observedAt" to System.currentTimeMillis(), "source" to "native-probe", "truncated" to false, "outcome" to "observed")); return
        }
        val p = prepared[e.requestId] ?: error("Tool not prepared")
        require(p.e.toolCallId == e.toolCallId && p.e.runId == e.runId && p.e.toolId == e.toolId && p.e.snapshotId == e.snapshotId && p.e.capabilityEpoch == e.capabilityEpoch)
        require(p.fingerprint == hash(j.getJSONObject("arguments").toString().toByteArray()))
        // Approval waiting does not occupy the shared device executor. Only the
        // short authorized operation is serialized against other device actions.
        p.approved.whenComplete {_,_->
        try { (if(ToolPack.descriptors[p.e.toolId]?.concurrencyKey=="network")networkWorker else deviceWorker).execute worker@{
            try {
                val permitted = p.approved.get((p.e.deadline - clock.now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                requireTool(permitted, Code.PERMISSION_MISSING, "用户拒绝审批")
                if(p.e.toolId in skillToolIds) {
                    serial.execute {
                        try {
                            val b=bound(c,j);require(p.e.toolId in scope(b).publishedTools && clock.now()<p.e.deadline)
                            val result=skillAction(p.e.toolId,j.getJSONObject("arguments"),false)
                            prepared.remove(e.requestId);publish()
                            future.complete(obj("content" to JSONArray().put(obj("type" to "text","text" to result.toString())),"observedAt" to System.currentTimeMillis(),"source" to "native-skills","truncated" to false,"outcome" to "observed"))
                        } catch(_:Exception) {future.complete(obj("error" to "HASH_CONFLICT","content" to JSONArray(),"observedAt" to System.currentTimeMillis(),"source" to "native-skills","truncated" to false,"outcome" to "observed"))}
                    }
                    return@worker
                }
                deviceAuthority.validate(p.e)
                if(ToolPack.descriptors[p.e.toolId]?.concurrencyKey!="network") {
                    backend.approval = p.approval
                    backend.installApproval = if(p.e.toolId=="apps.install" && p.approvalId!=null)p.args.getValue("sha256") as String else null
                    backend.shellApproval = if(p.approvalId==null)null else when(p.e.toolId) {
                        "debug.shell" -> hash((p.args.getValue("command") as String).toByteArray())
                        else -> null
                    }
                }
                val result = gateway.execute(p.e, p.args)
                serial.execute {
                    try {
                        bound(c, j)
                        val freshObservation=if(e.toolId=="perception.capture_screen" && result.error==null)snapshots.validate(result.snapshotId) else null
                        val data = result.data.toMutableMap(); val content = JSONArray()
                        val path = data.remove("attachmentRef") as? String
                        if(path != null && result.error == null) {
                            val file = File(path); require(file.canonicalPath.startsWith(File(context.cacheDir, "tool-frames").canonicalPath + "/"))
                            val bytes = file.readBytes(); require(bytes.size <= 2097152)
                            val aid = id(); val a = obj("id" to aid, "attachmentId" to aid, "sessionId" to e.sessionId, "runId" to e.runId, "mimeType" to "image/jpeg", "data" to Base64.encodeToString(bytes, Base64.NO_WRAP), "observedAt" to System.currentTimeMillis(), "expiresAt" to System.currentTimeMillis()+600000, "source" to JSONArray(listOf("screen_perception")), "size" to bytes.size, "hash" to hash(bytes))
                            state.getJSONArray("attachments").put(a); persist(); content.put(obj("type" to "attachment", "attachmentId" to aid))
                        }
                        content.put(obj("type" to "text", "text" to redactor.redact(JSONObject(json(data + mapOf("message" to result.message)).toString()).toString())))
                        // The next model request receives this fresh observation;
                        // tools still in the old response retain their old target.
                        if(freshObservation!=null)scopes.getValue(e.runId).pendingPerceptionSnapshot=snapshots.validate(freshObservation.id)
                        future.complete(obj("content" to content, "observedAt" to (System.currentTimeMillis() - (clock.now()-result.observedAt).coerceAtLeast(0)), "source" to result.source, "truncated" to result.truncated,
                            "evidenceRef" to (data["backupId"]?.let {"backup:$it"} ?: "native-event:${e.runId}:${e.requestId}"), "error" to result.error?.name, "outcome" to if(result.outcome == "not_submitted") "observed" else result.outcome))
                    } catch (error: Exception) { future.complete(obj("error" to ((error as? ToolFailure)?.code?.name ?: "STALE_AUTHORIZATION"), "content" to JSONArray(), "observedAt" to System.currentTimeMillis(), "source" to "native", "truncated" to false, "outcome" to "unknown")) }
                }
            } catch(e: Exception) { future.complete(obj("error" to (e as? ToolFailure)?.code?.name.orEmpty().ifEmpty { "CANCELLED" }, "content" to JSONArray(), "observedAt" to System.currentTimeMillis(), "source" to "native", "truncated" to false, "outcome" to "observed")) }
            finally { if(ToolPack.descriptors[p.e.toolId]?.concurrencyKey!="network") {backend.approval = null; backend.shellApproval = null; backend.installApproval=null} }
        } } catch(_:java.util.concurrent.RejectedExecutionException) {future.complete(obj("error" to "CANCELLED","content" to JSONArray(),"outcome" to "observed"))}
        }
    }

    private fun eventSummary(event:JSONObject):String {
        val d=event.getJSONObject("data")
        return when(event.getString("type")) {
            "text_delta" -> "回答更新"
            "thinking_delta" -> "思考更新"
            "tool_call_update" -> "准备调用 ${d.optString("toolName")}"
            "tool_intent" -> "准备工具 ${d.optString("toolId")}"
            "tool_started" -> "执行工具 ${d.optString("toolId")}"
            "tool_result" -> if(d.has("error") && !d.isNull("error"))"工具执行失败：${d.optString("error")}" else "工具执行结束"
            "context" -> "已整理本轮上下文"
            "state" -> "运行状态更新"
            "authorization_changed" -> "授权已变更"
            else -> "运行提示"
        }
    }
    private fun save(c: String, d: JSONObject): JSONObject {
        val b = bindingFor(d); require(c == connection && d.getString("sessionId") == b.sessionId && d.getString("runId") == b.runId)
        // Terminal/cancellation events can persist after Native has revoked the execution lease.
        val kind = d.getString("kind"); val value = d.getJSONObject("value"); val r = run(b.runId)
        require(redactor.redact(value.toString()) == value.toString())
        var appendedEvent: JSONObject?=null
        when(kind) {
            "event", "commit" -> {
                val event = if(kind == "commit") d.getJSONObject("event") else value
                require(event.getString("sessionId") == b.sessionId && event.getString("runId") == b.runId)
                val sequence = rows("events").count { it.getString("runId") == b.runId } + 1
                require(event.getInt("sequence") == sequence)
                val copy = JSONObject(event.toString()).put("id", event.getString("eventId")).put("summary", eventSummary(event))
                for(key in listOf("toolId","outcome","error")) if(event.getJSONObject("data").has(key))copy.put(key,event.getJSONObject("data").get(key))
                state.getJSONArray("events").put(copy)
                appendedEvent=copy
                val data = event.getJSONObject("data")
                if(event.getString("type") == "text_delta") rows("messages").single { it.getString("runId") == b.runId && it.getString("role") == "assistant" }.let { m -> val text = m.getString("text") + data.getString("text"); require(text.length <= 65536); m.put("text", text) }
                if(event.getString("type")=="thinking_delta") rows("messages").single {it.getString("runId")==b.runId && it.getString("role")=="assistant"}.let {m->val text=m.optString("thinking")+data.getString("text");require(text.length<=65536);m.put("thinking",text)}
                if(kind == "commit") { require(value.getString("sessionId") == b.sessionId && value.getString("runId") == b.runId); r.put("status", value.getString("state")); r.put("runtime", value); if(value.getString("state") in terminal) r.put("expanded", false) }
            }
            "run" -> { require(value.getString("runId") == b.runId && value.getString("sessionId") == b.sessionId); r.put("runtime", value).put("status", value.getString("state")) }
            "context" -> { require(value.getInt("modelProfileRevision") == revision && value.getLong("capabilityEpoch") == b.capabilityEpoch); require(value.getJSONArray("effectiveTools").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } == scope(b).publishedTools); r.getJSONArray("contexts").put(value) }
            "turn" -> { require(value.getString("runId") == b.runId); r.put("turn", value) }
            else -> error("Unsupported persistence method")
        }
        persist(r,appendedEvent); publish(); return obj()
    }
    private fun end(c: String, d: JSONObject): JSONObject {
        val b = bindingFor(d); require(c == connection && d.getString("runId") == b.runId && d.getString("sessionId") == b.sessionId)
        val r = run(b.runId)
        if(r.optString("status") !in terminal) {
            if(r.optString("stopReason")=="用户停止")r.put("status","cancelled")
            else r.put("status","failed").put("stopReason","Runtime 未完成；不重放")
        }
        if(b.runId == probeRun) {
            val turn = r.optJSONObject("turn")
            val passed = r.getString("status") == "completed" && (probeCompleted+probeConsumerClosed).size >= 2 && probeTools > 0 && (!supportsImages(scope(b).profile) || probeImageReads >= 2) && r.getJSONArray("contexts").length() >= 2 && turn?.optBoolean("complete") == true && rows("events").any { it.getString("runId") == b.runId && it.getString("type") == "text_delta" }
            if(scope(b).profile.getString("modelId")==prefs.getString("model","") && scope(b).profile.getString("protocol")==ModelCatalog.protocols[prefs.getInt("protocol",0).coerceIn(0,2)])check(prefs.edit().putString("probe",if(passed)"passed" else "failed").putInt("probeRevision",revision).putBoolean("probeImages",supportsImages(scope(b).profile)).commit())
            r.put("probeTransport",obj("httpStatuses" to JSONArray(probeHeads.values.toList()),"eofRequests" to probeCompleted.size,"clientClosedAfterBodyRequests" to probeConsumerClosed.size,"nonemptyBodies" to probeBodies.size,"toolCalls" to probeTools,"imageReads" to probeImageReads))
            r.put("probePassed", passed)
            check(prefs.edit().putBoolean(probeKey(scope(b).profile),passed).commit())
        }
        authority.stopRun(b.runId);deviceAuthority.stop(b.runId);backend.release(b.runId);prepared.values.filter {it.e.runId==b.runId}.forEach {it.approved.complete(false)};prepared.entries.removeAll {it.value.e.runId==b.runId};bindings.remove(b.runId);scopes.remove(b.runId);if(probeRun==b.runId)probeRun=null
        rows("messages").filter { it.getString("runId") == b.runId }.forEach { it.put("status", "complete") }
        persist(); publish(); return obj()
    }
    private fun ui(c: String, d: JSONObject): JSONObject {
        when(d.getString("op")) {
            "ready" -> Unit
            "skillRead" -> {require(bindings.isEmpty());skillDetail=skills.read(d.getString("name"),d.optString("path","SKILL.md"))}
            "skillClose" -> skillDetail=null
            "skillEnable" -> {require(bindings.isEmpty());skills.setEnabled(d.getString("name"),d.getBoolean("enabled"),d.getString("hash"));skillDetail=null}
            "skillDelete" -> {require(bindings.isEmpty());skills.delete(d.getString("name"),d.getString("hash"));skillDetail=null}
            "sessionOptions" -> {
                val s=session(d.getString("sessionId"))
                if(d.has("modelId")) {require(modelChoices().objects().any {it.getString("id")==d.getString("modelId")});s.put("modelId",d.getString("modelId"));s.remove("thinkingEffort")}
                if(d.has("thinkingEffort")) {val p=selectedProfile(s,false);require((0 until p.getJSONArray("efforts").length()).any {p.getJSONArray("efforts").getString(it)==d.getString("thinkingEffort")});s.put("thinkingEffort",d.getString("thinkingEffort"))}
                if(d.has("selectedSkills")) {val names=d.getJSONArray("selectedSkills");require(names.length()<=128);val known=skills.catalog().objects().map {it.getString("name")}.toSet();require((0 until names.length()).all {names.getString(it) in known});s.put("selectedSkills",JSONArray(names.toString()))}
                persist()
            }
            "new" -> { newSession(); persist() }
            "select" -> { require(session(d.getString("sessionId")).isNull("archivedAt")); state.put("selected", d.getString("sessionId")); persist() }
            "rename" -> { val title = d.getString("title"); require(title.isNotBlank() && title.length <= 80); session(d.getString("sessionId")).put("title", title); persist() }
            "expanded" -> { run(d.getString("runId")).put("expanded", d.getBoolean("expanded")); persist() }
            "archive", "delete" -> {
                val sid = d.getString("sessionId"); require(bindings.values.none {it.sessionId==sid})
                if(d.getString("op") == "archive") session(sid).put("archivedAt", if(d.getBoolean("archived")) System.currentTimeMillis() else JSONObject.NULL)
                else for(key in listOf("sessions", "messages", "runs", "events", "attachments")) state.put(key, JSONArray(rows(key).filter { if(key == "sessions") it.getString("id") != sid else it.getString("sessionId") != sid }))
                if(rows("sessions").none { it.getString("id") == state.optString("selected") && it.isNull("archivedAt") }) newSession()
                persist()
            }
            "capability" -> {
                val sid = d.getString("sessionId"); val cap = capNames.getValue(d.getString("capability")); authority.setEnabled(sid, cap, d.getBoolean("enabled"))
                val s = session(sid); s.getJSONObject("capabilities").put(d.getString("capability"), d.getBoolean("enabled")); s.put("capabilityEpoch", authority.sessionEpoch(sid))
                bindings.values.filter {it.sessionId==sid}.toList().forEach {stop("能力已变更；旧 lease 不会复活",it.runId)}
                persist()
            }
            "stop" -> { val b=bindingFor(d);stop("用户停止",b.runId) }
            "approve", "reject" -> {
                val b=bindingFor(d); authority.validate(b)
                val p = prepared.values.single { it.approvalId == d.getString("approvalId") && it.e.runId == b.runId }
                require(!p.approved.isDone && clock.now() < p.e.deadline)
                if(p.e.toolId !in skillToolIds)deviceAuthority.validate(p.e)
                run(b.runId).getJSONObject("approval").put("status", if(d.getString("op") == "approve") "approved" else "rejected")
                persist(); p.approved.complete(d.getString("op") == "approve")
            }
            else -> error("Unsupported UI command")
        }
        publish(); return view()
    }
    fun stop(reason:String,runId:String?=null) {
        authority.stopRun(runId);deviceAuthority.stop(runId)
        prepared.values.filter {runId==null || it.e.runId==runId}.forEach {it.approved.complete(false)}
        bindings.values.filter {runId==null || it.runId==runId}.forEach {b->backend.release(b.runId);run(b.runId).takeIf {it.optString("status") !in terminal}?.put("stopReason",reason)}
    }
    fun interrupt(reason:String) {if(closing)return;runCatching {serial.execute {stop(reason);bindings.values.forEach {b->run(b.runId).takeIf {it.optString("status") !in terminal}?.put("status","interrupted")};persist();bindings.clear();scopes.clear();connection=null;publish()}}}
    private fun revokeResources() {if(closing)return;runCatching {serial.execute {bindings.values.toList().forEach {authority.invalidateResources(it.sessionId);stop("系统授权或后端失效",it.runId)};persist();publish()}}}
    fun calibrate(upper: Int, lower: Int) { snapshots.update(displayFacts(context)); snapshots.calibrate(upper, lower); revokeResources() }
    fun bindDebug() = debug.requestAndBind()
    fun accessibilitySetupStatus() = debug.accessibilitySetup
    fun requestProbe(): String { require(probeRun==null && model != null); val token = id(); probeToken = token; return token }
    fun requestImagePicker(): String { require(bindings.values.none {it.sessionId==selectedSession()});return selectedSession() }
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
        state.getJSONArray("attachments").put(obj("id" to aid, "attachmentId" to aid, "sessionId" to b.sessionId, "runId" to b.runId, "mimeType" to "image/png", "data" to Base64.encodeToString(bytes, Base64.NO_WRAP), "observedAt" to System.currentTimeMillis(), "expiresAt" to System.currentTimeMillis()+180000, "source" to JSONArray(), "size" to bytes.size, "hash" to hash(bytes)))
        persist(); return aid
    }
    fun requestVoiceSession(sid:String) {require(bindings.values.none {it.sessionId==sid} && sid==selectedSession() && session(sid).isNull("archivedAt"))}
    fun importSkill(uri:android.net.Uri):CompletableFuture<JSONObject> {
        val future=CompletableFuture<JSONObject>()
        serial.execute {try {
            require(bindings.isEmpty())
            val name=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else ""}.orEmpty().ifBlank {uri.lastPathSegment.orEmpty()}
            val files=context.contentResolver.openInputStream(uri)!!.use {SkillStore.unpack(it,name.endsWith(".zip",true) || context.contentResolver.getType(uri)=="application/zip")}
            // Import never silently overwrites an installed skill.
            val result=skills.save(files);lastError=null;publish();future.complete(result)
        } catch(_:Exception) {lastError="Skill 导入失败：检查 YAML 名称/简介、重复名称、目录路径和文件大小（单文件 64 KiB，包 2 MiB）。";publish();future.completeExceptionally(IllegalArgumentException("Skill import rejected"))} }
        return future
    }
    private fun skillAction(tool:String,args:JSONObject,preview:Boolean):JSONObject {
        require(args.toString().length<=3*1024*1024 && redactor.redact(args.toString())==args.toString())
        val expected=when(tool) {"skills.list"->setOf("query","offset");"skills.read"->setOf("name","path");"skills.save"->setOf("content","expectedHash","files","resources");"skills.enable"->setOf("name","expectedHash","enabled");else->setOf("name","expectedHash")}
        require(args.keys().asSequence().all {it in expected})
        if(tool=="skills.list") {
            val query=args.optString("query").lowercase();val offset=args.optInt("offset",0);require(query.length<=200 && offset in 0..64)
            val matches=skills.catalog().objects().filter {query.isEmpty() || it.getString("name").lowercase().contains(query) || it.getString("description").lowercase().contains(query)}
            val page=matches.drop(offset).take(8)
            return obj("skills" to JSONArray(page),"total" to matches.size,"nextOffset" to if(offset+page.size<matches.size)offset+page.size else null)
        }
        if(tool=="skills.read")return if(preview)obj() else skills.read(args.getString("name"),args.optString("path","SKILL.md"),true)
        if(tool=="skills.save") {
            val content=args.getString("content");val hash=args.getString("expectedHash")
            val resources=skillTextResources(args)
            val files=skills.textPackage(content,resources,hash)
            return if(preview)obj("action" to "save","name" to SkillStore.manifest(content).first,"expectedHash" to hash,"content" to content,"resources" to resources) else skills.save(files,hash)
        }
        val name=args.getString("name");val hash=args.getString("expectedHash")
        val item=skills.catalog().objects().single {it.getString("name")==name};require(item.getString("hash")==hash)
        if(tool=="skills.delete")require(!item.getBoolean("builtin"))
        if(!preview) {if(tool=="skills.delete")skills.delete(name,hash) else skills.setEnabled(name,args.getBoolean("enabled"),hash)}
        return obj("action" to tool,"name" to name,"enabled" to args.optBoolean("enabled"),"expectedHash" to hash)
    }
    fun view(): JSONObject {
        val selectedBinding=bindings.values.find {it.sessionId==state.optString("selected")}
        val v = chatSnapshot(state,selectedBinding?.runId)
        if(storageFailed)v.put("fatal","本地存储失败；已停止网络和设备执行。历史保留；失败的变更未保存，重新打开将恢复上次已保存的开关。修复存储后重开，不会重放副作用。")
        v.put("availability", obj("perception" to if(ThorAccessibilityService.instance == null) "无障碍未授权；设备信息可用" else "需校准显示；按工具检查",
            "search" to if(searchConfigured) "Brave 搜索已配置；HTTP/HTTPS 网页读取可用" else "搜索未配置；HTTP/HTTPS 网页读取可用", "screen_control" to "触摸需校准/无障碍；手柄 unsupported", "debug" to "Shell: ${debug.availability().reason}；白名单常用调整直接执行，灰名单逐项确认；文件仅合成目录"))
        v.put("skills",skills.catalog()).put("skillDetail",skillDetail ?: JSONObject.NULL)
        v.put("activeRunId",selectedBinding?.runId ?: JSONObject.NULL)
        v.put("activeRuns",JSONArray(bindings.values.map {b->obj("id" to b.runId,"sessionId" to b.sessionId,"status" to run(b.runId).optString("status").let {if(it in terminal)"stopping" else it},"stopReason" to run(b.runId).optString("stopReason"))}))
        v.put("modelChoices",modelChoices()).put("modelSelection",selectedProfile(session(state.getString("selected")),false))
        v.put("error", lastError ?: JSONObject.NULL)
        v.put("modelStatus", if(model==null)"模型未配置" else if(profilePassed(selectedProfile(session(state.getString("selected")),false)))"连接已通过" else "首次使用时自动检查连接")
        return v
    }
    private fun publish() = notify(view())
    override fun close() {
        if(closing)return;closing=true
        displayManager.unregisterDisplayListener(displayListener)
        authority.stopRun(); prepared.values.forEach { it.approved.complete(false) }
        ThorAccessibilityService.invalidated = null
        serial.execute { try {stop("宿主关闭");bindings.values.forEach {b->run(b.runId).takeIf {it.optString("status") !in terminal}?.put("status","interrupted")};persist();bindings.clear();scopes.clear();deviceAuthority.close()} finally {store.close();storageOwner.release()} }
        debug.close(); deviceWorker.shutdownNow();networkWorker.shutdownNow(); serial.shutdown()
    }
}
