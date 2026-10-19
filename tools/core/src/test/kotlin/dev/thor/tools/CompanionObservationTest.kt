package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class CompanionObservationTest {
    private val upper=DisplayFact(0,"upper",1920,1080,1,320)
    private val lower=DisplayFact(4,"lower",1240,1080,1,320)
    private val upperRect=TouchableBounds(0,0,1920,1080)
    private val lowerRect=TouchableBounds(0,0,1240,1080)
    private class Fixture(val upper:DisplayFact,val lower:DisplayFact) {
        var now=0L
        val targets=mutableMapOf(upper.id to Target("game.app","valid","upper-content",TouchableBounds(0,0,upper.width,upper.height),"upper-layout"),
            lower.id to Target("game.app","valid","lower-content",TouchableBounds(0,0,lower.width,lower.height),"lower-layout"))
        val snapshots=Snapshots(Clock {now}) {targets.getValue(it)}.apply {update(listOf(upper,lower));calibrate(upper.id,lower.id)}
        val source=snapshots.capture()
        fun companion()=snapshots.captureCompanion(source.id,setOf("com.tobias.loki"))
    }
    @Test fun sameAppCompanionHasSeparateNonInputIdentityAndReturnsUpperSnapshot() {
        val f=Fixture(upper,lower);val observation=f.companion()
        assertEquals(lower,observation.lowerDisplay);assertEquals(upper,observation.upper.display)
        rejects(Code.TARGET_CHANGED) {f.snapshots.validateReadSource(observation.id)}
        val completed=f.snapshots.completeCompanion(observation)
        assertEquals(upper,completed.display);assertNotEquals(f.source.id,completed.id)
        assertEquals(Capability.SCREEN_PERCEPTION,ToolPack.descriptors.getValue("perception.capture_companion").capability)
        assertEquals(Effect.READ,ToolPack.descriptors.getValue("perception.capture_companion").effect)
    }
    @Test fun unknownAmbiguousOwnAndOtherAppCannotBecomeCompanion() {
        for(target in listOf(Target("other.app","valid","x",lowerRect,"x"),Target("game.app","ambiguous","x",lowerRect,"x"),Target(null,"unknown","x"))) {
            val f=Fixture(upper,lower);f.targets[4]=target;rejects(Code.PERMISSION_MISSING) {f.companion()}
        }
        val f=Fixture(upper,lower);f.targets[0]=f.targets.getValue(0).copy(packageName="com.tobias.loki");f.targets[4]=f.targets.getValue(4).copy(packageName="com.tobias.loki")
        rejects(Code.PERMISSION_MISSING) {f.companion()}
        val covered=Fixture(upper,lower);covered.targets[0]=covered.targets.getValue(0).copy(touchableBounds=TouchableBounds(0,0,0,0))
        rejects(Code.SECURE_CONTENT) {covered.companion()}
    }
    @Test fun contentChangesAreReadableButWindowGeometryRoleAndExpiryChangesReject() {
        val f=Fixture(upper,lower);val o=f.companion()
        f.targets[0]=f.targets.getValue(0).copy(revision="ram-2");f.targets[4]=f.targets.getValue(4).copy(revision="game-animation-2")
        f.snapshots.validateCompanion(o)
        assertEquals(o.upper,f.snapshots.validate(o.upper.id)) // Animation alone does not move the upper input target.
        for(change in listOf(f.targets.getValue(4).copy(layoutRevision="new-dialog"),f.targets.getValue(4).copy(packageName="other.app"),f.targets.getValue(4).copy(touchableBounds=TouchableBounds(0,1,1240,1080)))) {
            val original=f.targets.getValue(4);f.targets[4]=change;rejects(Code.TARGET_CHANGED) {f.snapshots.validateCompanion(o)};f.targets[4]=original
        }
        f.now=60000;rejects(Code.TARGET_CHANGED) {f.snapshots.validateCompanion(o)}
        val rotated=Fixture(upper,lower);val r=rotated.companion();rotated.snapshots.update(listOf(upper,lower.copy(rotation=2)))
        rejects(Code.TARGET_CHANGED) {rotated.snapshots.validateCompanion(r)}
        val swapped=Fixture(upper,lower);val s=swapped.companion();swapped.snapshots.calibrate(4,0)
        rejects(Code.TARGET_CHANGED) {swapped.snapshots.validateCompanion(s)}
    }
    private fun app(id:Int=1,pkg:String="game.app",region:List<TouchableBounds> = listOf(lowerRect))=
        TargetWindowEvidence(id,TargetWindowEvidence.APPLICATION,3,true,true,lowerRect,region,true,pkg,lowerRect)
    @Test fun onlyUniqueSameAppVisiblePixelsWithNoOverlayCanBeCaptured() {
        val bars=TargetWindowEvidence(2,TargetWindowEvidence.SYSTEM,4,false,false,TouchableBounds(0,0,1240,40),listOf(TouchableBounds(0,0,1240,40)),true,null,null)
        val state=CompanionWindows.verify(lower,"game.app",listOf(app(region=listOf(TouchableBounds(0,40,1240,1080))),bars),setOf("com.tobias.loki"))
        assertEquals(TouchableBounds(0,40,1240,1080),state.clip)
        rejects(Code.TARGET_CHANGED) {CompanionWindows.verify(lower,"game.app",listOf(app(),app(id=3)),emptySet())}
        rejects(Code.PERMISSION_MISSING) {CompanionWindows.verify(lower,"game.app",listOf(app(pkg="com.tobias.loki")),setOf("com.tobias.loki"))}
        rejects(Code.PERMISSION_MISSING) {CompanionWindows.verify(lower,"game.app",listOf(app(pkg="other.app")),emptySet())}
        for(type in listOf(TargetWindowEvidence.SYSTEM,2,4)) {
            val overlay=bars.copy(type=type,bounds=TouchableBounds(100,100,800,800),region=listOf(TouchableBounds(100,100,800,800)))
            rejects(Code.SECURE_CONTENT) {CompanionWindows.verify(lower,"game.app",listOf(app(),overlay),emptySet())}
        }
        rejects(Code.TARGET_CHANGED) {CompanionWindows.verify(lower,"game.app",listOf(app().copy(rectangular=false)),emptySet())}
    }
    @Test fun outputCannotSmuggleCompanionIdentityIntoUpperInputAuthority() {
        val f=Fixture(upper,lower);val o=f.companion();val completion=f.snapshots.completeCompanion(o)
        val data=mapOf<String,Any?>("readOnly" to true,"frameInputUsable" to false,"displayRole" to "lower-companion-read-only","coordinateSpace" to "companion_read_only",
            "displayId" to 4,"authorizedInputDisplayId" to 0,"targetSnapshotId" to completion.id,"companionObservationId" to o.id,"companionImageTransform" to mapOf("identity" to o.id))
        f.snapshots.validateCompanionResult(completion.id,data)
        for(change in listOf(mapOf("frameId" to o.id),mapOf("frameInputUsable" to true),mapOf("readOnly" to false),mapOf("displayId" to 0),
            mapOf("authorizedInputDisplayId" to 4),mapOf("targetSnapshotId" to o.id),mapOf("companionObservationId" to completion.id),mapOf("companionImageTransform" to mapOf("identity" to "foreign"))))
            rejects(Code.TARGET_CHANGED) {f.snapshots.validateCompanionResult(completion.id,data+change)}
    }
    private fun rejects(code:Code,block:()->Unit) {try {block();fail("Unsafe companion observation accepted")}catch(e:ToolFailure){assertEquals(code,e.code)}}
}
