package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GamepadLeaseGuardTest {
    private val pool=Executors.newSingleThreadExecutor {task->Thread(task,"fake-shell-service-timer").apply {isDaemon=true}}
    @After fun cleanup() {pool.shutdownNow()}
    private fun background(action:()->Unit): Code? = pool.submit<Code?> {try {action();null}catch(t:ToolFailure){t.code}}.get(2,TimeUnit.SECONDS)
    @Test fun serviceTimerUsesCapturedLeaseInsteadOfItsOwnShellUid() {
        val guard=GamepadLeaseGuard(10234,100,Clock {10},Cancellation()) {true}
        guard.checkCaller(10234)
        assertNull(background {guard.checkAlive()})
        // The same shell UID may execute trusted cleanup/timer work but cannot impersonate an app IPC caller.
        assertEquals(Code.STALE_AUTHORIZATION,background {guard.checkCaller(2000)})
    }
    @Test fun replacingLeaseOrKillingHostRejectsPreviouslyCapturedTimer() {
        val current=AtomicBoolean(true);val cancel=Cancellation()
        val old=GamepadLeaseGuard(10234,100,Clock {10},cancel) {current.get()}
        current.set(false)
        assertEquals(Code.STALE_AUTHORIZATION,background {old.checkAlive()})
        current.set(true);cancel.cancel()
        assertEquals(Code.CANCELLED,background {old.checkAlive()})
    }
    @Test fun exactDeadlineExpiresWithoutPollingFromTheApp() {
        val now=AtomicLong(99)
        val guard=GamepadLeaseGuard(10234,100,Clock {now.get()},Cancellation()) {true}
        assertNull(background {guard.checkAlive()})
        now.set(100)
        assertEquals(Code.TIMEOUT,background {guard.checkAlive()})
    }
}
