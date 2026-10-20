package dev.thor.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Zen availability intersected with explicit zero-price, supported Models.dev profiles.
 * No API key is stored here or sent to the metadata service. */
class OpenCodeZenCatalog(private val context:Context) {
    companion object {
        const val KIND="opencode-zen"
        const val BASE_URL="https://opencode.ai/zen/v1"
        const val SOURCE="$BASE_URL/models"
        const val VERIFIED_AT="2026-10-20"
        fun normalize(raw:JSONObject,available:JSONObject):JSONArray {
            require(available.getString("object")=="list")
            val rows=available.getJSONArray("data");require(rows.length()<=256)
            val provider=raw.getJSONObject("opencode")
            val free=JSONObject()
            for((id,m) in provider.getJSONObject("models").let {models->models.keys().asSequence().map {it to models.getJSONObject(it)}.toList()}) {
                val cost=m.optJSONObject("cost") ?: continue
                fun zero(key:String)=(cost.opt(key) as? Number)?.toDouble()==0.0
                if(!zero("input") || !zero("output") || listOf("cache_read","cache_write").any {cost.has(it) && !zero(it)})continue
                if(m.optString("status")=="deprecated" || m.opt("tool_call")!=true)continue
                val output=m.optJSONObject("modalities")?.optJSONArray("output") ?: continue
                if((0 until output.length()).none {output.optString(it)=="text"})continue
                free.put(id,m)
            }
            val filtered=JSONObject().put("opencode",JSONObject(provider.toString()).put("models",free))
            return ModelCatalog.normalizeOfficial(filtered,available,JSONArray(),"opencode",SOURCE,allowEmpty=true).also {models->
                models.objects().forEach {it.put("free",true);configureTransport(it)}
            }
        }
        private fun configureTransport(model:JSONObject) {
            // Zen's OpenAI-compatible route uses max_tokens, including when
            // loading an older metadata cache created before this contract.
            if(model.optString("protocol")=="openai-completions")model.put("compat",(model.optJSONObject("compat") ?: JSONObject()).put("maxTokensField","max_tokens"))
        }
        fun validate(models:JSONArray):JSONArray {
            require(models.length()<=256)
            if(models.length()>0)ModelCatalog.validateCached(models)
            models.objects().forEach {require(it.opt("free")==true && it.optString("availabilitySource")==SOURCE);configureTransport(it)}
            return models
        }
        internal fun fetch(credential:String,client:OkHttpClient,availabilityUrl:String=SOURCE,metadataUrl:String=ModelCatalog.SOURCE):JSONArray {
            require(credential.isNotBlank() && credential.length<=8192 && credential.none {it=='\r' || it=='\n'})
            // Redirects are disabled even for injected clients; never forward a Key.
            val boundedClient=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            fun get(url:String,limit:Int,key:String?=null):JSONObject {
                val request=Request.Builder().url(url).header("Accept","application/json").apply {if(key!=null)header("Authorization","Bearer $key")}.build()
                return boundedClient.newCall(request).execute().use {response->
                    require(response.isSuccessful) {"免费模型列表请求失败（HTTP ${response.code}）"}
                    response.body?.byteStream()?.use {ModelCatalog.readCatalog(it,limit)} ?: error("模型目录为空")
                }
            }
            val available=get(availabilityUrl,2*1024*1024,credential)
            return normalize(get(metadataUrl,16*1024*1024),available)
        }
    }
    private val prefs=context.getSharedPreferences("opencode-zen-model-catalog",0)
    fun models():JSONArray=runCatching {validate(JSONArray(prefs.getString("models",null) ?: error("No cache")))}.getOrElse {
        context.assets.open("opencode-zen-models.json").bufferedReader().use {
            val snapshot=JSONObject(it.readText());normalize(snapshot.getJSONObject("metadata"),snapshot.getJSONObject("available"))
        }
    }
    fun source():String=prefs.getString("source","内置 OpenCode Zen 免费模型快照 · $VERIFIED_AT")!!
    private val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).build()
    fun refresh(credential:String):JSONArray {
        val models=fetch(credential,client)
        check(prefs.edit().putString("models",models.toString()).putString("source","OpenCode Zen 免费模型 + Models.dev · ${java.time.LocalDate.now()}").commit())
        return models
    }
}
