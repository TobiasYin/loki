package dev.thor.agent

/** UI-thread only. Retains one pending snapshot and one acknowledged renderer delivery.
 * Durable events are never coalesced. A slow JS renderer cannot accumulate evaluateJavascript calls.
 */
internal class LatestValue<T:Any>(private val schedule:((()->Unit)->Unit),private val send:(T,()->Unit)->Unit) {
    private var pending:T?=null
    private var scheduled=false
    private var inFlight=false
    private var closed=false
    fun offer(value:T) {if(closed)return;pending=value;drain()}
    private fun drain() {
        if(closed || scheduled || inFlight || pending==null)return
        scheduled=true
        schedule {
            scheduled=false
            if(!closed && !inFlight) {
                val latest=pending ?: return@schedule
                pending=null;inFlight=true;var acknowledged=false
                send(latest) {
                    if(!acknowledged) {acknowledged=true;inFlight=false;drain()}
                }
            }
        }
    }
    fun close() {closed=true;pending=null}
}
