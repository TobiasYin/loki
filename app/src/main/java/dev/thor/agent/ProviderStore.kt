package dev.thor.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Native-only provider records. Secrets stay in CredentialVault under a per-record reference. */
class ProviderStore(private val backend: Backend) {
    interface Backend {
        fun read(): String?
        fun write(value: String)
        fun legacy(): JSONObject?
        fun readCredential(ref: String): String?
        fun writeCredential(ref: String, value: String)
        fun removeCredential(ref: String)
        fun credentialStatus(ref: String): String
    }
    constructor(context: Context): this(AndroidBackend(context.applicationContext))
    init { synchronized(lock) { initialize() } }
    fun list(): List<JSONObject> = synchronized(lock) { records(document()).map { copy(it) } }
    fun get(id: String): JSONObject? = list().firstOrNull { it.getString("id") == id }
    fun defaultId(): String? = synchronized(lock) { document().optString("defaultId").takeIf { it.isNotBlank() } }
    fun credential(id: String): String? = synchronized(lock) { require(get(id)!=null); backend.readCredential(credentialRef(id)) }
    fun credentialStatus(id: String): String = synchronized(lock) { require(get(id)!=null); backend.credentialStatus(credentialRef(id)) }
    fun save(config: JSONObject, key: String? = null, removeKey: Boolean = false): JSONObject = synchronized(lock) {
        require(!(removeKey && !key.isNullOrBlank()))
        val doc=document();val rows=records(doc).toMutableList()
        val requested=config.optString("id").ifBlank { UUID.randomUUID().toString() }
        require(validId(requested))
        val old=rows.firstOrNull { it.getString("id")==requested }
        require(old!=null || rows.size<MAX_PROVIDERS) { "最多保存 16 个模型服务" }
        val clean=normalize(config,requested,(old?.getInt("revision") ?: 0)+1)
        require(old==null || old.getString("kind")==clean.getString("kind")) { "服务类型不可更改，请添加新的服务" }
        // A changed endpoint must never silently inherit an existing credential.
        val changedDestination=old!=null && destination(old.getString("url"))!=destination(clean.getString("url"))
        require(!changedDestination || !key.isNullOrBlank() || removeKey) { "修改服务地址后请重新填写该服务的 API Key" }
        if(!key.isNullOrBlank())validateKey(key)
        val oldDocument=requireNotNull(backend.read())
        val changesCredential=removeKey || !key.isNullOrBlank()
        // Snapshot only this record. No other provider's credential participates in a save.
        val previousCredential=if(changesCredential)backend.readCredential(credentialRef(requested)) else null
        if(old==null)rows.add(clean) else rows[rows.indexOf(old)]=clean
        doc.put("records",JSONArray(rows))
        if(doc.optString("defaultId").isBlank() && clean.getBoolean("enabled"))doc.put("defaultId",requested)
        repairDefault(doc,rows)
        try {
            if(removeKey)backend.removeCredential(credentialRef(requested))
            else if(!key.isNullOrBlank())backend.writeCredential(credentialRef(requested),key)
            backend.write(doc.toString())
        }catch(error:Exception) {
            // Backends may throw after applying an in-memory change (e.g. commit failure).
            // Restore both stores rather than leaving an old endpoint paired with a new Key.
            if(changesCredential)try {
                if(previousCredential==null)backend.removeCredential(credentialRef(requested))
                else backend.writeCredential(credentialRef(requested),previousCredential)
            }catch(rollback:Exception){error.addSuppressed(rollback)}
            try {if(backend.read()!=oldDocument)backend.write(oldDocument)}catch(rollback:Exception){error.addSuppressed(rollback)}
            throw error
        }
        copy(clean)
    }
    fun delete(id: String) = synchronized(lock) {
        val doc=document();val rows=records(doc).filterNot { it.getString("id")==id }
        require(rows.size!=records(doc).size)
        // Remove metadata first: a storage failure cannot leave an apparently usable stale record.
        doc.put("records",JSONArray(rows));repairDefault(doc,rows);backend.write(doc.toString())
        backend.removeCredential(credentialRef(id))
    }
    fun setDefault(id: String) = synchronized(lock) {
        val doc=document();require(records(doc).any {it.getString("id")==id && it.getBoolean("enabled")})
        backend.write(doc.put("defaultId",id).toString())
    }
    fun setEnabled(id: String, enabled: Boolean): JSONObject = synchronized(lock) {
        val config=requireNotNull(get(id));config.put("enabled",enabled);save(config)
    }
    private fun initialize() {
        if(backend.read()!=null){document();return}
        val legacy=backend.legacy()
        val rows=mutableListOf<JSONObject>()
        if(legacy!=null && legacy.optString("url").isNotBlank()) {
            val passed=legacy.optString("probe")=="passed" && legacy.optInt("probeRevision",-1)==legacy.optInt("revision",0)
            val normalized=normalize(legacy,"legacy",maxOf(1,legacy.optInt("revision",1)))
                .put("legacyMigration",true).put("legacyProbePassed",passed).put("legacyProbeImages",passed && legacy.optBoolean("probeImages",false))
            // Migration is retryable. The legacy reference and preferences are never modified.
            backend.readCredential("model")?.let {backend.writeCredential(credentialRef("legacy"),it)}
            rows.add(normalized)
        }
        backend.write(JSONObject().put("version",1).put("records",JSONArray(rows)).put("defaultId",if(rows.isEmpty())"" else "legacy").toString())
    }
    private fun document(): JSONObject {
        val doc=JSONObject(requireNotNull(backend.read()))
        require(doc.getInt("version")==1 && doc.getJSONArray("records").length()<=MAX_PROVIDERS)
        val rows=records(doc);require(rows.map {it.getString("id")}.distinct().size==rows.size)
        rows.forEach {normalize(it,it.getString("id"),it.getInt("revision"))}
        return doc
    }
    private fun records(doc: JSONObject): List<JSONObject> = doc.getJSONArray("records").let {a->(0 until a.length()).map {a.getJSONObject(it)}}
    private fun repairDefault(doc: JSONObject, rows: List<JSONObject>) {
        if(rows.none {it.getString("id")==doc.optString("defaultId") && it.getBoolean("enabled")})
            doc.put("defaultId",rows.firstOrNull {it.getBoolean("enabled")}?.getString("id") ?: "")
    }
    companion object {
        const val MAX_PROVIDERS=16
        private val lock=Any()
        fun credentialRef(id: String): String {require(validId(id));return "provider.$id.model"}
        private fun validId(id: String)=id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9-]{0,63}"))
        private fun copy(value: JSONObject)=JSONObject(value.toString())
        private fun validateKey(key: String) {require(key.length in 1..8192 && '\r' !in key && '\n' !in key)}
        private fun destination(url: String): String=URI(url).normalize().let {"${it.scheme.lowercase()}://${it.host.lowercase()}:${it.port}${it.path.trimEnd('/')}"}
        fun normalize(config: JSONObject,id: String,revision: Int): JSONObject {
            require(validId(id) && revision>0)
            val kind=config.optString("kind",config.optString("provider","custom"))
            require(kind in setOf("opencode-go","deepseek",VolcAgentPlan.KIND,"custom"))
            val url=config.getString("url").trim().trimEnd('/')
            val uri=URI(url);require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null && url.length<=2048)
            if(kind==VolcAgentPlan.KIND)require(url==VolcAgentPlan.BASE_URL && config.optInt("protocol",0)==0)
            if(kind=="deepseek")require(url==DeepSeekCatalog.BASE_URL)
            if(kind=="opencode-go")require(url=="https://opencode.ai/zen/go/v1")
            val protocol=config.optInt("protocol",0);require(protocol in 0..2)
            val model=config.getString("model").trim();require(model.isNotBlank() && model.length<=200)
            val name=config.optString("name").trim().ifBlank {when(kind){"opencode-go"->"OpenCode Go";"deepseek"->"DeepSeek 官方";VolcAgentPlan.KIND->"火山 AgentPlan";else->"自定义服务"}};require(name.length<=80)
            val context=config.optInt("contextBudget",32768);require(context in 4096..4000000)
            val maxOutput=config.optInt("maxOutput",2048);require(maxOutput in 16 until context)
            val outputText=config.optString("maxOutputText").trim();require(outputText.isEmpty() || outputText.matches(Regex("[0-9]+")) && outputText.toIntOrNull()?.let {it in 16 until context}==true)
            val headerName=if(kind=="custom")config.optString("headerName") else ""
            val headerTemplate=if(kind=="custom")config.optString("headerTemplate") else ""
            ModelConnectionTest.Draft(url,ModelCatalog.protocols[protocol],model,"validation-placeholder",headerName,headerTemplate,maxOutput=maxOutput).validate()
            return JSONObject().put("id",id).put("kind",kind).put("name",name).put("enabled",config.optBoolean("enabled",true))
                .put("url",url).put("model",model).put("protocol",protocol).put("contextBudget",context)
                .put("contextBudgetText",config.optString("contextBudgetText")).put("maxOutputText",outputText).put("maxOutput",maxOutput)
                .put("supportsImages",config.optBoolean("supportsImages",true)).put("headerName",headerName).put("headerTemplate",headerTemplate).put("revision",revision)
        }
    }
    private class AndroidBackend(context: Context): Backend {
        private val prefs=context.getSharedPreferences("provider-configs",Context.MODE_PRIVATE)
        private val old=context.getSharedPreferences("profiles",Context.MODE_PRIVATE)
        private val vault=CredentialVault(context)
        override fun read()=prefs.getString("document",null)
        override fun write(value: String) {check(prefs.edit().putString("document",value).commit()) {"模型服务保存失败"}}
        override fun legacy(): JSONObject? {
            val url=old.getString("url","").orEmpty();if(url.isBlank())return null
            val out=JSONObject();listOf("url","model","name","contextBudgetText","maxOutputText","headerName","headerTemplate").forEach {out.put(it,old.getString(it,"").orEmpty())}
            out.put("kind",old.getString("provider",null) ?: if(url.startsWith("https://opencode.ai/zen/go"))"opencode-go" else "custom")
            listOf("protocol","contextBudget","maxOutput","revision").forEach {out.put(it,old.getInt(it,when(it){"contextBudget"->32768;"maxOutput"->2048;else->0}))}
            return out.put("supportsImages",old.getBoolean("supportsImages",true)).put("enabled",true)
                .put("probe",old.getString("probe","")).put("probeRevision",old.getInt("probeRevision",-1)).put("probeImages",old.getBoolean("probeImages",false))
        }
        override fun readCredential(ref: String)=vault.readNative(ref)
        override fun writeCredential(ref: String,value: String)=vault.put(ref,value)
        override fun removeCredential(ref: String)=vault.remove(ref)
        override fun credentialStatus(ref: String)=vault.status(ref)
    }
}
