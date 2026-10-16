package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class GamepadCleanupTest {
    @Test fun rejectedNeutralWriteCannotBeReportedAsReleasedAndStillClosesOriginalFd() {
        var closed=false
        val failure=ToolFailure(Code.IO_ERROR,"neutral frame rejected")
        val receipt=GamepadCleanup.finish(true,{throw failure},{closed=true})
        assertTrue(closed);assertFalse(receipt.releaseAcknowledged);assertFalse(receipt.complete)
        assertEquals(false,receipt.data()["released"]);assertSame(failure,receipt.error)
    }
    @Test fun preparedButNeverSubmittedClosesWithoutManufacturingUpOrNeutral() {
        var releases=0;var closes=0
        val receipt=GamepadCleanup.finish(false,{releases++},{closes++})
        assertEquals(0,releases);assertEquals(1,closes)
        assertTrue(receipt.complete);assertFalse(receipt.releaseAttempted);assertFalse(receipt.releaseAcknowledged)
    }
    @Test fun closeFailureIsDistinctFromAcknowledgedInputRelease() {
        val failure=IllegalStateException("fd close failed")
        val receipt=GamepadCleanup.finish(true,{},{throw failure})
        assertTrue(receipt.releaseAcknowledged);assertFalse(receipt.resourceClosed);assertFalse(receipt.complete)
        assertSame(failure,receipt.error)
    }
    @Test fun normalCleanupAcknowledgesOnlyAfterReleaseThenClose() {
        val order=mutableListOf<String>()
        val receipt=GamepadCleanup.finish(true,{order.add("release")},{order.add("close")})
        assertEquals(listOf("release","close"),order)
        assertTrue(receipt.complete);assertTrue(receipt.releaseAcknowledged);assertNull(receipt.error)
    }
}
