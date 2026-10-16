package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class PresentationProofRefreshTest {
    private val proof=PresentationWindowProof(4,40,"abc",10123,"receiver.app","LAYOUT_IN_SCREEN",TouchableBounds(0,0,100,100))
    private fun put(cache:PresentationProofCache,started:Long,generation:Long=cache.generation(4))=
        cache.put(4,"stable",listOf(proof),started,generation)

    @Test fun reserveSchedulesCompleteRefreshBeforeOldAbsoluteExpiry() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now))
        now=4000;assertEquals(2000L,cache.remainingLifetimeMs(4,"stable"))
        assertFalse(PresentationRefreshPolicy.needsFreshProof(cache.remainingLifetimeMs(4,"stable")))
        now=4001;assertEquals(1999L,cache.remainingLifetimeMs(4,"stable"))
        assertTrue(PresentationRefreshPolicy.needsFreshProof(cache.remainingLifetimeMs(4,"stable")))
        assertNotNull(cache.get(4,"stable")) // Refresh scheduling must not evict still-valid evidence.
    }
    @Test fun remainingLifetimeIsPassiveAndDoesNotRenewOrRemoveTheEntry() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now));val trace=cache.diagnostics()
        now=5900;repeat(50){assertEquals(100L,cache.remainingLifetimeMs(4,"stable"))}
        assertEquals(trace,cache.diagnostics())
        assertEquals(0L,cache.remainingLifetimeMs(4,"other"));assertEquals(100L,cache.remainingLifetimeMs(4,"stable"))
        now=6000;assertEquals(0L,cache.remainingLifetimeMs(4,"stable"));assertEquals(trace,cache.diagnostics())
        assertNull(cache.get(4,"stable"));assertEquals(PresentationCacheReason.EXPIRED,cache.diagnostics().last().reason)
    }
    @Test fun failedRefreshLeavesOriginalEntryAndDeadlineIntact() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now))
        now=4500;val generation=cache.generation(4)
        // A rejected new result is not usable proof and cannot extend the old lifetime.
        assertFalse(cache.put(4,"stable",listOf(proof.copy(displayId=0)),4500,generation))
        assertEquals(1500L,cache.remainingLifetimeMs(4,"stable"));assertNotNull(cache.get(4,"stable"))
        now=6000;assertNull(cache.get(4,"stable"))
    }
    @Test fun completedFullNewReadReplacesOnlyWithItsOwnStartTime() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now))
        now=4500;val newReadStarted=now;val generation=cache.generation(4)
        now=4800;assertTrue(put(cache,newReadStarted,generation))
        assertEquals(4700L,cache.remainingLifetimeMs(4,"stable"))
        now=6000;assertNotNull(cache.get(4,"stable"));now=9500;assertNull(cache.get(4,"stable"))
    }
    @Test fun sameDisplayOrGlobalInvalidationRejectsInFlightRefresh() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now));now=4500
        val before=cache.generation(4);cache.invalidate(4,PresentationCacheReason.STRUCTURE_EVENT)
        assertFalse(put(cache,4500,before));assertTrue(put(cache,4500))
        val current=cache.generation(4);cache.invalidate(reason=PresentationCacheReason.READER_CHANGED)
        assertFalse(put(cache,4500,current));assertNull(cache.get(4,"stable"))
    }
    @Test fun failedRefreshOnAnotherDisplayCannotEvictValidConcurrentProof() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now));now=4500
        cache.invalidate(0,PresentationCacheReason.STRUCTURE_EVENT)
        assertNotNull(cache.get(4,"stable"));assertEquals(1500L,cache.remainingLifetimeMs(4,"stable"))
        assertTrue(put(cache,4500));assertEquals(5000L,cache.remainingLifetimeMs(4,"stable"))
    }
    @Test fun missingWrongKeyExpiredAndClockRollbackAllRequireCompleteNewProof() {
        var now=1000L;val cache=PresentationProofCache(Clock {now})
        assertTrue(PresentationRefreshPolicy.needsFreshProof(cache.remainingLifetimeMs(4,"stable")))
        assertTrue(put(cache,now));assertEquals(0L,cache.remainingLifetimeMs(4,"other"))
        now=999;assertEquals(0L,cache.remainingLifetimeMs(4,"stable"));now=6000;assertEquals(0L,cache.remainingLifetimeMs(4,"stable"))
        assertTrue(PresentationRefreshPolicy.needsFreshProof(0))
    }
    @Test fun unchangedNewProofFactsKeepTargetLayoutIdentity() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now))
        val bounds=TouchableBounds(0,0,100,100);val display=DisplayFact(4,"lower",100,100,0,160)
        fun target()=UpperTargetResolver.resolve(display,listOf(TargetWindowEvidence(40,-1,0,false,false,bounds,listOf(bounds),true,"receiver.app",bounds,cache.get(4,"stable")!!.single())),TargetWindowRevisions())
        val before=target();now=4500;assertTrue(put(cache,now));assertTrue(before.sameStructure(target()))
    }
    @Test fun schedulingDiagnosticsCaptureNumericStageWithoutRenewingOrExposingProofKey() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});assertTrue(put(cache,now));now=4500
        val generation=cache.generation(4)
        cache.note(4,PresentationCacheReason.SCHEDULE_REFRESH_REQUIRED,"private-internal-window-key",remainingMs=1500,candidateCount=1,expectedGeneration=generation)
        cache.note(4,PresentationCacheReason.WORKER_BEGIN,expectedGeneration=generation)
        cache.note(4,PresentationCacheReason.READER_FAILED,durationMs=150,errorCode=Code.SECURE_CONTENT)
        val rows=cache.diagnostics().takeLast(3)
        assertEquals(1500L,rows[0].remainingMs);assertEquals(1,rows[0].candidateCount);assertEquals(generation,rows[0].expectedGeneration)
        assertEquals(Code.SECURE_CONTENT,rows[2].errorCode);assertEquals(150L,rows[2].durationMs)
        assertEquals(64,rows[0].newKeyHash!!.length);assertFalse(rows.toString().contains("private-internal-window-key"))
        cache.note(4,PresentationCacheReason.SCHEDULE_QUEUED,"abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",cache.diagnostics().last().newKeyHash)
        assertNotNull(cache.get(4,"stable"));assertEquals(1500L,cache.remainingLifetimeMs(4,"stable"))
        now=6000;assertNull(cache.get(4,"stable"))
    }
    @Test fun unknownReaderFailureAndWaitDiagnosticsRemainBoundedAndDoNotInvalidateEvidence() {
        val cache=PresentationProofCache(Clock {1000});assertTrue(put(cache,1000));val generation=cache.generation(4)
        cache.note(4,PresentationCacheReason.READER_REMOTE_FAILED,errorCode=null)
        assertNull(cache.diagnostics().last().errorCode)
        repeat(80){cache.note(4,PresentationCacheReason.WAIT_TIMEOUT,durationMs=1500,expectedGeneration=generation)}
        assertEquals(64,cache.diagnostics().size);assertEquals(generation,cache.generation(4));assertEquals(5000L,cache.remainingLifetimeMs(4,"stable"))
        assertNotNull(cache.get(4,"stable"))
    }

}
