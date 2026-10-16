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
    // UI needs complete thought blocks and the latest parsed call, not every streaming fragment.
    // Keep the durable append-only ledger untouched for model history, audit and recovery.
    val events=mutableListOf<JSONObject>();val blocks=mutableMapOf<String,Pair<JSONObject,StringBuilder>>();val updates=mutableMapOf<String,Int>()
    for(original in (indexedEvents ?: state.getJSONArray("events").objects()).filter(::visible)) {
        val type=original.optString("type");val run=original.optString("runId")
        if(type in setOf("text_delta","thinking_delta")) {
            val data=original.getJSONObject("data");val key="$run:$type:${data.optString("blockId")}";val previous=blocks[key]
            if(previous==null) {val copy=JSONObject(original.toString());blocks[key]=copy to StringBuilder(data.optString("text"));events.add(copy)}
            else previous.second.append(data.optString("text"))
        } else if(type=="tool_call_update" && original.has("toolCallId")) {
            val key="$run:${original.getString("toolCallId")}";val index=updates[key]
            if(index==null) {updates[key]=events.size;events.add(original)} else events[index]=original
        } else events.add(original)
    }
    blocks.values.forEach {(row,text)->row.getJSONObject("data").put("text",text.toString())}
    projected.put("events",JSONArray(events.sortedBy {it.optLong("sequence")}))
    projected.put("runs",JSONArray(state.getJSONArray("runs").objects().filter(::visible).map {fields(it,setOf("turn","contexts","runtime"))}))
    projected.put("attachments",JSONArray(state.getJSONArray("attachments").objects().filter(::visible).map {fields(it,setOf("data","path"))}))
    // Isolate the UI snapshot before crossing threads; never expose mutable Native rows.
    return JSONObject(projected.toString())
}
