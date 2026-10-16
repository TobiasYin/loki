package dev.thor.agent

import org.json.JSONObject

/** Release has no recorder, clock, text inspection, retained state or export. */
@Suppress("UNUSED_PARAMETER")
internal object RuntimeStartupDiagnostics {
    fun begin()=0L
    fun mark(attempt:Long,phase:RuntimeStartupPhase,value:Long=0) {}
    fun payload(attempt:Long,phase:RuntimeStartupPhase,text:String) {}
    fun snapshot()=JSONObject()
}
