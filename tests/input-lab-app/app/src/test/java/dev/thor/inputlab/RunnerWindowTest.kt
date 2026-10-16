package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test

class RunnerWindowTest {
    @Test fun omittedDurationPreservesFiveSecondObservationAndFifteenSecondLease() {
        val window=RunnerWindow.parse()
        assertEquals(5,window.observationSeconds)
        assertEquals(15_000L,window.leaseDurationMs)
    }
    @Test fun allSupportedDurationsRetainTenSecondReadbackMarginAndOneHundredThirtySecondCap() {
        for(seconds in 5..120) {
            val window=RunnerWindow.parse(seconds)
            assertEquals(seconds,window.observationSeconds)
            assertEquals(10_000L,window.leaseDurationMs-seconds*1000L)
            assertTrue(window.leaseDurationMs in 15_000L..130_000L)
        }
    }
    @Test fun outOfRangeDurationsAreRejectedInsteadOfClampedOrOverflowed() {
        for(seconds in listOf(Int.MIN_VALUE,-1,0,4,121,180,Int.MAX_VALUE)) {
            assertThrows("seconds=$seconds",IllegalArgumentException::class.java) { RunnerWindow.parse(seconds) }
        }
    }
    @Test fun malformedBundleValuesCannotSilentlySelectDefaultOrCoerceTypes() {
        for(value in listOf("30",30L,30f,30.0,true,listOf(30))) {
            assertThrows("value=$value",IllegalArgumentException::class.java) { RunnerWindow.parse(value) }
        }
    }
}
