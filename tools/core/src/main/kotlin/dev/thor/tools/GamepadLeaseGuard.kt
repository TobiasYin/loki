package dev.thor.tools

/**
 * Binder identity is checked only at the IPC entrance. Service timer threads have
 * the service UID, so their liveness check must use the already captured lease.
 */
class GamepadLeaseGuard(private val callerUid: Int,private val deadline: Long,private val clock: Clock,
    private val cancellation: Cancellation,private val isCurrentLease: ()->Boolean) {
    fun checkCaller(actualUid: Int) {
        requireTool(actualUid==callerUid,Code.STALE_AUTHORIZATION,"Gamepad caller differs from its lease owner")
        checkAlive()
    }
    fun checkAlive() {
        cancellation.check()
        requireTool(isCurrentLease(),Code.STALE_AUTHORIZATION,"Gamepad lease changed")
        requireTool(clock.now()<deadline,Code.TIMEOUT,"Gamepad lease expired")
    }
}
