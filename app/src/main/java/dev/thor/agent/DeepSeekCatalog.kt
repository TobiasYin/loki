package dev.thor.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Official public metadata. API keys stay Native and are never cached with the model list. */
class DeepSeekCatalog(context:Context) {
    companion object {
        const val BASE_URL="https://api.deepseek.com"
        const val SOURCE="$BASE_URL/models"
        const val VERIFIED_AT="2026-10-16"
        private val levels=setOf("low","high","max")
        /** Existing Go/custom editing remains compatible; an official-service boundary needs its own key. */
        fun canReuseCredential(savedProvider:String,currentProvider:String)=savedProvider==currentProvider ||
            savedProvider!="deepseek" && currentProvider!="deepseek"
        private fun profile(id:String,name:String,images:Boolean)=JSONObject()
            .put("id",id).put("name",name).put("protocol","openai-responses")
            .put("contextWindow",1048576).put("maxOutput",393216).put("reasoning",true)
            .put("providerMetadataKnown",true).put("supportsTools",true)
            .put("metadataSource","https://api-docs.deepseek.com/api/list-models/ · $VERIFIED_AT")
            .put("input",JSONArray(if(images)listOf("text","image") else listOf("text")))
            .put("efforts",JSONArray(listOf("off","low","high","max"))).put("defaultEffort","high")
            .put("thinkingLevelMap",JSONObject().put("off","none").put("low","low").put("high","high").put("max","max"))
            // DeepSeek treats developer as user: retain system-level instructions as system.
            .put("compat",JSONObject().put("supportsDeveloperRole",false).put("supportsLongCacheRetention",false))
        fun bundled():JSONArray=JSONArray().put(profile("deepseek-flash","DeepSeek-V4.1-Flash",true))
            .put(profile("deepseek-v4-pro","DeepSeek-V4-Pro",false))

        /** /models metadata is authoritative; legacy id-only rows use only known documented profiles. */
        fun normalize(raw:JSONObject):JSONArray {
            require(raw.optString("object")=="list")
            val rows=raw.getJSONArray("data");require(rows.length() in 1..256)
            val known=bundled().objects().associateBy {it.getString("id")}
            val result=JSONArray();val seen=mutableSetOf<String>()
            for(row in rows.objects()) {
                val id=row.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_.:-]{1,200}")) && seen.add(id))
                val fallback=known[id]
                // Unknown id-only models have no trustworthy context/modalities/effort profile.
                if(fallback==null && listOf("context_window","max_output_tokens","input_modalities","effort").any {!row.has(it)})continue
                require(!row.has("input_modalities") || row.opt("input_modalities") is JSONArray)
                require(!row.has("effort") || row.opt("effort") is JSONObject)
                val model=JSONObject(fallback?.toString() ?: profile(id,id,false).toString())
                // The documented ids support function calls. Future discovery entries do not
                // inherit that claim merely because they advertise a context and modalities.
                if(fallback==null)model.put("supportsTools",false)
                model.put("metadataSource",SOURCE)
                val context=row.optInt("context_window",model.getInt("contextWindow"))
                val output=row.optInt("max_output_tokens",model.getInt("maxOutput"))
                require(context in 4096..4000000 && output in 16 until context)
                model.put("contextWindow",context).put("maxOutput",output)
                if(row.has("name"))model.put("name",row.getString("name").also {require(it.length in 1..120 && it.none(Char::isISOControl))})
                row.optJSONArray("input_modalities")?.let {input->
                    val values=(0 until input.length()).map {input.getString(it)}
                    require("text" in values && values.all {it in setOf("text","image")})
                    model.put("input",JSONArray(values.distinct()))
                }
                row.optJSONObject("effort")?.let {effort->
                    val input=effort.getJSONArray("supported_levels")
                    val supported=(0 until input.length()).map {input.getString(it)}.filter {it in levels}.distinct()
                    require(supported.isNotEmpty())
                    val default=effort.optString("default_level",if("high" in supported)"high" else supported.first())
                    require(default in supported)
                    model.put("efforts",JSONArray(listOf("off")+supported)).put("defaultEffort",default)
                    model.put("thinkingLevelMap",JSONObject().put("off","none").also {map->supported.forEach {map.put(it,it)}})
                }
                result.put(model)
            }
            require(result.length()>0)
            return result
        }
        internal fun fetch(credential:String,client:OkHttpClient,endpoint:String=SOURCE):JSONArray {
            require(credential.isNotBlank() && credential.length<=8192 && credential.none {it=='\r' || it=='\n'})
            val request=Request.Builder().url(endpoint).header("Authorization","Bearer $credential").header("Accept","application/json").build()
            return client.newCall(request).execute().use {response->
                require(response.isSuccessful) {"模型列表请求失败（HTTP ${response.code}）"}
                val bounded=response.body?.byteStream()?.use {dev.thor.tools.Budgets.read(it,dev.thor.tools.Budgets.CONFIG)} ?: error("模型列表为空")
                require(!bounded.truncated)
                normalize(JSONObject(bounded.bytes.toString(Charsets.UTF_8)))
            }
        }
    }
    private val prefs=context.getSharedPreferences("deepseek-model-catalog",0)
    fun models():JSONArray=runCatching {JSONArray(prefs.getString("models",null) ?: error("No cache")).also {require(it.length() in 1..256)}}.getOrElse {bundled()}
    fun source():String=prefs.getString("source", "DeepSeek 官方文档 · $VERIFIED_AT（可用 Key 刷新）")!!
    fun refresh(credential:String):JSONArray {
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(25,TimeUnit.SECONDS).build()
        val models=fetch(credential,client)
        check(prefs.edit().putString("models",models.toString()).putString("source","DeepSeek 官方 /models · ${java.time.LocalDate.now()}").commit())
        return models
    }
}
