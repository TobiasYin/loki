package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Presentation projection only. Durable history and model context remain in Native. */
internal fun chatSnapshot(state:JSONObject,activeRunId:String?,indexedEvents:List<JSONObject>?=null):JSONObject {
    val selected=state.optString("selected")
    fun visible(row:JSONObject)=row.optString("sessionId")==selected || activeRunId!=null && row.optString("runId",row.optString("id"))==activeRunId
    fun fields(row:JSONObject,excluded:Set<String>)=JSONObject().apply {row.keys().forEach {key->if(key !in excluded)put(key,row.get(key))}}
    val projected=JSONObject()
    state.keys().forEach {key->if(key !in setOf("runs","messages","events","attachments"))projected.put(key,state.get(key))}
    projected.put("messages",JSONArray(state.getJSONArray("messages").objects().filter(::visible)))
    // UI needs bounded recent thought/text windows and the latest parsed call, not every fragment.
    // Keep the durable append-only ledger untouched for model history, audit and recovery.
    val fold=PresentationEventFold()
    for(original in (indexedEvents ?: state.getJSONArray("events").objects()).filter(::visible))fold.append(original)
    projected.put("events",JSONArray(fold.snapshot().sortedBy {it.optLong("sequence")}))
    projected.put("runs",JSONArray(state.getJSONArray("runs").objects().filter(::visible).map {fields(it,setOf("turn","contexts","runtime"))}))
    projected.put("attachments",JSONArray(state.getJSONArray("attachments").objects().filter(::visible).map {fields(it,setOf("data","path"))}))
    // Isolate the UI snapshot before crossing threads; never expose mutable Native rows.
    return JSONObject(projected.toString())
}
