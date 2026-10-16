package dev.thor.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Tests the search settings draft without saving or exposing its credential. */
object SearchConnectionTest {
    fun run(url:String,key:String):ModelConnectionTest.Result {
        val started=System.nanoTime()
        fun result(ok:Boolean,text:String)=ModelConnectionTest.Result(ok,text,(System.nanoTime()-started)/1_000_000)
        return try {
            val uri=URI(url)
            require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null)
            require(key.isNotBlank() && key.length<=8192 && '\r' !in key && '\n' !in key)
            val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(30,TimeUnit.SECONDS).build()
            val request=Request.Builder().url("$url?q=Android&count=1").header("X-Subscription-Token",key).header("Accept","application/json").build()
            client.newCall(request).execute().use {response->
                if(!response.isSuccessful)return result(false,when(response.code){401,403->"搜索认证失败（HTTP ${response.code}），请检查 Key 权限";429->"搜索限流或额度不足（HTTP 429）";in 300..399->"搜索返回重定向；未向其他地址转发 Key";else->"搜索服务请求失败（HTTP ${response.code}）"})
                val body=response.body ?: return result(false,"搜索服务未返回内容")
                val bounded=dev.thor.tools.Budgets.read(body.byteStream(),256*1024)
                if(bounded.truncated)return result(false,"搜索响应超出测试大小上限")
                val data=JSONObject(bounded.bytes.toString(Charsets.UTF_8))
                if(data.optJSONObject("web")?.optJSONArray("results")!=null)result(true,"Brave 搜索连接可用，当前草稿未保存")
                else result(false,"连接成功但返回格式不是 Brave 搜索结果，请检查地址")
            }
        }catch(_:Exception){result(false,"搜索测试失败：请检查当前地址、API Key 或网络；草稿未保存")}
    }
}
