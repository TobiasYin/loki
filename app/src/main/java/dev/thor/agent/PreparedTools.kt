package dev.thor.agent

import dev.thor.tools.Code
import dev.thor.tools.requireTool

/** In-flight payloads are bounded independently of completed calls. Tombstones
 * remain until the Native run ends, so releasing a payload never enables replay.
 * Claim and finish are synchronized because completion can occur on a worker. */
internal class PreparedTools<T : Any>(private val maxInFlight:Int=32,private val maxCallsPerRun:Int=4096) {
    private data class Entry<T>(val runId:String,val callId:String,var value:T?,var claimed:Boolean=false)
    private val entries=mutableMapOf<String,Entry<T>>()
    private val calls=mutableMapOf<String,MutableSet<String>>()
    init {require(maxInFlight>0 && maxCallsPerRun>=maxInFlight)}

    @Synchronized fun prepare(runId:String,requestId:String,callId:String,value:T) {
        requireTool(requestId.length in 1..100 && callId.length in 1..512,Code.INVALID_ARGUMENT,"Invalid tool request/call IDs")
        requireTool(requestId !in entries && callId !in calls[runId].orEmpty(),Code.INVALID_ARGUMENT,"Tool request has already been prepared or consumed")
        requireTool(entries.values.count {it.runId==runId && it.value!=null}<maxInFlight,Code.LIMIT_EXCEEDED,"Too many in-flight tools")
        requireTool(calls[runId].orEmpty().size<maxCallsPerRun,Code.LIMIT_EXCEEDED,"Run tool-call budget exhausted")
        entries[requestId]=Entry(runId,callId,value)
        calls.getOrPut(runId){mutableSetOf()}.add(callId)
    }
    @Synchronized operator fun get(requestId:String):T?=entries[requestId]?.value
    @get:Synchronized val values:List<T> get()=entries.values.mapNotNull {it.value}
    /** Validate the complete Native envelope before consuming its one execution. */
    @Synchronized fun claim(requestId:String,validate:(T)->Boolean):T {
        val entry=entries[requestId]
        requireTool(entry!=null && entry.value!=null && !entry.claimed,Code.INVALID_ARGUMENT,"Tool was not prepared or was already consumed")
        val value=entry!!.value!!
        requireTool(validate(value),Code.INVALID_ARGUMENT,"Prepared tool identity or arguments changed")
        entry.claimed=true
        return value
    }
    @Synchronized fun finish(requestId:String,value:T) {
        val entry=entries[requestId] ?: return
        if(entry.value===value)entry.value=null
    }
    @Synchronized fun removeRun(runId:String) {entries.entries.removeAll {it.value.runId==runId};calls.remove(runId)}
    @Synchronized fun clear() {entries.clear();calls.clear()}
}
