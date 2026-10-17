package dev.thor.tools

/** Health scheduling only. Enabled settings do not prove Android connected the
 * service; only the caller's real service callback resets retries. No I/O here. */
class AccessibilityRecovery(private val clock:Clock,private val connectGraceMs:Long=2000) {
    data class Attempt internal constructor(val id:Long)
    private var generation=0L
    private var active:Attempt?=null
    private var backendSeen=false
    private var retry=0
    private var nextAt=0L
    private var closed=false
    @Synchronized fun next(backendReady:Boolean,serviceConnected:Boolean):Attempt? {
        if(closed)return null
        val now=clock.now()
        if(serviceConnected) {
            active=null;generation++;retry=0;nextAt=now+connectGraceMs;backendSeen=backendReady
            return null
        }
        if(!backendReady) {
            if(backendSeen){active=null;generation++;retry=0}
            backendSeen=false;return null
        }
        if(!backendSeen) {backendSeen=true;nextAt=now+connectGraceMs;return null}
        if(active!=null || now<nextAt)return null
        return Attempt(++generation).also {active=it}
    }
    /** Successful settings readback still needs a real connection callback. */
    @Synchronized fun finished(attempt:Attempt) {
        if(closed || active!=attempt)return
        active=null
        val delay=longArrayOf(2000,5000,15000,30000)[retry.coerceAtMost(3)]
        retry=(retry+1).coerceAtMost(3);nextAt=clock.now()+delay
    }
    @Synchronized fun close(){closed=true;active=null;generation++}
}
