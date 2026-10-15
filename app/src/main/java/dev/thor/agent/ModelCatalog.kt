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
        const val SOURCE="https://unpkg.com/@earendil-works/pi-ai@latest/dist/providers/data/opencode-go.json"
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
    }
    private val prefs=context.getSharedPreferences("model-catalog",0)
    private var cachedRaw:String?=null
    private var cachedModels:JSONArray?=null
    /** Callers treat metadata as immutable; refreshed catalogs replace the whole array. */
    @Synchronized fun models():JSONArray {
        val raw=prefs.getString("models",null)
        if(cachedModels==null || raw!=cachedRaw) {
            cachedModels=runCatching {JSONArray(raw ?: error("No cache")).also {require(it.length() in 1..256)}}.getOrElse {
                context.assets.open("opencode-go-models.json").bufferedReader().use {JSONObject(it.readText()).getJSONArray("models")}
            }
            cachedRaw=raw
        }
        return cachedModels!!
    }
    fun source():String=prefs.getString("source","内置 Pi 模型资料 · 2026-10-15")!!
    fun refresh():JSONArray {
        val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).build()
        val models=client.newCall(Request.Builder().url(SOURCE).build()).execute().use {response ->
            require(response.isSuccessful);val body=response.body ?: error("Empty catalog")
            val bounded=body.byteStream().use {dev.thor.tools.Budgets.read(it,4*1024*1024)};require(!bounded.truncated);val bytes=bounded.bytes
            normalize(JSONObject(bytes.toString(Charsets.UTF_8)))
        }
        check(prefs.edit().putString("models",models.toString()).putString("source","Pi 在线模型资料 · ${java.time.LocalDate.now()}").commit())
        return models
    }
}
