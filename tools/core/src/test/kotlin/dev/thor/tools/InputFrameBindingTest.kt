package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class InputFrameBindingTest {
    private class Fixture {
        var now=0L
        val upper=DisplayFact(7,"upper",1200,800,0,240)
        val lower=DisplayFact(9,"lower",1200,800,0,240)
        var target=Target("game.app","valid","content-1",TouchableBounds(0,0,1200,800),"window-1","interaction-1")
        val snapshots=Snapshots(Clock {now}) {target}.apply {update(listOf(upper,lower));calibrate(upper.id,lower.id)}
        val captured=snapshots.capture()
        val frame=InputFrameBinding("session-a","run-a",captured.id,FrameTransform(100,50,1000,600,500,300))
        val raw=listOf(Point(200.0,100.0))
        fun fresh()=snapshots.captureReadCompletion(captured.id)
        fun use(current:Snapshot,frame:InputFrameBinding=this.frame)=snapshots.validateInputFrame(frame,"session-a","run-a",current.id,raw)
    }
    private fun rejected(block:()->Unit)=assertThrows(ToolFailure::class.java,block).also {assertEquals(Code.TARGET_CHANGED,it.code)}
    @Test fun captureFrameRemainsUsableAfterIndependentTreeReadSignsFreshSnapshot() {
        val f=Fixture();f.target=f.target.copy(revision="caret-or-animation-update")
        val tree=f.fresh();assertNotEquals(f.captured.id,tree.id)
        assertEquals(listOf(Point(500.0,250.0)),f.use(tree))
        assertEquals(f.captured.id,f.frame.snapshotId) // no rebinding of old pixel provenance
    }
    @Test fun differentSessionOrRunCannotReuseOtherwiseCompatibleFrame() {
        val f=Fixture();val tree=f.fresh()
        for((session,run) in listOf("other-session" to "run-a","session-a" to "other-run")) {
            val error=assertThrows(ToolFailure::class.java) {f.snapshots.validateInputFrame(f.frame,session,run,tree.id,f.raw)}
            assertEquals(Code.STALE_AUTHORIZATION,error.code)
        }
    }
    @Test fun expiredFrameCannotBorrowFreshAuthorizedSnapshotLifetime() {
        val f=Fixture();f.now=100;val tree=f.fresh();f.now=Snapshots.OBSERVATION_TTL_MS
        f.snapshots.validate(tree.id)
        rejected {f.use(tree)}
    }
    @Test fun scrollWindowOrAppChangesCannotBorrowNewTreeAuthority() {
        for(change in listOf<(Target)->Target>(
            {it.copy(interactionRevision="scrolled")},
            {it.copy(layoutRevision="different-window")},
            {it.copy(packageName="other.app")}
        )) {
            val f=Fixture();f.target=change(f.target);val current=f.snapshots.capture()
            rejected {f.use(current)}
        }
    }
    @Test fun roleRotationCalibrationAndVisibleGeometryMustRemainCompatible() {
        val role=Fixture();rejected {role.use(role.snapshots.capture("lower"))}
        val rotated=Fixture();rotated.snapshots.update(listOf(rotated.upper.copy(rotation=1),rotated.lower));rotated.snapshots.calibrate(7,9)
        rejected {rotated.use(rotated.snapshots.capture())}
        val geometry=Fixture();geometry.target=geometry.target.copy(touchableBounds=TouchableBounds(0,0,1100,800))
        rejected {geometry.use(geometry.snapshots.capture())}
    }
    @Test fun finalSubmissionRevalidationRejectsTargetAndClippingChangesAfterPreparation() {
        val f=Fixture();val tree=f.fresh();assertEquals(listOf(Point(500.0,250.0)),f.use(tree))
        f.target=f.target.copy(touchableBounds=TouchableBounds(0,0,400,800))
        rejected {f.use(tree)} // both old observations still exist, but final point is clipped
        f.target=f.target.copy(touchableBounds=TouchableBounds(0,0,1200,800),interactionRevision="scrolled-before-submit")
        rejected {f.use(tree)}
    }
    @Test fun frameTransformAndPixelsMustStayInsideBothAuthorizedRegions() {
        val f=Fixture();val tree=f.fresh()
        rejected {f.use(tree,f.frame.copy(transform=FrameTransform(1000,0,1000,600,500,300)))}
        val restricted=Fixture();restricted.target=restricted.target.copy(touchableBounds=TouchableBounds(0,0,400,800))
        val captured=restricted.snapshots.capture();val frame=restricted.frame.copy(snapshotId=captured.id)
        rejected {restricted.use(restricted.snapshots.capture(),frame)}
        assertThrows(ToolFailure::class.java) {f.snapshots.validateInputFrame(f.frame,"session-a","run-a",tree.id,listOf(Point(500.0,0.0)))}
    }
}
