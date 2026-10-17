package dev.thor.agent

/** Exact origin and main document identity; same-origin child frames do not inherit authority. */
object BridgePolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/chat/index.html"
    const val RUNTIME_PAGE = "$ORIGIN/assets/chat/runtime.html"
    const val UI_MAX_BYTES = 256 * 1024
    fun accepts(sourceOrigin: String, mainFrame: Boolean, currentUrl: String?, size: Int) =
        mainFrame && sourceOrigin == ORIGIN && currentUrl == PAGE && size in 1..16384

    fun acceptsChat(sourceOrigin:String,mainFrame:Boolean,sameInstance:Boolean,currentUrl:String?,size:Int,
        documentGeneration:String,claimedGeneration:String,documentReady:Boolean) =
        mainFrame && sameInstance && sourceOrigin==ORIGIN && currentUrl==PAGE && size in 1..UI_MAX_BYTES &&
            documentReady && documentGeneration.isNotEmpty() && documentGeneration==claimedGeneration

    private val fields=mapOf(
        "ready" to emptySet(),"new" to emptySet(),"seed" to emptySet(),"settings" to emptySet(),"pickImage" to emptySet(),
        "skillImport" to emptySet(),"voiceSettings" to emptySet(),"voice.permission" to emptySet(),"skillClose" to emptySet(),
        "select" to setOf("sessionId"),"historyMore" to setOf("sessionId"),"rename" to setOf("sessionId","title"),"archive" to setOf("sessionId","archived"),
        "delete" to setOf("sessionId"),"capability" to setOf("sessionId","capability","enabled","clientMutationId"),
        "approve" to setOf("runId","approvalId"),"reject" to setOf("runId","approvalId"),"expanded" to setOf("runId","expanded"),
        "sessionOptions" to setOf("sessionId","providerConfigId","modelId","thinkingEffort","selectedSkills"),
        "skillRead" to setOf("name","path"),"skillEnable" to setOf("name","hash","enabled"),"skillDelete" to setOf("name","hash"),
        "openLink" to setOf("url"),"voice.start" to setOf("id","sessionId"),"voice.finish" to setOf("id","sessionId"),
        "voice.cancel" to setOf("id","sessionId"),"send" to setOf("sessionId","text","scenario","clientSendId"),
        "stop" to setOf("runId"),"stopPreparing" to setOf("sessionId"))
    /** Flat UI intent only. No arbitrary RPC, network envelope, lease, URI,
     * JavaScript or tool arguments can cross this bridge. Host rechecks policy. */
    fun acceptsCommand(command:Map<String,Any?>,debug:Boolean=false):Boolean {
        val op=command["op"] as? String ?: return false
        val allowed=fields[op] ?: return false
        if(op=="seed" && !debug || command.keys.any {it!="op" && it !in allowed})return false
        for((key,value) in command)if(key!="op") {
            val valid=when(key) {
                "enabled","expanded","archived"->value is Boolean
                "selectedSkills"->value is List<*> && value.size<=128 && value.all {it is String && it.length in 1..160}
                "text"->value is String && value.length in 1..20000
                "title"->value is String && value.length in 1..512
                "url"->value is String && value.length in 1..8192
                "path"->value is String && value.length in 1..512
                "id"->value is String && value.length in 1..100
                else->value is String && value.length in 1..160
            }
            if(!valid)return false
        }
        val optional=when(op){"send"->setOf("scenario");"skillRead"->setOf("path");"sessionOptions"->setOf("providerConfigId","modelId","thinkingEffort","selectedSkills");else->emptySet()}
        return allowed.minus(optional).all {command.containsKey(it)} &&
            (op!="sessionOptions" || command.keys.any {it in optional}) &&
            (op!="capability" || command["capability"] in setOf("perception","search","screen_control","debug"))
    }
}

/** Small, fixed execution-to-view event vocabulary; no arbitrary JS callback,
 * transcript, key, transport binding or executable body is a valid payload. */
internal object RuntimeUiEventPolicy {
    private val errors=setOf("CAPABILITY_DISABLED","STALE_AUTHORIZATION","PERMISSION_MISSING","BACKEND_UNAVAILABLE","TARGET_CHANGED","UNSUPPORTED","TIMEOUT","CANCELLED","LIMIT_EXCEEDED","IO_ERROR","PROTOCOL_ERROR","PERSISTENCE_ERROR","INVALID_ARGUMENT","HASH_CONFLICT","APPROVAL_REQUIRED","COMMAND_BLOCKED","SECURE_CONTENT","DISPLAY_GONE","RATE_LIMITED")
    private fun id(value:Any?)=value is String && value.length in 1..160
    fun accepts(name:String,payload:Map<String,Any?>,bytes:Int):Boolean {
        if(bytes !in 1..2048)return false
        return when(name) {
            "ready"->payload.isEmpty()
            "preparationState"->payload.keys==setOf("sessionId","phase","elapsedMs") && id(payload["sessionId"]) &&
                payload["phase"] in setOf("checking","probing","starting","idle") && (payload["elapsedMs"] as? Number)?.let {it.toDouble()>=0 && it.toDouble()<=9007199254740991 && it.toDouble()==it.toLong().toDouble()}==true
            "sendFailure"->payload.keys==setOf("sessionId","clientSendId","errorCode") && id(payload["sessionId"]) && id(payload["clientSendId"]) && payload["errorCode"] in errors
            "capabilityFailure"->payload.keys==setOf("sessionId","capability","clientMutationId") && id(payload["sessionId"]) && id(payload["clientMutationId"]) && payload["capability"] in setOf("perception","search","screen_control","debug")
            "operationFailure"->payload.isEmpty() || payload.keys==setOf("sessionId") && id(payload["sessionId"])
            else->false
        }
    }
}
