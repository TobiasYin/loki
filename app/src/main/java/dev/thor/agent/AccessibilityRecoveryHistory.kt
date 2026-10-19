package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Native service maintenance has its own bounded history. It must never consume
 * a sequence number from the runtime's independently acknowledged event ledger. */
internal object AccessibilityRecoveryHistory {
    private val phases=setOf("connected","recovering","waiting_connection","failed","manual_required","restart_required","timed_out")
    fun append(run:JSONObject,event:JSONObject):Boolean {
        if(event.optString("phase") !in phases)return false
        val events=run.optJSONArray("serviceRecoveryEvents") ?: JSONArray()
        if(events.objects().any {it.optLong("id")==event.optLong("id")})return false
        val safe=JSONObject()
        for(key in listOf("id","wallTime","phase","errorCode","frameworkPhase"))if(event.has(key))safe.put(key,event.get(key))
        run.put("serviceRecoveryEvents",JSONArray((events.objects()+safe).takeLast(64)))
        return true
    }
}
