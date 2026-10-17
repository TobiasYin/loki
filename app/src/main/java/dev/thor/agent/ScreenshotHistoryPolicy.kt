package dev.thor.agent

import org.json.JSONObject

/** Screenshot bytes are historical conversation data, never an input authorization.
 * Live frame validity remains owned by Snapshots and the Native tool authority. */
internal object ScreenshotHistoryPolicy {
    const val HISTORY_EXPIRES_AT=9_007_199_254_740_991L // JavaScript's largest exact integer.
    fun captureExpiry(observedAt:Long,configurationProbe:Boolean):Long =
        if(configurationProbe)observedAt+600_000L else HISTORY_EXPIRES_AT

    /** Normalize a read-only copy of an owned historical capture. Never migrate
     * stored rows or extend user uploads, synthetic probe images, or other data. */
    fun replay(attachment:JSONObject?,sessionId:String,findRun:(String)->JSONObject?):JSONObject? {
        if(attachment==null || sessionId.isBlank() || attachment.optString("sessionId")!=sessionId || attachment.optString("mimeType")!="image/jpeg")return attachment
        val source=attachment.optJSONArray("source") ?: return attachment
        if(source.length()!=1 || source.optString(0)!="screen_perception")return attachment
        val runId=attachment.optString("runId").takeIf {it.isNotBlank() && it!="null"} ?: return attachment
        val owner=findRun(runId) ?: return attachment
        if(owner.optString("id")!=runId || owner.optString("sessionId")!=sessionId || owner.optBoolean("probe"))return attachment
        // A shallow copy shares immutable strings (including image data), avoiding
        // a second base64 serialization while leaving the persisted row untouched.
        return JSONObject(attachment,attachment.keys().asSequence().toList().toTypedArray()).put("expiresAt",HISTORY_EXPIRES_AT)
    }
}
