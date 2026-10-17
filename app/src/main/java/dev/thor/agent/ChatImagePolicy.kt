package dev.thor.agent

import org.json.JSONObject

internal data class ChatImageRequest(val sessionId:String,val runId:String,val attachmentId:String)
internal data class ChatImage(val mimeType:String,val bytes:ByteArray)

/** Read-only historical pixels. This route never accepts a file path, network
 * address, live frame, execution lease or device operation from the chat page. */
internal object ChatImagePolicy {
    private val path=Regex("^/chat-images/([A-Za-z0-9_-]{1,160})/([A-Za-z0-9_-]{1,160})/([A-Za-z0-9_-]{1,160})$")
    fun parse(value:String):ChatImageRequest?=path.matchEntire(value)?.groupValues?.let {ChatImageRequest(it[1],it[2],it[3])}
    fun permits(request:ChatImageRequest,selectedSession:String,attachment:JSONObject?,owner:JSONObject?,now:Long):Boolean {
        if(request.sessionId!=selectedSession || attachment==null || owner==null)return false
        if(owner.optString("id")!=request.runId || owner.optString("sessionId")!=request.sessionId || owner.optBoolean("probe"))return false
        if(attachment.optString("id")!=request.attachmentId || attachment.optString("sessionId")!=request.sessionId || attachment.optString("runId")!=request.runId)return false
        if(attachment.optString("mimeType") !in setOf("image/jpeg","image/png","image/webp") || attachment.optLong("expiresAt")<=now || attachment.optLong("size") !in 1..2_097_152L)return false
        val source=attachment.optJSONArray("source") ?: return false
        return source.length()==1 && source.optString(0)=="screen_perception"
    }
}
