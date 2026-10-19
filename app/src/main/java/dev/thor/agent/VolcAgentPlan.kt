package dev.thor.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One Native credential, resolved independently for each fixed official service. */
object VolcAgentPlan {
    const val KIND="volcengine-agent-plan"
    const val BASE_URL="https://ark.cn-beijing.volces.com/api/plan/v3"
    const val ASR_ENDPOINT="wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async"
    const val ASR_RESOURCE="volc.seedasr.sauc.duration"
    const val SEARCH_ENDPOINT="https://open.feedcoopapi.com/search_api/web_search"
    const val SEARCH_NAME="豆包搜索"
    fun selected(context:Context):JSONObject? {
        val store=ProviderStore(context)
        return store.defaultId()?.let(store::get)?.takeIf {it.optBoolean("enabled") && it.optString("kind")==KIND && it.optString("url")==BASE_URL}
    }
    fun credential(context:Context):String?=selected(context)?.let {row->
        runCatching {ProviderStore(context).credential(row.getString("id"))}.getOrNull()?.takeIf {it.isNotBlank()}
    }
}

/** Public official configuration discovery: the management API needs AK/SK, and
 * the Plan data plane has no /models. No credential is sent to the documentation host. */
class VolcAgentPlanCatalog(private val context:Context) {
    companion object {
        const val SOURCE="https://ark.volcengine.com/region:cn-beijing/docs/agent-plan-personal-openclaw.md"
        const val VERIFIED_AT="2026-10-19"
        fun normalize(rows:JSONArray):JSONArray {
            require(rows.length() in 1..256)
            val seen=mutableSetOf<String>()
            return JSONArray().also {out->for(row in rows.objects()) {
                val id=row.getString("id");val name=row.getString("name")
                require(id.matches(Regex("[A-Za-z0-9_.:-]{1,200}")) && seen.add(id))
                require(name.length in 1..160 && name.none(Char::isISOControl))
                val window=row.getInt("contextWindow");val output=row.getInt("maxTokens")
                require(window in 4096..4000000 && output in 16..window)
                val input=row.getJSONArray("input");val modalities=(0 until input.length()).map {input.getString(it)}
                require("text" in modalities && modalities.all {it in setOf("text","image")})
                out.put(obj("id" to id,"name" to name,"protocol" to "openai-responses","contextWindow" to window,"maxOutput" to output,
                    "input" to JSONArray(modalities.distinct()),"supportsTools" to true,"providerMetadataKnown" to true,
                    "reasoning" to true,"efforts" to JSONArray(listOf("low","medium","high")),"defaultEffort" to "high",
                    "thinkingLevelMap" to obj("low" to "low","medium" to "medium","high" to "high"),
                    "compat" to obj("supportsDeveloperRole" to false,"supportsLongCacheRetention" to false),
                    "metadataSource" to SOURCE,"availabilitySource" to "官方目录；账户权限以连接测试为准"))
            }}
        }
        fun discover(markdown:String):JSONArray {
            require(markdown.toByteArray(Charsets.UTF_8).size<=1024*1024)
            for(match in Regex("```(?:json|JSON)\\s*\\n(.*?)```",RegexOption.DOT_MATCHES_ALL).findAll(markdown)) {
                val provider=runCatching {JSONObject(match.groupValues[1]).getJSONObject("models").getJSONObject("providers").getJSONObject(VolcAgentPlan.KIND)}.getOrNull() ?: continue
                if(provider.optString("baseUrl")!=VolcAgentPlan.BASE_URL || provider.optString("api")!="openai-responses")continue
                return normalize(provider.getJSONArray("models"))
            }
            error("官方目录格式已变化；保留现有目录")
        }
    }
    private val prefs=context.getSharedPreferences("volc-agent-plan-catalog",0)
    private fun bundled()=context.assets.open("volc-agent-plan-models.json").bufferedReader().use {JSONArray(it.readText())}
    fun models():JSONArray=normalize(runCatching {JSONArray(prefs.getString("rows",null) ?: error("No cache")).also {normalize(it)}}.getOrElse {bundled()})
    fun source():String=prefs.getString("source","火山官方目录 · $VERIFIED_AT；非账户可用性验证")!!
    fun refresh():JSONArray {
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(25,TimeUnit.SECONDS).build()
        val markdown=client.newCall(Request.Builder().url(SOURCE).build()).execute().use {response->
            require(response.isSuccessful) {"官方目录请求失败（HTTP ${response.code}）"}
            val body=requireNotNull(response.body)
            val bounded=dev.thor.tools.Budgets.read(body.byteStream(),1024*1024);require(!bounded.truncated)
            bounded.bytes.toString(Charsets.UTF_8)
        }
        val models=discover(markdown)
        // Cache only the small, validated public fields; never source text or credentials.
        val rows=JSONArray(models.objects().map {m->obj("id" to m.getString("id"),"name" to m.getString("name"),"contextWindow" to m.getInt("contextWindow"),"maxTokens" to m.getInt("maxOutput"),"input" to m.getJSONArray("input"))})
        check(prefs.edit().putString("rows",rows.toString()).putString("source","火山官方目录 · ${java.time.LocalDate.now()}；账户权限以连接测试为准").commit())
        return models
    }
}
