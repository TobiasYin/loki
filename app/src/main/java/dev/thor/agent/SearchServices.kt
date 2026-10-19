package dev.thor.agent

import android.content.Context
import java.net.URI

/** Credentials are resolved only in Native. Official DeepSeek records share their model credential. */
object SearchServices {
    val names=listOf("自动选择","DeepSeek","Tavily","Brave","关闭")
    fun endpoint(backend:String)=when(backend) {
        "DeepSeek"->"https://api.deepseek.com/v1/tools/search"
        "Tavily"->"https://api.tavily.com/search"
        else->"https://api.search.brave.com/res/v1/web/search"
    }
    data class Service(val backend:String,val url:String,val key:String) { override fun toString()="Service($backend, credential=[REDACTED])" }
    fun choose(selected:String,available:List<Service>):Service? = when(selected) {
        "关闭"->null
        "自动选择",""->available.firstOrNull {it.backend=="DeepSeek"} ?: available.firstOrNull()
        else->available.firstOrNull {it.backend.equals(selected,true)}
    }
    fun available(context:Context):List<Service> {
        val prefs=context.getSharedPreferences("profiles",0);val vault=CredentialVault(context);val store=ProviderStore(context)
        val deepseek=store.list().sortedBy {if(it.optString("id")==store.defaultId())0 else 1}.firstNotNullOfOrNull {row->
            if(row.optBoolean("enabled") && isOfficialDeepSeek(row.optString("url")))runCatching {store.credential(row.getString("id"))}.getOrNull()?.takeIf {it.isNotBlank()} else null
        }
        return listOfNotNull(
            deepseek?.let {Service("DeepSeek",endpoint("DeepSeek"),it)},
            runCatching {vault.readNative("search.tavily")}.getOrNull()?.takeIf {it.isNotBlank()}?.let {Service("Tavily",endpoint("Tavily"),it)},
            runCatching {vault.readNative("search")}.getOrNull()?.takeIf {it.isNotBlank()}?.let {Service("Brave",prefs.getString("searchUrl",endpoint("Brave"))!!.ifBlank {endpoint("Brave")},it)}
        )
    }
    fun resolve(context:Context):Service?=choose(context.getSharedPreferences("profiles",0).getString("searchBackend", "自动选择").orEmpty(),available(context))
    fun isOfficialDeepSeek(url:String)=runCatching {val u=URI(url);u.scheme=="https" && u.host=="api.deepseek.com" && u.port==-1 && u.userInfo==null && u.rawQuery==null && u.fragment==null && u.path.trimEnd('/') in setOf("","/v1")}.getOrDefault(false)
}
