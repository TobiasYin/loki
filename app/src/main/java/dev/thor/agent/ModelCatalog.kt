package dev.thor.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public provider metadata; credentials never enter the WebView or the catalog. */
class ModelCatalog(private val context:Context) {
    companion object {
        const val SOURCE="https://models.dev/api.json"
        const val AVAILABILITY_SOURCE="https://opencode.ai/zen/go/v1/models"
        private const val CACHE_VERSION=2
        val protocols=listOf("openai-responses","anthropic-messages","openai-completions")
        fun normalize(raw:JSONObject):JSONArray {
            val result=JSONArray()
            for(api in protocols) {
                val entries=raw.optJSONObject(api) ?: continue
                for(key in entries.keys()) {
                    val m=entries.getJSONObject(key);val id=m.getString("id")
                    val context=m.getInt("contextWindow");val output=m.getInt("maxTokens")
                    require(context in 4096..4000000 && output in 16 until context)
                    val map=m.optJSONObject("thinkingLevelMap")
                    // Match Pi getSupportedThinkingLevels: null explicitly excludes a level;
                    // basic levels default to supported, extended levels need a mapping.
                    val efforts=if(!m.optBoolean("reasoning"))listOf("off") else listOf("off","minimal","low","medium","high","xhigh","max").filter {level->
                        (map==null || !map.has(level) || !map.isNull(level)) && (level !in setOf("xhigh","max") || map?.has(level)==true)
                    }
                    require(efforts.isNotEmpty() && efforts.all {it in setOf("off","minimal","low","medium","high","xhigh","max")})
                    result.put(obj("id" to id,"name" to m.getString("name"),"protocol" to if(id=="deepseek-v4.1-flash")"openai-responses" else api,
                        "contextWindow" to context,"maxOutput" to output,"reasoning" to m.optBoolean("reasoning"),
                        "efforts" to JSONArray(efforts),"thinkingLevelMap" to map,"compat" to m.optJSONObject("compat"),"input" to m.optJSONArray("input")))
                }
            }
            require(result.length() in 1..256)
            return result
        }
        /** OpenCode's documented catalog source + its public service model list.
         * Unknown models/protocols without usable metadata are omitted, never guessed. */
        fun normalizeOfficial(raw:JSONObject,available:JSONObject,transportProfiles:JSONArray):JSONArray {
            val provider=raw.getJSONObject("opencode-go");val models=provider.getJSONObject("models")
            val ids=available.getJSONArray("data").objects().map {it.getString("id")}.toSet()
            require(ids.isNotEmpty() && ids.size<=256)
            val transports=transportProfiles.objects().associateBy {it.getString("id")}
            val result=JSONArray()
            for(id in ids.sorted()) {
                val source=models.optJSONObject(id) ?: continue
                val fallback=transports[id]
                val wire=source.optJSONObject("provider")
                val npm=wire?.optString("npm").orEmpty().ifBlank {provider.optString("npm")}
                // Existing Pi transport compatibility includes the explicitly supported
                // Go DeepSeek Responses route; capability facts still come from Models.dev.
                val protocol=fallback?.optString("protocol") ?: when(npm) {
                    "@ai-sdk/anthropic"->"anthropic-messages"
                    "@ai-sdk/openai"->"openai-responses"
                    "@ai-sdk/openai-compatible"->"openai-completions"
                    else->continue
                }
                if(protocol !in protocols)continue
                val limit=source.optJSONObject("limit") ?: continue
                fun integer(key:String):Int {
                    val value=(limit.opt(key) as? Number)?.toDouble() ?: return 0
                    return if(value.isFinite() && value==kotlin.math.floor(value) && value in 0.0..4000000.0)value.toInt() else 0
                }
                val context=integer("context");val output=integer("output")
                if(context !in 4096..4000000 || output !in 16..context)continue
                val providerInput=source.optJSONObject("modalities")?.optJSONArray("input") ?: continue
                val inputs=(0 until providerInput.length()).map {providerInput.getString(it)}
                if("text" !in inputs)continue
                val input=inputs.filter {it in setOf("text","image")}.distinct()
                val reasoning=source.opt("reasoning")==true
                val options=source.optJSONArray("reasoning_options") ?: JSONArray()
                val declared=mutableListOf<String>();var toggle=false
                for(option in options.objects())when(option.optString("type")) {
                    "toggle"->toggle=true
                    "effort"->option.optJSONArray("values")?.let {values->for(i in 0 until values.length())declared.add(values.getString(i))}
                }
                val knownLevels=setOf("off","minimal","low","medium","high","xhigh","max")
                val mapped=declared.map {if(it=="none")"off" else it}.filter {it in knownLevels}.distinct().toMutableList()
                if(toggle && "off" !in mapped)mapped.add(0,"off")
                val efforts=if(!reasoning)listOf("off") else mapped.ifEmpty {
                    fallback?.optJSONArray("efforts")?.let {a->(0 until a.length()).map {a.getString(it)}.filter {it in knownLevels}}?.takeIf {it.isNotEmpty()} ?: listOf("off")
                }
                val levelMap=JSONObject().also {map->efforts.forEach {level->
                    val previous=fallback?.optJSONObject("thinkingLevelMap")
                    map.put(level,when {level=="off"->"none";previous?.has(level)==true && !previous.isNull(level)->previous.getString(level);else->level})
                }}
                val name=source.optString("name",id)
                if(!id.matches(Regex("[A-Za-z0-9_.:-]{1,200}")) || name.length !in 1..160 || name.any(Char::isISOControl))continue
                result.put(obj("id" to id,"name" to name,"protocol" to protocol,"contextWindow" to context,"maxOutput" to output,
                    "reasoning" to reasoning,"supportsTools" to (source.opt("tool_call")==true),"input" to JSONArray(input),"providerInput" to providerInput,
                    "efforts" to JSONArray(efforts),"defaultEffort" to if("high" in efforts)"high" else efforts.first(),"thinkingLevelMap" to levelMap,
                    "compat" to (fallback?.optJSONObject("compat") ?: JSONObject()),"providerMetadataKnown" to true,
                    "metadataSource" to SOURCE,"availabilitySource" to AVAILABILITY_SOURCE,"effortSource" to if(mapped.isEmpty() && reasoning)"Pi transport compatibility" else SOURCE))
            }
            require(result.length() in 1..256) {"No supported OpenCode Go models with complete metadata"}
            return result
        }
        fun validateCached(models:JSONArray):JSONArray {
            require(models.length() in 1..256)
            val ids=mutableSetOf<String>()
            for(m in models.objects()) {
                require(ids.add(m.getString("id")) && m.getString("id").matches(Regex("[A-Za-z0-9_.:-]{1,200}")) && m.getString("protocol") in protocols)
                require(m.getInt("contextWindow") in 4096..4000000 && m.getInt("maxOutput") in 16..m.getInt("contextWindow"))
                require(m.optBoolean("providerMetadataKnown") && m.opt("supportsTools") is Boolean && m.opt("reasoning") is Boolean)
                require(m.getJSONArray("input").objectsOrStrings().let {"text" in it && it.all {v->v in setOf("text","image")}})
                require(m.getJSONArray("efforts").objectsOrStrings().let {it.isNotEmpty() && it.all {v->v in setOf("off","minimal","low","medium","high","xhigh","max")}})
            }
            return models
        }
        private fun JSONArray.objectsOrStrings()=(0 until length()).map {getString(it)}

    }
    private val prefs=context.getSharedPreferences("model-catalog",0)
    private var cachedRaw:String?=null
    private var cachedModels:JSONArray?=null
    private var usingCache=false
    /** Callers treat metadata as immutable; refreshed catalogs replace the whole array. */
    @Synchronized fun models():JSONArray {
        val raw=prefs.getString("models",null)
        if(cachedModels==null || raw!=cachedRaw) {
            cachedModels=runCatching {require(prefs.getInt("version",0)==CACHE_VERSION);validateCached(JSONArray(raw ?: error("No cache"))).also {usingCache=true}}.getOrElse {
                usingCache=false
                context.assets.open("opencode-go-models.json").bufferedReader().use {validateCached(JSONObject(it.readText()).getJSONArray("models"))}
            }
            cachedRaw=raw
        }
        return cachedModels!!
    }
    @Synchronized fun source():String {models();return if(usingCache)prefs.getString("source","OpenCode 官方模型目录缓存")!! else "内置 OpenCode 官方模型目录快照 · 2026-10-16"}
    fun refresh():JSONArray {
        val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).build()
        fun fetch(url:String,limit:Int):JSONObject=client.newCall(Request.Builder().url(url).header("Accept","application/json").build()).execute().use {response->
            require(response.isSuccessful) {"模型目录请求失败（HTTP ${response.code}）"}
            val bounded=response.body?.byteStream()?.use {dev.thor.tools.Budgets.read(it,limit)} ?: error("Empty catalog")
            require(!bounded.truncated) {"模型目录超出读取预算"}
            JSONObject(bounded.bytes.toString(Charsets.UTF_8))
        }
        val models=normalizeOfficial(fetch(SOURCE,16*1024*1024),fetch(AVAILABILITY_SOURCE,2*1024*1024),models())
        check(prefs.edit().putInt("version",CACHE_VERSION).putString("models",models.toString()).putString("source","OpenCode 官方服务列表 + Models.dev · ${java.time.LocalDate.now()}").commit())
        return models
    }
}
