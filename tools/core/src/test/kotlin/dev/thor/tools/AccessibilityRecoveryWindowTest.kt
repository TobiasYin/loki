package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AccessibilityRecoveryWindowTest {
    private class Time:Clock {var time=0L;override fun now()=time}
    @Test fun settingsSuccessCannotExtendWindowWithoutRealConnection() {
        val clock=Time();val window=AccessibilityRecoveryWindow(clock)
        assertTrue(window.allow(true,false))
        clock.time=44999;assertTrue(window.allow(true,false))
        clock.time=45000;assertFalse(window.allow(true,false))
        clock.time=90000;assertFalse(window.allow(true,false))
        assertFalse(window.allow(true,true))
        assertTrue(window.allow(true,false))
    }
    @Test fun absentBackendDoesNotSpendRepairWindowAndRegrantCanRetry() {
        val clock=Time();val window=AccessibilityRecoveryWindow(clock)
        assertFalse(window.allow(false,false))
        clock.time=100000;assertTrue(window.allow(true,false))
        clock.time+=45000;assertFalse(window.allow(true,false))
        assertFalse(window.allow(false,false));assertTrue(window.allow(true,false))
    }
}
