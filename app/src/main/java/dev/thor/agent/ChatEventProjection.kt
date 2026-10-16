package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Append-only presentation cache. Durable events remain untouched and complete.
 * Rebuilding the selected conversation's entire streamed ledger for every token
 * creates quadratic work; only newly committed rows are folded here. */
internal class ChatEventProjection {
    private var source:JSONArray?=null
    private var selected:String?=null
    private var consumed=0
    private val rows=mutableListOf<JSONObject>()
    private val blocks=mutableMapOf<String,Pair<JSONObject,StringBuilder>>()
    private val updates=mutableMapOf<String,Int>()
    internal var foldedEvents=0L;private set

    fun project(events:JSONArray,sid:String,index:EventIndex):List<JSONObject> {
        if(source!==events || selected!=sid || consumed>index.sessionEventCount(events,sid)) {
            source=events;selected=sid;consumed=0;rows.clear();blocks.clear();updates.clear()
        }
        val tail=index.sessionEventsSince(events,sid,consumed)
        val dirty=mutableSetOf<String>()
        for(original in tail) {
            foldedEvents++;val type=original.optString("type");val run=original.optString("runId")
            if(type in setOf("text_delta","thinking_delta")) {
                val data=original.getJSONObject("data");val key="$run:$type:${data.optString("blockId")}"
                val block=blocks.getOrPut(key) {JSONObject(original.toString()).also {rows.add(it)} to StringBuilder()}
                block.second.append(data.optString("text"));dirty.add(key)
            }else if(type=="tool_call_update" && original.has("toolCallId")) {
                val key="$run:${original.getString("toolCallId")}";val position=updates[key]
                if(position==null){updates[key]=rows.size;rows.add(original)}else rows[position]=original
            }else rows.add(original)
        }
        consumed+=tail.size
        for(key in dirty)blocks.getValue(key).let {(row,text)->row.getJSONObject("data").put("text",text.toString())}
        return rows.toList()
    }
}
