package dev.thor.agent

import java.net.URI

/** An official provider label at a custom endpoint remains a custom connection.
 * The catalog is configuration evidence, never an assertion of a passed network test. */
internal object OfficialProfilePolicy {
    fun protocolBase(provider:String,protocol:String,configured:String):String {
        val base=configured.trimEnd('/')
        // Anthropic SDK itself appends /v1/messages. Go's shared configured
        // endpoint includes /v1 for the OpenAI transports, so remove it only here.
        return if(provider=="opencode-go" && protocol=="anthropic-messages")base.removeSuffix("/v1") else base
    }
    fun metadataKnown(provider:String,baseUrl:String,declared:Boolean):Boolean {
        if(!declared)return false
        return runCatching {
            val u=URI(baseUrl);val path=u.path.orEmpty().trimEnd('/')
            if(u.scheme!="https" || u.userInfo!=null || u.query!=null || u.fragment!=null || u.port !in setOf(-1,443))false
            else when(provider) {
                "opencode-go"->u.host=="opencode.ai" && (path=="/zen/go/v1" || path.matches(Regex("/inference/go/(openai|anthropic)/v1")))
                VolcAgentPlan.KIND->u.host=="ark.cn-beijing.volces.com" && path=="/api/plan/v3"
                "deepseek"->u.host=="api.deepseek.com" && path in setOf("","/v1")
                else->false
            }
        }.getOrDefault(false)
    }
}
