package dev.thor.agent

import org.json.JSONObject

/** A bounded display view independent of the tools/thoughts event page. Pi owns
 * the full conversation; this helper never reconstructs model context. */
internal class AssistantTextProjection(private val maxUnits:Int=StreamTextWindow.MAX_UNITS) {
    private data class Segment(val row:JSONObject,val parts:MutableList<String>)
    private val segments=mutableListOf<Segment>()
    private var remaining=maxUnits
    private var legacy=false
    init {require(maxUnits>0)}
    /** Consume text rows newest first. False means the display budget is full. */
    fun appendDescending(event:JSONObject):Boolean {
        require(event.getString("type")=="text_delta")
        val data=event.getJSONObject("data")
        val block=data.optString("blockId").takeIf {it.isNotBlank()}
        if(block==null){legacy=true;return false}
        if(remaining==0){omitOlder(event);return false}
        val window=StreamTextWindow.append("",data.getString("text"),data.optLong("omittedUtf16Units",0),remaining)
        val current=segments.lastOrNull()?.takeIf {it.row.getJSONObject("data").getString("blockId")==block &&
            event.optLong("lastSequence",event.getLong("sequence"))+1==it.row.getLong("sequence")}
        val row=current?.row ?: JSONObject().put("runId",event.getString("runId")).put("sessionId",event.getString("sessionId"))
            .put("type","text_delta").put("lastSequence",event.optLong("lastSequence",event.getLong("sequence")))
            .put("data",JSONObject().put("blockId",block)).also {segments.add(Segment(it,mutableListOf()))}
        row.put("sequence",event.getLong("sequence"))
        for(key in listOf("elapsedMs","wallTime"))if(!row.has(key))event.opt(key)?.let {row.put(key,it)}
        val segment=current ?: segments.last()
        segment.parts.add(window.text);remaining-=window.text.length
        if(window.truncated || data.optBoolean("textTruncated"))row.getJSONObject("data").put("textTruncated",true)
        if(window.omittedUtf16Units>0)row.getJSONObject("data").put("omittedUtf16Units",window.omittedUtf16Units)
        return true
    }
    fun omitOlder(event:JSONObject) {
        val oldest=segments.lastOrNull()?.row ?: return
        if(event.optJSONObject("data")?.optString("blockId")==oldest.getJSONObject("data").optString("blockId") &&
            event.optLong("lastSequence",event.optLong("sequence"))+1==oldest.getLong("sequence"))oldest.getJSONObject("data").put("textTruncated",true)
    }
    fun snapshot():List<JSONObject>? {
        if(legacy)return null
        return segments.asReversed().map {segment->JSONObject(segment.row.toString()).apply {
            val block=getJSONObject("data").getString("blockId")
            put("presentationSegment","${getString("runId")}:text:block:$block:${getLong("sequence")}")
            getJSONObject("data").put("text",segment.parts.asReversed().joinToString(""))
        }}
    }
}
