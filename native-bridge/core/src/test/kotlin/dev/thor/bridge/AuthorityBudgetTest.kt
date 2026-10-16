package dev.thor.bridge

import org.junit.Assert.*
import org.junit.Test

class AuthorityBudgetTest {
    @Test fun chatStopCapturesOnlyItsCurrentRunAndCannotRevokeAnotherChatOrLaterWork() {
        val a=Authority();val c=a.connect();a.createSession("one");a.createSession("two")
        val first=a.beginRun("one",c,emptyMap(),durationMs=0)
        val other=a.beginRun("two",c,emptyMap(),durationMs=0)
        assertTrue(a.stopSessionRuns("one",other.runId).isEmpty())
        a.validate(first);a.validate(other)
        val stopped=a.stopSessionRuns("one")
        assertEquals(listOf(first.runId),stopped)
        assertThrows(BridgeFailure::class.java){a.validate(first)}
        a.validate(other)
        val next=a.beginRun("one",c,emptyMap(),durationMs=0)
        stopped.forEach {a.stopRun(it)} // Queued cleanup must use captured IDs.
        a.validate(next);a.validate(other)
    }
    @Test fun stoppingAnEmptyOrUnknownChatNeverRevokesExistingWork() {
        val a=Authority();val c=a.connect();a.createSession("one");a.createSession("empty")
        val run=a.beginRun("one",c,emptyMap(),durationMs=0)
        assertTrue(a.stopSessionRuns("empty").isEmpty())
        assertTrue(a.stopSessionRuns("unknown").isEmpty())
        a.validate(run)
    }
    @Test fun unboundedForegroundRunSurvivesClockAdvanceUntilExplicitRevocation() {
        var now=1000L;val a=Authority(MonotonicClock {now});a.createSession("session");val connection=a.connect()
        val run=a.beginRun("session",connection,emptyMap(),durationMs=0)
        now+=90L*24*60*60*1000;a.validate(run)
        a.stopRun(run.runId)
        assertThrows(BridgeFailure::class.java){a.validate(run)}
        val next=a.beginRun("session",connection,emptyMap(),durationMs=0)
        a.disconnect(connection);assertThrows(BridgeFailure::class.java){a.validate(next)}
    }
    @Test fun longForegroundTaskHasBoundedNativeLeaseAndCanStillStopImmediately() {
        var now=1000L;val a=Authority(MonotonicClock {now});a.createSession("test");val c=a.connect()
        val b=a.beginRun("test",c,emptyMap(),durationMs=600000)
        assertEquals(600000L,a.remainingLeaseMs(b));now+=180001;a.validate(b)
        a.stopRun(b.runId)
        try {a.validate(b);fail("Stopped lease accepted")}catch(_:BridgeFailure){}
        try {a.beginRun("test",c,emptyMap(),durationMs=600001);fail("Unbounded lease accepted")}catch(_:IllegalArgumentException){}
        val next=a.beginRun("test",c,emptyMap(),durationMs=600000);now+=600000
        try {a.validate(next);fail("Expired lease accepted")}catch(_:BridgeFailure){}
    }
}
