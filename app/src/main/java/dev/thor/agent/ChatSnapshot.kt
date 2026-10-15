package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Presentation projection only. Durable history and model context remain in Native. */
internal fun chatSnapshot(state:JSONObject,activeRunId:String?):JSONObject {
    val selected=state.optString("selected")
    fun visible(row:JSONObject)=row.optString("sessionId")==selected || activeRunId!=null && row.optString("runId",row.optString("id"))==activeRunId
    fun fields(row:JSONObject,excluded:Set<String>)=JSONObject().apply {row.keys().forEach {key->if(key !in excluded)put(key,row.get(key))}}
    val projected=JSONObject()
    state.keys().forEach {key->if(key !in setOf("runs","messages","events","attachments"))projected.put(key,state.get(key))}
    projected.put("messages",JSONArray(state.getJSONArray("messages").objects().filter(::visible)))
    projected.put("events",JSONArray(state.getJSONArray("events").objects().filter(::visible)))
    projected.put("runs",JSONArray(state.getJSONArray("runs").objects().filter(::visible).map {fields(it,setOf("turn","contexts","runtime"))}))
    projected.put("attachments",JSONArray(state.getJSONArray("attachments").objects().filter(::visible).map {fields(it,setOf("data","path"))}))
    // Isolate the UI snapshot before crossing threads; never expose mutable Native rows.
    return JSONObject(projected.toString())
}
