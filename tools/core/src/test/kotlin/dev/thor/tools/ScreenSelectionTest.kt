package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class ScreenSelectionTest {
    private val upper=DisplayFact(0,"upper",1920,1080,1,320)
    private val lower=DisplayFact(4,"lower",1240,1080,1,320)
    private fun snapshots()=Snapshots(Clock {0}) {id->Target(if(id==0)"first.app" else "different.app","valid","content-$id",TouchableBounds(0,0,if(id==0)1920 else 1240,1080),"layout-$id")}
        .apply {update(listOf(upper,lower));calibrate(0,4)}
    @Test fun lowerSelectionAndSubsequentReadsStayOnLowerWithoutChangingGlobalCalibration() {
        val s=snapshots();val original=s.capture();val selected=s.selectScreen(original.id,"lower")
        assertEquals("upper",original.role);assertEquals("lower",selected.role);assertEquals(lower,selected.display)
        assertEquals("different.app",selected.target.packageName) // Dual-screen control is not limited to one app.
        assertEquals("lower",s.captureForRead(selected.id).role);assertEquals(lower,s.captureReadCompletion(selected.id).display)
        assertEquals(upper,s.selected());assertEquals("upper",s.capture().role);assertEquals("upper",s.captureForRead(original.id).role)
        assertEquals(selected,s.validate(selected.id))
    }
    @Test fun selectingAScreenDoesNotGrantInputCapability() {
        val d=ToolPack.descriptors.getValue("perception.select_screen")
        assertEquals(Capability.SCREEN_PERCEPTION,d.capability);assertEquals(Effect.READ,d.effect)
        val s=snapshots();val selected=s.selectScreen(s.capture().id,"lower")
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"fixture","fixture")
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult=error("Disabled input reached backend")
            override fun release(runId:String)=""
        }
        val authority=ManualTestAuthority(Clock {0});val lease=authority.begin(backend)
        val result=Gateway(authority,backend,s,Clock {0}).execute(Envelope(lease.session,lease.run,"input","input.tap",lease.epoch,lease.deadline,selected.id),emptyMap())
        assertEquals(Code.CAPABILITY_DISABLED,result.error)
    }
    @Test fun callersCannotSelectArbitraryDisplayIdsOrMutateAnotherRun() {
        assertEquals("lower",ScreenSelection.parse(mapOf("screen" to "lower")))
        for(args in listOf(emptyMap(),mapOf("screen" to "4"),mapOf("screen" to 4),mapOf("screen" to "upper","displayId" to 0),mapOf("screen" to "lower","runId" to "other")))
            rejects(Code.INVALID_ARGUMENT) {ScreenSelection.parse(args)}
    }
    @Test fun lowerOwnUnknownAmbiguousOrNativeUnsafeTargetsAreRejected() {
        for(target in listOf(Target("dev.thor.agent","valid","x",TouchableBounds(0,0,1240,1080)),Target(null,"unknown","x"),Target("other.app","ambiguous","x"))) {
            val s=Snapshots(Clock {0}) {id->if(id==4)target else Target("first.app","valid","x")}.apply {update(listOf(upper,lower));calibrate(0,4)}
            rejects(Code.PERMISSION_MISSING) {s.selectScreen(s.capture().id,"lower")}
        }
        val s=snapshots();s.installLowerGuard {_,_->fail(Code.SECURE_CONTENT,"Visible password or secure overlay")}
        rejects(Code.SECURE_CONTENT) {s.selectScreen(s.capture().id,"lower")}
    }
    @Test fun lowerCoordinatesStillUseLowerGeometryAndCannotReuseUpperFrameNamespace() {
        val s=snapshots();val source=s.capture();val selected=s.selectScreen(source.id,"lower")
        s.validateInputPoints(selected.id,listOf(Point(1200.0,500.0)))
        rejects(Code.TARGET_CHANGED) {s.validateInputPoints(selected.id,listOf(Point(1600.0,500.0)))}
        assertNotEquals(source.id,selected.id);assertNotEquals(s.validate(source.id).display,s.validate(selected.id).display)
        s.calibrate(4,0);rejects(Code.TARGET_CHANGED) {s.validate(selected.id)}
    }
    @Test fun onlySelectScreenMayReturnADifferentRoleInTheFreshReadChain() {
        for(tool in listOf("perception.select_screen","perception.app_info")) {
            val s=snapshots();val source=s.capture()
            val backend=object:DeviceBackend {
                override fun availability(toolId:String)=Availability(Status.READY,"fixture","fixture")
                override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                    val selected=s.selectScreen(envelope.snapshotId,"lower")
                    return ToolResult(0,"fixture",availability(envelope.toolId),mapOf("selectedScreen" to "lower","displayRole" to "lower","displayId" to 4,
                        "targetSnapshotId" to selected.id,"readOnlySelection" to true,"calibrationChanged" to false),snapshotId=selected.id)
                }
                override fun release(runId:String)=""
            }
            val authority=ManualTestAuthority(Clock {0});val lease=authority.begin(backend)
            val result=Gateway(authority,backend,s,Clock {0}).execute(Envelope(lease.session,lease.run,tool,tool,lease.epoch,lease.deadline,source.id),mapOf("screen" to "lower"))
            assertEquals(if(tool=="perception.select_screen")null else Code.TARGET_CHANGED,result.error)
        }
    }
    @Test fun sameRoleCannotForgeSelectionMetadataOrCompanionShortcutOnLower() {
        val s=snapshots();val source=s.capture();val selected=s.selectScreen(source.id,"lower")
        rejects(Code.TARGET_CHANGED) {ScreenSelection.verifyResult(source,selected,mapOf("selectedScreen" to "upper"))}
        rejects(Code.INVALID_ARGUMENT) {s.captureCompanion(selected.id,emptySet())}
    }
    private fun rejects(code:Code,block:()->Unit) {try {block();fail("Unsafe screen selection accepted")}catch(e:ToolFailure){assertEquals(code,e.code)}}
}
