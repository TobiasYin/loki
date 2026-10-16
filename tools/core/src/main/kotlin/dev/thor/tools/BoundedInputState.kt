package dev.thor.tools

/** One asynchronous Android input dispatch at a time. Register before entering Binder.
 *
 * A pending, normally executing gesture does not make its own checkpoints unavailable.
 * Only an abandoned dispatch has an unknown result, until its matching completion arrives.
 * Keeping uncertainty on the token makes callback/abandon transitions atomic: a callback
 * which already completed can never be followed by a detached "unknown" flag.
 */
class BoundedInputState {
    private data class Pending(val runId:String,val requestId:String,var unknown:Boolean=false)
    private var pending:Pending?=null

    @Synchronized fun register(runId:String,requestId:String) {
        requireTool(pending==null,Code.BACKEND_UNAVAILABLE,"A bounded input dispatch is still pending")
        pending=Pending(runId,requestId)
    }

    @Synchronized fun complete(runId:String,requestId:String) {
        if(matches(runId,requestId))pending=null
    }

    @Synchronized fun abandon(runId:String,requestId:String) {
        if(matches(runId,requestId))pending!!.unknown=true
    }

    @Synchronized fun abandonRun(runId:String):Boolean {
        val current=pending ?: return false
        if(current.runId!=runId)return false
        current.unknown=true
        return true
    }

    @Synchronized fun hasPending():Boolean=pending!=null
    @Synchronized fun hasUnknown():Boolean=pending?.unknown==true

    private fun matches(runId:String,requestId:String):Boolean =
        pending?.let {it.runId==runId && it.requestId==requestId}==true
}
