package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class ReadObservationTest {
    private val upper=DisplayFact(0,"upper",1920,1080,0,240)
    private val lower=DisplayFact(4,"lower",1240,1080,0,240)
    private val bounds=TouchableBounds(0,0,1920,1080)
    private fun initial()=Target("emulator.game","valid","text-1",bounds,"window-1:focused:geometry-1")
    private fun snapshots(clock:Clock,current:()->Target)=Snapshots(clock){current()}.apply {update(listOf(upper,lower));calibrate(0,4)}
    private fun rejected(action:()->Unit) {assertEquals(Code.TARGET_CHANGED,assertThrows(ToolFailure::class.java,action).code)}
    @Test fun changingCounterTextDuringReadKeepsCaptureIdentitySeparateFromFreshCompletion() {
        var current=initial();val snapshots=snapshots(Clock {1000}){current};val source=snapshots.capture()
        val capture=snapshots.captureForRead(source.id)
        repeat(100) {current=current.copy(revision="RAM-$it");assertEquals(capture,snapshots.validateObservation(capture.id))}
        val completed=snapshots.captureReadCompletion(capture.id)
        assertNotEquals(capture.id,completed.id);assertEquals("text-1",capture.target.revision);assertEquals("RAM-99",completed.target.revision)
        assertFalse("New content identity must not pretend to be captured pixels",capture.target.sameLayout(completed.target))
        rejected {snapshots.validate(capture.id)};assertEquals(completed,snapshots.validate(completed.id))
        current=current.copy(revision="RAM-100")
        assertEquals(completed,snapshots.validateObservation(completed.id));rejected {snapshots.validate(completed.id)}
    }
    @Test fun packageWindowFocusGeometryClippingAndValidityStillInvalidateARead() {
        for(change in listOf<(Target)->Target>(
            {it.copy(packageName="other.app")},{it.copy(validity="ambiguous")},
            {it.copy(layoutRevision="other-window")},{it.copy(layoutRevision="focus-lost")},
            {it.copy(layoutRevision="overlay-added")},{it.copy(layoutRevision="root-bounds-changed")},
            {it.copy(touchableBounds=TouchableBounds(0,72,1920,1080))},{it.copy(touchableBounds=null)}
        )) {
            var current=initial();val snapshots=snapshots(Clock {1000}){current};val capture=snapshots.capture()
            current=change(current)
            rejected {snapshots.validateObservation(capture.id)};rejected {snapshots.captureReadCompletion(capture.id)}
        }
    }
    @Test fun expiryRotationRoleAndDisplayEpochGuardsStillApply() {
        for(change in listOf("expiry","rotation","role","display-removal")) {
            var now=1000L;val snapshots=snapshots(Clock {now}){initial()};val capture=snapshots.capture()
            when(change) {
                "expiry"->now=capture.expires
                "rotation"->snapshots.update(listOf(upper.copy(rotation=1),lower))
                "role"->snapshots.calibrate(4,0)
                else->snapshots.update(listOf(lower))
            }
            rejected {snapshots.validateObservation(capture.id)};rejected {snapshots.captureReadCompletion(capture.id)}
        }
    }
    @Test fun changedStructureBetweenReadValidationAndCompletionCannotBeSignedAsTheOldRead() {
        var reads=0;var changing=false
        val snapshots=snapshots(Clock {1000}) {
            if(changing && ++reads>=2)initial().copy(layoutRevision="replacement-window") else initial()
        }
        val capture=snapshots.capture();changing=true
        rejected {snapshots.captureReadCompletion(capture.id)}
    }
    @Test fun gatewayReturnsPureReadDespiteContentUpdatesBeforeItsFinalValidation() {
        var current=initial();val clock=Clock {1000};val snapshots=snapshots(clock){current};val source=snapshots.capture()
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"test","read-fixture")
            override fun release(runId:String)="none"
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                val capture=snapshots.captureForRead(envelope.snapshotId)
                repeat(10){current=current.copy(revision="RAM-$it");checkpoint.check();snapshots.validateObservation(capture.id)}
                val completed=snapshots.captureReadCompletion(capture.id)
                current=current.copy(revision="RAM-after-read")
                return ToolResult(clock.now(),"read-fixture",availability(envelope.toolId),mapOf("pixels" to "captured-earlier","captureSnapshotId" to capture.id,"targetSnapshotId" to completed.id),snapshotId=completed.id)
            }
        }
        val authority=ManualTestAuthority(clock);val lease=authority.begin(backend)
        val result=Gateway(authority,backend,snapshots,clock).execute(Envelope(lease.session,lease.run,"read","perception.capture_screen",lease.epoch,lease.deadline,source.id),emptyMap())
        assertNull(result.error);assertEquals("captured-earlier",result.data["pixels"])
        assertNotEquals(result.data["captureSnapshotId"],result.data["targetSnapshotId"])
        rejected {snapshots.validate(result.snapshotId)} // Never relax a subsequent coordinate input.
    }
    @Test fun fixedBackCanStartAfterContentUpdateWhileCoordinateAndExistingScrollInputsRemainStrict() {
        val clock=Clock {1000};var current=initial();val snapshots=snapshots(clock){current};val source=snapshots.capture()
        current=current.copy(revision="RAM-before-input");var entries=0;var submissions=0
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"test","navigation-fixture")
            override fun release(runId:String)="none"
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                entries++;assertEquals("input.navigate",envelope.toolId)
                NavigationCommand.parse(args);current=current.copy(revision="RAM-before-BACK")
                checkpoint.effect {submissions++}
                return ToolResult(clock.now(),"navigation-fixture",availability(envelope.toolId),outcome="submitted")
            }
        }
        val authority=ManualTestAuthority(clock);authority.set(Capability.SCREEN_CONTROL,true);val lease=authority.begin(backend)
        val gateway=Gateway(authority,backend,snapshots,clock)
        fun envelope(tool:String)=Envelope(lease.session,lease.run,tool,tool,lease.epoch,lease.deadline,source.id)
        for(tool in listOf("input.tap","input.long_press","input.swipe","input.drag","input.scroll","input.press_button","input.set_axes"))
            assertEquals(tool,Code.TARGET_CHANGED,gateway.execute(envelope(tool),emptyMap()).error)
        assertEquals(0,entries)
        assertNull(gateway.execute(envelope("input.navigate"),mapOf("action" to "back")).error)
        assertEquals(1,entries);assertEquals(1,submissions)
    }
    @Test fun fixedBackStillRejectsWindowOrTouchableGeometryChangesBeforeCommit() {
        for(change in listOf<(Target)->Target>({it.copy(layoutRevision="overlay")},{it.copy(packageName="other.app")},{it.copy(touchableBounds=TouchableBounds(0,70,1920,1080))})) {
            val clock=Clock {1000};var current=initial();val snapshots=snapshots(clock){current};val source=snapshots.capture();var submissions=0
            val backend=object:DeviceBackend {
                override fun availability(toolId:String)=Availability(Status.READY,"test","navigation-fixture")
                override fun release(runId:String)="none"
                override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                    current=change(current);checkpoint.effect {submissions++}
                    return ToolResult(clock.now(),"test",availability(envelope.toolId))
                }
            }
            val authority=ManualTestAuthority(clock);authority.set(Capability.SCREEN_CONTROL,true);val lease=authority.begin(backend)
            val result=Gateway(authority,backend,snapshots,clock).execute(Envelope(lease.session,lease.run,"back","input.navigate",lease.epoch,lease.deadline,source.id),mapOf("action" to "back"))
            assertEquals(Code.TARGET_CHANGED,result.error);assertEquals(0,submissions)
        }
    }
}
