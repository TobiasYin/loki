package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class PresentationCacheInvalidationTest {
    private val windows=mapOf(0 to setOf(10,11),4 to setOf(40))
    private fun proof(display:Int)=PresentationWindowProof(display,display+100,"abc",10123,"receiver.app",
        "LAYOUT_IN_SCREEN",TouchableBounds(0,0,100,100))
    private fun store(cache:PresentationProofCache,display:Int,now:Long)=
        assertTrue(cache.put(display,"key:$display",listOf(proof(display)),now,cache.generation(display)))

    @Test fun publicDisplayAndCurrentWindowMappingMustAgree() {
        assertEquals(0,PresentationEventScope.display(0,10,windows))
        assertEquals(4,PresentationEventScope.display(4,40,windows))
        assertNull(PresentationEventScope.display(0,40,windows))
        assertNull(PresentationEventScope.display(9,40,windows))
        assertNull(PresentationEventScope.display(4,40,windows+ (0 to setOf(40))))
    }
    @Test fun preApi33UsesUniqueWindowMappingAndUnknownOriginRevokesAll() {
        assertEquals(0,PresentationEventScope.display(-1,10,windows))
        assertEquals(4,PresentationEventScope.display(-1,40,windows))
        assertNull(PresentationEventScope.display(-1,-1,windows))
        assertNull(PresentationEventScope.display(-1,99,windows))
        assertNull(PresentationEventScope.display(-1,40,windows+ (0 to setOf(40))))
    }
    @Test fun removedWindowRequiresKnownPublicDisplayAndZeroIsValid() {
        assertEquals(0,PresentationEventScope.display(0,99,windows))
        assertEquals(4,PresentationEventScope.display(4,99,windows))
        assertNull(PresentationEventScope.display(8,99,windows))
        assertNull(PresentationEventScope.display(-1,99,windows))
    }
    @Test fun unrelatedUpperStructureDoesNotRevokeLowerProofOrLowerWorker() {
        val cache=PresentationProofCache(Clock {1000});store(cache,0,1000);store(cache,4,1000)
        val lowerGeneration=cache.generation(4);val upperGeneration=cache.generation(0)
        cache.invalidate(0,PresentationCacheReason.STRUCTURE_EVENT,0x400000,10,0)
        assertNull(cache.get(0,"key:0"));assertNotNull(cache.get(4,"key:4"))
        assertEquals(lowerGeneration,cache.generation(4))
        assertTrue(cache.put(4,"key:4",listOf(proof(4)),1000,lowerGeneration))
        assertFalse(cache.put(0,"key:0",listOf(proof(0)),1000,upperGeneration))
    }
    @Test fun sameDisplayAndUnknownStructureRejectLateWorkerAndStillClearProof() {
        val cache=PresentationProofCache(Clock {1000});store(cache,0,1000);store(cache,4,1000)
        val oldLower=cache.generation(4)
        cache.invalidate(4,PresentationCacheReason.STRUCTURE_EVENT,0x20,40,4)
        assertNull(cache.get(4,"key:4"));assertNotNull(cache.get(0,"key:0"))
        assertFalse(cache.put(4,"key:4",listOf(proof(4)),1000,oldLower));store(cache,4,1000)
        val upper=cache.generation(0);val lower=cache.generation(4)
        cache.invalidate(reason=PresentationCacheReason.STRUCTURE_EVENT,eventType=0x400000,windowId=-1,reportedDisplay=-1)
        assertNull(cache.get(0,"key:0"));assertNull(cache.get(4,"key:4"))
        assertFalse(cache.put(0,"key:0",listOf(proof(0)),1000,upper))
        assertFalse(cache.put(4,"key:4",listOf(proof(4)),1000,lower))
    }
    @Test fun absoluteFiveSecondTtlAndChangedKeyRemainFailClosed() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});store(cache,4,now)
        now=5999;assertNotNull(cache.get(4,"key:4"));now=6000;assertNull(cache.get(4,"key:4"))
        assertEquals(PresentationCacheReason.EXPIRED,cache.diagnostics().last().reason)
        store(cache,4,now);assertNull(cache.get(4,"new:key"))
        assertEquals(PresentationCacheReason.KEY_CHANGED,cache.diagnostics().last().reason)
        assertFalse(cache.put(4,"key:4",listOf(proof(4)),1000,cache.generation(4)))
    }
    @Test fun diagnosticsAreBoundedAndRetainOnlyHashedWindowIdentity() {
        val cache=PresentationProofCache(Clock {1000});store(cache,4,1000)
        cache.get(4,"secret-window-title-and-token")
        val item=cache.diagnostics().last()
        assertEquals(64,item.oldKeyHash!!.length);assertEquals(64,item.newKeyHash!!.length)
        assertFalse(item.toString().contains("secret-window-title"))
        repeat(80){cache.invalidate(0,PresentationCacheReason.STRUCTURE_EVENT,0x400000,it,0)}
        assertEquals(64,cache.diagnostics().size)
    }
    @Test fun serviceOrDisplayIdentityResetInvalidatesBothRolesAfterScopedChanges() {
        val cache=PresentationProofCache(Clock {1000});cache.invalidate(0);store(cache,0,1000);store(cache,4,1000)
        val upper=cache.generation(0);val lower=cache.generation(4)
        cache.invalidate(reason=PresentationCacheReason.DISPLAY_CONFIGURATION)
        assertFalse(cache.put(0,"key:0",listOf(proof(0)),1000,upper))
        assertFalse(cache.put(4,"key:4",listOf(proof(4)),1000,lower))
        assertNull(cache.get(0,"key:0"));assertNull(cache.get(4,"key:4"))
    }
}
