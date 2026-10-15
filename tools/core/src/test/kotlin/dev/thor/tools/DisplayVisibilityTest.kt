package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class DisplayVisibilityTest {
    private val upper=DisplayFact(0,"upper",800,600,1,240)
    private val lower=DisplayFact(4,"lower",640,600,1,240)
    private val full=TouchableBounds(0,0,800,600)
    private fun rejected(action:()->Unit) {
        val error=assertThrows(ToolFailure::class.java,action)
        assertEquals(Code.TARGET_CHANGED,error.code)
    }
    @Test fun canonicalLayoutRequiresCompleteSoleFullscreenRectangularEvidence() {
        assertEquals(full,FullScreenGeometry.canonicalRoot(upper,1,1,full,TouchableBounds(1,0,800,599),true))
        assertEquals(full,FullScreenGeometry.canonicalRoot(upper,1,1,full,TouchableBounds(50,50,750,550),true))
        assertNull(FullScreenGeometry.canonicalRoot(upper,2,1,full,full,true))
        assertNull(FullScreenGeometry.canonicalRoot(upper,1,0,full,full,true))
        assertNull(FullScreenGeometry.canonicalRoot(upper,1,1,full,full,false))
        assertNull(FullScreenGeometry.canonicalRoot(upper,1,1,null,full,true))
        assertNull(FullScreenGeometry.canonicalRoot(upper,1,1,full,null,true))
        assertNull(FullScreenGeometry.canonicalRoot(upper,1,1,TouchableBounds(1,0,800,600),full,true))
        for(clip in listOf(TouchableBounds(-1,0,800,600),TouchableBounds(0,0,801,600),TouchableBounds(0,0,800,0)))
            assertNull(FullScreenGeometry.canonicalRoot(upper,1,1,full,clip,true))
    }
    @Test fun clippingDoesNotRebindLayoutButCurrentClippedEdgesCannotReceiveInput() {
        var target=Target("lab","valid","same-root",full)
        val s=Snapshots(Clock {1000}) {target};s.update(listOf(upper,lower));s.calibrate(0,4);val snapshot=s.capture()
        target=target.copy(touchableBounds=TouchableBounds(1,0,800,599))
        assertEquals(snapshot,s.validate(snapshot.id))
        s.validateInputPoints(snapshot.id,listOf(Point(400.0,300.0)))
        s.validateInputPoints(snapshot.id,listOf(Point(2.0,2.0),Point(799.5,598.5)))
        rejected {s.validateInputPoints(snapshot.id,listOf(Point(0.0,300.0)))}
        rejected {s.validateInputPoints(snapshot.id,listOf(Point(400.0,599.0)))}
        rejected {s.validateInputPoints(snapshot.id,listOf(Point(400.0,300.0),Point(0.0,300.0)))}
    }
    @Test fun newlyVisibleEdgesCannotBeUsedFromAnImageThatNeverObservedThem() {
        var target=Target("lab","valid","same-root",TouchableBounds(1,0,800,599))
        val s=Snapshots(Clock {1000}) {target};s.update(listOf(upper,lower));s.calibrate(0,4);val old=s.capture()
        target=target.copy(touchableBounds=full)
        rejected {s.validateInputPoints(old.id,listOf(Point(0.0,300.0)))}
        rejected {s.validateInputPoints(old.id,listOf(Point(400.0,599.0)))}
        val fresh=s.captureForRead(old.id)
        assertNotEquals(old.id,fresh.id)
        s.validateInputPoints(fresh.id,listOf(Point(0.0,300.0)))
        s.validateInputPoints(fresh.id,listOf(Point(400.0,599.0)))
    }
    @Test fun unknownVisibilityAndRealRootOrAppChangesStillInvalidateTheTarget() {
        val original=Target("lab","valid","same-root",full);var target=original
        val s=Snapshots(Clock {1000}) {target};s.update(listOf(upper,lower));s.calibrate(0,4);val snapshot=s.capture()
        for(changed in listOf(original.copy(touchableBounds=null),original.copy(revision="root-translated"),original.copy(packageName="other"),original.copy(validity="unknown"))) {
            target=changed;rejected {s.validateInputPoints(snapshot.id,listOf(Point(400.0,300.0)))}
        }
    }
    @Test fun clippingNormalizationNeverExtendsTheTenSecondInputExpiry() {
        var now=1000L;var target=Target("lab","valid","same-root",full)
        val s=Snapshots(Clock {now}) {target};s.update(listOf(upper,lower));s.calibrate(0,4);val old=s.capture()
        now+=10_000;target=target.copy(touchableBounds=TouchableBounds(1,0,800,599))
        rejected {s.validateInputPoints(old.id,listOf(Point(400.0,300.0)))}
        val fresh=s.captureForRead(old.id)
        s.validateInputPoints(fresh.id,listOf(Point(400.0,300.0)))
        rejected {s.validateInputPoints(old.id,listOf(Point(400.0,300.0)))}
    }
}
