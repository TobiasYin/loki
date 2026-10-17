package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AccessibilityRecoveryTest {
    private class Time:Clock {var time=0L;override fun now()=time}
    @Test fun enabledSettingsWithoutServiceCallbackContinueRecoveringInsteadOfBecomingHealthy() {
        val time=Time();val recovery=AccessibilityRecovery(time)
        assertNull(recovery.next(true,false));time.time=1999;assertNull(recovery.next(true,false))
        time.time=2000;val attempt=requireNotNull(recovery.next(true,false))
        // Settings were successfully written, but Android stayed in Binding.
        recovery.finished(attempt);time.time=3999;assertNull(recovery.next(true,false))
        time.time=4000;assertNotNull(recovery.next(true,false))
    }
    @Test fun oneRecoveryStaysInFlightAndRepeatedMissingCallbacksBackOff() {
        val time=Time();val recovery=AccessibilityRecovery(time)
        recovery.next(true,false);time.time=2000
        var attempt=requireNotNull(recovery.next(true,false));assertNull(recovery.next(true,false))
        for(delay in listOf(2000L,5000L,15000L,30000L,30000L)) {
            recovery.finished(attempt);time.time+=delay-1;assertNull(recovery.next(true,false));time.time++
            attempt=requireNotNull(recovery.next(true,false))
        }
    }
    @Test fun realConnectionCancelsOldAttemptAndAllowsNormalRebindGraceBeforeAnotherRepair() {
        val time=Time();val recovery=AccessibilityRecovery(time)
        recovery.next(true,false);time.time=2000;val old=requireNotNull(recovery.next(true,false))
        time.time=2100;assertNull(recovery.next(true,true));recovery.finished(old)
        time.time=4099;assertNull(recovery.next(true,false));time.time=4100;assertNotNull(recovery.next(true,false))
    }
    @Test fun missingShizukuAndClosedHostNeverStartRepairAndStaleCompletionCannotDelayRegrant() {
        val time=Time();val recovery=AccessibilityRecovery(time)
        assertNull(recovery.next(false,false));time.time=100000;assertNull(recovery.next(false,false))
        recovery.next(true,false);time.time+=2000;val stale=requireNotNull(recovery.next(true,false))
        recovery.next(false,false);recovery.next(true,false);recovery.finished(stale)
        time.time+=2000;assertNotNull(recovery.next(true,false));recovery.close();time.time+=100000;assertNull(recovery.next(true,false))
    }
}
