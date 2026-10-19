package dev.thor.tools

/** Bounds automatic repair across successful settings writes without callbacks.
 * A real connection or loss/regrant of the backend starts a fresh window. */
class AccessibilityRecoveryWindow(private val clock:Clock,private val timeoutMs:Long=45000) {
    private var startedAt:Long?=null
    @Synchronized fun allow(authorized:Boolean,connected:Boolean):Boolean {
        if(connected || !authorized) {startedAt=null;return false}
        val start=startedAt ?: clock.now().also {startedAt=it}
        return clock.now()-start<timeoutMs
    }
}
