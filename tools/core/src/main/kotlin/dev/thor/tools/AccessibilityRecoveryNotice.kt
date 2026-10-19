package dev.thor.tools

/** Foreground reminder policy. Initial ungranted services are not failures;
 * brief reconnects and authorized automatic recovery should not open a dialog. */
class AccessibilityRecoveryNotice(private val clock:Clock,private val graceMs:Long=3000) {
    private var wasConnected=false
    private var missingSince:Long?=null
    private var shown=false

    fun update(connected:Boolean,recoveryPending:Boolean,enabled:Boolean):Boolean {
        if(connected) {
            wasConnected=true;missingSince=null;shown=false
            return false
        }
        if(recoveryPending || (!wasConnected && !enabled)) {
            missingSince=null
            return false
        }
        val since=missingSince ?: clock.now().also {missingSince=it}
        if(shown || clock.now()-since<graceMs)return false
        shown=true
        return true
    }
}
