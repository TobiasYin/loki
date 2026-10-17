package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject

/** Presentation-only fold. Stream segments keep their first position; subsequent tool
 * argument/result updates never move the operation past later thoughts. The complete
 * durable ledger is untouched. A second fold preserves explicit segment boundaries. */
internal class PresentationEventFold {
    private val rows=mutableListOf<JSONObject>()
    private val blocks=linkedMapOf<String,Pair<JSONObject,StreamTextWindow>>()
    private val streams=mutableMapOf<String,String>()
    private val updates=mutableMapOf<String,Int>()
    private val budgets=mutableMapOf<String,Int>()
    private val budgetKeys=mutableMapOf<String,MutableList<String>>()
    private val budgetHeads=mutableMapOf<String,Int>()
    private val toolEvents=setOf("tool_call_update","tool_intent","tool_started","tool_result")
    fun append(original:JSONObject) {
        val type=original.optString("type");val run=original.optString("runId")
        if(type=="text_delta" || type=="thinking_delta") {
            val data=original.getJSONObject("data")
            val explicit=original.optString("presentationSegment").takeIf {it.isNotEmpty()}
            val current=streams[run]?.takeIf {blocks[it]?.first?.optString("type")==type && (explicit==null || explicit==it)}
            val key=current ?: explicit ?: "$run:$type:${original.optLong("sequence")}:${rows.size}"
            if(!blocks.containsKey(key)) {
                val row=JSONObject(original.toString()).put("presentationSegment",key)
                rows.add(row);blocks[key]=row to StreamTextWindow("",0)
                budgetKeys.getOrPut("$run:$type"){mutableListOf()}.add(key)
            }
            streams[run]=key
            val previous=blocks.getValue(key)
            val window=StreamTextWindow.append(previous.second.text,data.optString("text"),previous.second.omittedUtf16Units+data.optLong("omittedUtf16Units",0))
            blocks[key]=previous.first to window
            previous.first.put("lastSequence",original.optLong("lastSequence",original.optLong("sequence")))
            val budget="$run:$type"
            budgets[budget]=(budgets[budget] ?: 0)+window.text.length-previous.second.text.length
            publish(key);trim(budget)
        }else if(type=="tool_call_update" && original.has("toolCallId")) {
            streams.remove(run)
            val key="$run:${original.getString("toolCallId")}";val position=updates[key]
            if(position==null){updates[key]=rows.size;rows.add(original)}else {
                val first=rows[position]
                rows[position]=JSONObject(original.toString()).put("sequence",first.optLong("sequence")).put("lastSequence",original.optLong("lastSequence",original.optLong("sequence")))
            }
        }else {
            if(type in toolEvents || type=="diagnostic" && original.optJSONObject("data")?.optString("code") in setOf("CONTEXT_COMPACTION_STARTED","CONTEXT_COMPACTION_FINISHED"))streams.remove(run)
            rows.add(original)
        }
    }
    private fun publish(key:String) {
        val (row,window)=blocks.getValue(key)
        row.getJSONObject("data").put("text",window.text).also {
            if(window.truncated)it.put("textTruncated",true).put("omittedUtf16Units",window.omittedUtf16Units)
        }
    }
    /** Keep a run's total displayed text bounded even when many segments are retained. */
    private fun trim(budget:String) {
        var excess=(budgets[budget] ?: 0)-StreamTextWindow.MAX_UNITS
        if(excess<=0)return
        val keys=budgetKeys.getValue(budget);var head=budgetHeads[budget] ?: 0
        while(excess>0 && head<keys.size) {
            val key=keys[head];val (row,window)=blocks.getValue(key)
            if(window.text.isEmpty()){head++;continue}
            var cut=excess.coerceAtMost(window.text.length)
            if(cut<window.text.length && cut>0 && window.text[cut].isLowSurrogate() && window.text[cut-1].isHighSurrogate())cut++
            blocks[key]=row to StreamTextWindow(window.text.substring(cut),(window.omittedUtf16Units+cut).coerceAtMost(9_007_199_254_740_991L))
            budgets[budget]=budgets.getValue(budget)-cut;excess-=cut;publish(key)
            if(cut==window.text.length)head++
        }
        budgetHeads[budget]=head
    }
    fun snapshot():List<JSONObject> = rows.toList()
}

/** Incrementally visit newly committed rows, rather than rebuilding a long ledger
 * for every streamed token. Clearing releases all borrowed rows and text windows. */
internal class ChatEventProjection {
    private var source:JSONArray?=null
    private var selected:String?=null
    private var consumed=0
    private var fold=PresentationEventFold()
    internal var foldedEvents=0L;private set
    @Synchronized fun clear() {source=null;selected=null;consumed=0;fold=PresentationEventFold();foldedEvents=0}
    @Synchronized fun project(events:JSONArray,sid:String,index:EventIndex):List<JSONObject> {
        if(source!==events || selected!=sid || consumed>index.sessionEventCount(events,sid)) {
            source=events;selected=sid;consumed=0;fold=PresentationEventFold()
        }
        val tail=index.sessionEventsSince(events,sid,consumed)
        for(original in tail){foldedEvents++;fold.append(original)}
        consumed+=tail.size
        return fold.snapshot()
    }
}
