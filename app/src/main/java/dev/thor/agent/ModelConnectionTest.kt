package dev.thor.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Settings-only draft test. Credentials and response bodies never cross the WebView bridge. */
object ModelConnectionTest {
    data class Draft(val baseUrl:String,val protocol:String,val model:String,val credential:String,
                     val headerName:String="",val headerTemplate:String="",val reasoningEffort:String?=null) {
        fun validate() {
            val uri=URI(baseUrl)
            require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null) {"请填写有效的模型服务地址"}
            require(reasoningEffort==null || reasoningEffort=="low") {"连接测试的思考配置无效"}
            require(protocol in ModelCatalog.protocols && model.isNotBlank() && model.length<=200) {"请选择模型和接口类型"}
            require(credential.isNotBlank() && credential.length<=8192 && '\n' !in credential && '\r' !in credential) {"请填写 API Key，或保留已保存的 Key"}
            require(!isGoEndpoint(baseUrl) || !headerName.equals("x-opencode-session",true)) {"OpenCode 会话头由应用生成，不能作为凭据头"}
            require(headerName.isBlank() || headerName.matches(Regex("[A-Za-z0-9-]+")) && headerName.lowercase() !in setOf("host","content-length","transfer-encoding","connection","content-type")) {"凭据头名称无效"}
            require(headerTemplate.isBlank() || headerTemplate.split("{{credential}}").size==2 && '\r' !in headerTemplate && '\n' !in headerTemplate) {"凭据格式必须包含一次 {{credential}}"}
        }
        /** Only use a setting which this exact model/protocol profile explicitly supports. */
        fun withProfile(profile:JSONObject?):Draft {
            if(profile==null || profile.optString("id")!=model || profile.optString("protocol")!=protocol || !profile.optBoolean("reasoning"))return this
            val efforts=profile.optJSONArray("efforts") ?: return this
            return if((0 until efforts.length()).any {efforts.optString(it)=="low"})copy(reasoningEffort="low") else this
        }
        fun endpoint():String {
            val uri=URI(baseUrl.trimEnd('/'))
            val root="${uri.scheme}://${uri.rawAuthority}"
            val path=uri.rawPath.orEmpty().trimEnd('/')
            return root+when(protocol) {"openai-responses"->"$path/responses";"openai-completions"->"$path/chat/completions";else->"${path.removeSuffix("/v1")}/v1/messages"}
        }
    }
    data class Result(val success:Boolean,val message:String,val elapsedMs:Long)
    fun run(draft:Draft,client:OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS).build()):Result {
        val start=System.nanoTime()
        fun result(ok:Boolean,text:String)=Result(ok,text,(System.nanoTime()-start)/1_000_000)
        var status:Int?=null
        var receivedEvents=0
        try {
            draft.validate()
            val request=request(draft)
            client.newCall(request).execute().use {response->
                status=response.code
                if(!response.isSuccessful) {
                    val diagnostic=runCatching {
                        val body=response.body ?: return@runCatching ""
                        val bounded=dev.thor.tools.Budgets.read(body.byteStream(),16384)
                        if(bounded.truncated)"" else safeError(draft,JSONObject(bounded.bytes.toString(Charsets.UTF_8)))
                    }.getOrDefault("")
                    val summary=when(response.code) {
                    401,403->"认证失败（HTTP ${response.code}），请检查 API Key 和订阅权限"
                    404->"接口或模型不存在（HTTP 404），请检查地址、接口类型和模型 ID"
                    429->"请求限流或额度不足（HTTP 429），请稍后重试"
                    in 300..399->"服务返回重定向；为保护 Key，未向其他地址转发"
                    else->"服务请求失败（HTTP ${response.code}），请检查服务状态与模型配置"
                    }
                    return result(false,summary+if(diagnostic.isBlank())"" else "；$diagnostic")
                }
                val source=response.body?.source() ?: return result(false,"服务未返回内容")
                var bytes=0
                val frames=SseFrames()
                while(!source.exhausted()) {
                    val line=source.readUtf8LineStrict(256*1024) // Bound even a malformed SSE line.
                    bytes+=line.toByteArray(Charsets.UTF_8).size
                    if(bytes>1024*1024)return result(false,"响应超出连接测试大小上限")
                    val event=frames.accept(line) ?: continue
                    receivedEvents++
                    if(isFailure(event))return result(false,"服务返回模型错误，请检查模型与接口配置"+safeError(draft,event).let {if(it.isEmpty())"" else "；$it"})
                    if(hasText(draft.protocol,event))return result(true,"文本流式连接可用；图片和工具能力需另行完整检查")
                }
                return result(false,"连接成功但没有收到文本增量，请检查接口类型或输出预算")
            }
        } catch(error:Exception) {
            return result(false,if(error is java.io.InterruptedIOException)"连接测试等待响应超时（HTTP ${status ?: "未收到响应头"}，已接收 $receivedEvents 个事件）；草稿未保存"
                else "连接测试失败：检查地址、密钥配置、网络或服务响应；草稿未保存")
        }
    }
    internal fun isGoEndpoint(base:String):Boolean=runCatching {val uri=URI(base);uri.scheme=="https" && uri.host=="opencode.ai" && uri.port in setOf(-1,443) && uri.path.trimEnd('/')=="/zen/go/v1" && uri.userInfo==null && uri.query==null && uri.fragment==null}.getOrDefault(false)
    /** Mirrors Pi Responses request fields and Native Go session affinity, without product state. */
    internal fun request(draft:Draft):Request {
        draft.validate()
        val messages=JSONArray().put(JSONObject().put("role","user").put("content","Reply only THOR_CONNECTION_OK."))
        val body=JSONObject().put("model",draft.model).put("stream",true)
        when(draft.protocol) {
            "openai-responses"->body.put("store",false).put("input",JSONArray().put(JSONObject().put("role","user").put("content",JSONArray().put(JSONObject().put("type","input_text").put("text","Reply only THOR_CONNECTION_OK."))))).put("max_output_tokens",2048)
            "anthropic-messages"->body.put("messages",messages).put("max_tokens",2048)
            else->body.put("messages",messages).put("max_tokens",2048)
        }
        if(draft.reasoningEffort!=null)when(draft.protocol) {
            "openai-responses"->body.put("reasoning",JSONObject().put("effort",draft.reasoningEffort).put("summary","auto")).put("include",JSONArray(listOf("reasoning.encrypted_content")))
            "openai-completions"->body.put("reasoning_effort",draft.reasoningEffort)
            // Anthropic adaptive vs budget thinking differ by model; leave its default unchanged.
        }
        val authName=draft.headerName.ifBlank {if(draft.protocol=="anthropic-messages")"x-api-key" else "Authorization"}
        val template=draft.headerTemplate.ifBlank {if(draft.protocol=="anthropic-messages")"{{credential}}" else "Bearer {{credential}}"}
        val request=Request.Builder().url(draft.endpoint()).header(authName,template.replace("{{credential}}",draft.credential))
            .header("Accept","text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType()))
        if(draft.protocol=="anthropic-messages")request.header("anthropic-version","2023-06-01")
        if(isGoEndpoint(draft.baseUrl))request.header("x-opencode-session",java.util.UUID.randomUUID().toString())
        return request.build()
    }
    /** Only explicit diagnostic fields survive; never return raw response bodies or headers. */
    internal fun safeError(draft:Draft,root:JSONObject):String {
        val source=root.optJSONObject("error") ?: root.optJSONObject("response")?.optJSONObject("error") ?: root
        val auth=draft.headerTemplate.ifBlank {if(draft.protocol=="anthropic-messages")"{{credential}}" else "Bearer {{credential}}"}.replace("{{credential}}",draft.credential)
        val secrets=listOf(draft.credential,auth,java.net.URLEncoder.encode(draft.credential,"UTF-8"),
            java.util.Base64.getEncoder().encodeToString(draft.credential.toByteArray()),java.util.Base64.getUrlEncoder().encodeToString(draft.credential.toByteArray()))+
            if(draft.credential.length>=12)listOf(draft.credential.take(8),draft.credential.takeLast(8)) else emptyList()
        fun clean(value:String,limit:Int):String {
            fun scrub(raw:String):String {var safe=raw;for(secret in secrets.filter {it.isNotEmpty()}.sortedByDescending {it.length})safe=safe.replace(secret,"[已隐藏]",ignoreCase=true);return safe}
            var text=scrub(value)
            // Redact before and after decoding, including secrets containing '+' or '%'.
            repeat(3){text=scrub(runCatching {java.net.URLDecoder.decode(text,"UTF-8")}.getOrDefault(text))}
            return text.replace(Regex("[\\p{Cntrl}\\p{Cf}]+")," ").replace(Regex("\\s+")," ").trim().take(limit)
        }
        val parts=mutableListOf<String>()
        for(field in listOf("code","type","param","message")) {
            val value=source.opt(field)
            if(value !is String && value !is Number)continue
            val text=clean(value.toString(),if(field=="message")220 else 80)
            if(text.isNotBlank())parts.add("$field=$text")
        }
        if(parts.isEmpty() && root.opt("error") is String)parts.add("message="+clean(root.getString("error"),220))
        return parts.joinToString(" · ").take(400)
    }
    /** Standard SSE frames may provide the type in event: and JSON across several data: lines. */
    internal class SseFrames {
        private val data=StringBuilder()
        private var type=""
        fun accept(line:String):JSONObject? {
            if(line.isEmpty()) {
                val raw=data.toString();data.setLength(0)
                val event=runCatching {JSONObject(raw)}.getOrNull()
                if(event!=null && !event.has("type") && type.matches(Regex("[A-Za-z0-9_.-]{1,80}")))event.put("type",type)
                type="";return event
            }
            if(line.startsWith(":"))return null
            val colon=line.indexOf(':');if(colon<0)return null
            val field=line.substring(0,colon);val value=line.substring(colon+1).removePrefix(" ")
            when(field){"event"->type=value;"data"->{if(data.isNotEmpty())data.append('\n');data.append(value)}}
            return null
        }
    }
    internal fun isFailure(event:JSONObject)=event.optString("type") in setOf("error","response.failed") || event.has("error") && !event.isNull("error")
    internal fun hasText(protocol:String,event:JSONObject):Boolean=when(protocol) {
        "openai-responses"->event.optString("type")=="response.output_text.delta" && event.optString("delta").isNotBlank()
        "anthropic-messages"->event.optString("type")=="content_block_delta" && event.optJSONObject("delta")?.let {it.optString("type")=="text_delta" && it.optString("text").isNotBlank()}==true
        else->event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")?.isNotBlank()==true
    }
}
