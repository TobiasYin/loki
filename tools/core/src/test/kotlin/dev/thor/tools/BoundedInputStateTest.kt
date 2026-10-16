package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundedInputStateTest {
    @Test fun normalPendingKeepsCurrentCheckpointAvailableButRejectsAnotherDispatch() {
        val state=BoundedInputState()
        state.register("run-a","tap-1")
        assertTrue(state.hasPending());assertFalse(state.hasUnknown())
        // The current gesture's checkpoint uses unknown, not pending, as its exclusion signal.
        requireTool(!state.hasUnknown(),Code.BACKEND_UNAVAILABLE,"Unknown input")
        try {state.register("run-a","tap-2");fail("A second dispatch must not overlap")}
        catch(e:ToolFailure){assertEquals(Code.BACKEND_UNAVAILABLE,e.code)}
        state.complete("run-a","tap-1")
        assertFalse(state.hasPending());assertFalse(state.hasUnknown())
        state.register("run-a","tap-2")
    }

    @Test fun earlyCompletionFollowedByExceptionCannotLeaveGhostUnknown() {
        val state=BoundedInputState();state.register("run-a","drag-1")
        state.complete("run-a","drag-1")
        state.abandon("run-a","drag-1")
        assertFalse(state.abandonRun("run-a"))
        assertFalse(state.hasPending());assertFalse(state.hasUnknown())
        state.register("run-b","tap-1")
    }

    @Test fun lateMatchingCallbackClearsAbandonedDispatchAndAllowsRecovery() {
        val state=BoundedInputState();state.register("run-a","drag-1")
        state.abandon("run-a","drag-1")
        assertTrue(state.hasPending());assertTrue(state.hasUnknown())
        try {state.register("run-b","tap-1");fail("Unknown dispatch is still pending")}
        catch(e:ToolFailure){assertEquals(Code.BACKEND_UNAVAILABLE,e.code)}
        state.complete("run-a","drag-1")
        assertFalse(state.hasPending());assertFalse(state.hasUnknown())
        state.register("run-b","tap-1")
    }

    @Test fun staleCallbacksAndExceptionsCannotClearOrPoisonANewerRequest() {
        val state=BoundedInputState();state.register("run-a","old")
        state.complete("run-a","old");state.register("run-a","new")
        state.complete("run-a","old");state.abandon("run-a","old")
        assertTrue(state.hasPending());assertFalse(state.hasUnknown())
        state.abandon("run-a","new")
        state.complete("run-a","old")
        assertTrue(state.hasPending());assertTrue(state.hasUnknown())
        state.complete("run-a","new")
        assertFalse(state.hasPending());assertFalse(state.hasUnknown())
    }

    @Test fun runReleaseAffectsOnlyItsOwnPendingDispatchAndNeverPretendsToCompleteIt() {
        val state=BoundedInputState();assertFalse(state.abandonRun("run-a"))
        state.register("run-a","shared-request-id")
        state.complete("run-b","shared-request-id");state.abandon("run-b","shared-request-id")
        assertFalse(state.abandonRun("run-b"))
        assertTrue(state.hasPending());assertFalse(state.hasUnknown())
        assertTrue(state.abandonRun("run-a"));assertTrue(state.hasUnknown());assertTrue(state.hasPending())
        state.complete("run-b","shared-request-id");assertTrue(state.hasUnknown())
        state.complete("run-a","shared-request-id");assertFalse(state.hasUnknown());assertFalse(state.hasPending())
        state.register("run-b","shared-request-id")
        state.complete("run-a","shared-request-id");assertTrue(state.hasPending())
    }

    @Test fun simultaneousRegistrationsAtomicallyAllowOnlyOneOwner() {
        val pool=Executors.newFixedThreadPool(2)
        try {
            val state=BoundedInputState();val start=CountDownLatch(1)
            val jobs=listOf("run-a","run-b").map {run->pool.submit<Pair<String,Code?>> {
                check(start.await(2,TimeUnit.SECONDS))
                run to try {state.register(run,"request");null}catch(e:ToolFailure){e.code}
            }}
            start.countDown();val outcomes=jobs.map {it.get(2,TimeUnit.SECONDS)}
            assertEquals(1,outcomes.count {it.second==null})
            assertEquals(1,outcomes.count {it.second==Code.BACKEND_UNAVAILABLE})
            val winner=outcomes.single {it.second==null}.first
            assertFalse(state.abandonRun(outcomes.single {it.second!=null}.first))
            assertTrue(state.hasPending());assertFalse(state.hasUnknown())
            state.complete(winner,"request");assertFalse(state.hasPending())
        } finally {pool.shutdownNow()}
    }

    @Test fun racingCompletionAndAbandonNeverRecreateUnknownAfterCallback() {
        val pool=Executors.newFixedThreadPool(2)
        try {
            repeat(100) {index->
                val state=BoundedInputState();val request="request-$index";state.register("run-a",request)
                val start=CountDownLatch(1)
                val complete=pool.submit {check(start.await(2,TimeUnit.SECONDS));state.complete("run-a",request)}
                val abandon=pool.submit {check(start.await(2,TimeUnit.SECONDS));if(index%2==0)state.abandon("run-a",request) else state.abandonRun("run-a")}
                start.countDown();complete.get(2,TimeUnit.SECONDS);abandon.get(2,TimeUnit.SECONDS)
                assertFalse("Completed dispatch leaked at race $index",state.hasPending())
                assertFalse("Unknown resurrected at race $index",state.hasUnknown())
            }
        } finally {pool.shutdownNow()}
    }
}
