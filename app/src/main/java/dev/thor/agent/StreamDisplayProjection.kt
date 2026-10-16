package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** A display window only. Durable fragments and Pi/domain turns remain complete. */
internal data class StreamTextWindow(val text:String,val omittedUtf16Units:Long) {
    val truncated get()=omittedUtf16Units>0
    companion object {
        const val MAX_UNITS=65536
        fun append(previous:String,delta:String,alreadyOmitted:Long=0,maxUnits:Int=MAX_UNITS):StreamTextWindow {
            require(maxUnits>0 && alreadyOmitted>=0)
            val combined=previous+delta
            var start=(combined.length-maxUnits).coerceAtLeast(0)
            // Never start the display tail in the middle of a surrogate pair.
            if(start>0 && start<combined.length && combined[start].isLowSurrogate() && combined[start-1].isHighSurrogate())start++
            val omitted=(alreadyOmitted.coerceAtMost(9_007_199_254_740_991L)+start).coerceAtMost(9_007_199_254_740_991L)
            return StreamTextWindow(combined.substring(start),omitted)
        }
    }
}

/** Validate and prepare everything that can reject an event before mutating the
 * in-memory ledger. Storage failures still belong to the real transaction. */
internal object StreamDisplayProjection {
    fun append(events:JSONArray,messages:List<JSONObject>,run:JSONObject,event:JSONObject,
        sessionId:String,runId:String,sequence:Int,summary:String,commit:JSONObject?=null):JSONObject {
        require(event.getString("sessionId")==sessionId && event.getString("runId")==runId)
        require(event.getInt("sequence")==sequence)
        val data=event.getJSONObject("data")
        val type=event.getString("type")
        val copy=JSONObject(event.toString()).put("id",event.getString("eventId")).put("summary",summary)
        for(key in listOf("toolId","outcome","error"))if(data.has(key))copy.put(key,data.get(key))
        val committed=commit?.let {
            require(it.getString("sessionId")==sessionId && it.getString("runId")==runId)
            it.getString("state") // Validate before appending the event.
            JSONObject(it.toString())
        }
        val field=when(type){"text_delta"->"text";"thinking_delta"->"thinking";else->null}
        val message=field?.let {messages.single {it.getString("runId")==runId && it.getString("role")=="assistant"}}
        val window=field?.let {
            require(data.get("text") is String)
            StreamTextWindow.append(message!!.optString(it),data.getString("text"),message.optLong("${it}OmittedUtf16Units",0))
        }
        // No validation or display-size rejection follows ledger mutation.
        events.put(copy)
        if(field!=null && window!=null)message!!.put(field,window.text).put("${field}Truncated",window.truncated).put("${field}OmittedUtf16Units",window.omittedUtf16Units)
        if(committed!=null) {
            val status=committed.getString("state")
            run.put("status",status).put("runtime",committed)
            if(status in setOf("completed","failed","cancelled","interrupted"))run.put("expanded",false)
        }
        return copy
    }
}
