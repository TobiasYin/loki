package dev.thor.agent

/** Presentation only. Call on one UI thread; durable events are never coalesced. */
internal class LatestValue<T:Any>(private val schedule:((()->Unit)->Unit),private val send:(T)->Unit) {
    private var pending:T?=null
    private var scheduled=false
    private var closed=false
    fun offer(value:T) {
        if(closed)return
        pending=value
        if(scheduled)return
        scheduled=true
        schedule {
            if(!closed) {
                scheduled=false
                val latest=pending;pending=null
                if(latest!=null)send(latest)
            }
        }
    }
    fun close(){closed=true;pending=null}
}
