package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Native ledger is append-only until a session deletion replaces its array.
 * Index row references once; never copy or alter durable evidence. Single writer only. */
internal class EventIndex {
    private var source:JSONArray?=null
    private var length=0
    private val bySession=mutableMapOf<String,MutableList<JSONObject>>()
    private val byRun=mutableMapOf<String,MutableList<JSONObject>>()
    private fun update(events:JSONArray) {
        if(events!==source || events.length()<length) {source=events;length=0;bySession.clear();byRun.clear()}
        while(length<events.length()) {
            val row=events.getJSONObject(length++)
            bySession.getOrPut(row.optString("sessionId")){mutableListOf()}.add(row)
            byRun.getOrPut(row.optString("runId")){mutableListOf()}.add(row)
        }
    }
    @Synchronized fun sessionEvents(events:JSONArray,sid:String):List<JSONObject> {update(events);return bySession[sid].orEmpty().toList()}
    @Synchronized fun runEventCount(events:JSONArray,rid:String):Int {update(events);return byRun[rid]?.size ?: 0}
    @Synchronized fun runEvents(events:JSONArray,rid:String):List<JSONObject> {update(events);return byRun[rid].orEmpty().toList()}
}
