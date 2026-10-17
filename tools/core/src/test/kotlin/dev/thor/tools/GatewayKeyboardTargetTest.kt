package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

/** Exercise the real Gateway checkpoint routing, not only Snapshots helpers. */
class GatewayKeyboardTargetTest {
    private val semanticTools=listOf("input.type_text","input.key_press","input.activate","input.navigate")
    private class Fixture {
        val clock=Clock {1000L}
        val upper=DisplayFact(0,"upper",1920,1080,0,320)
        val lower=DisplayFact(4,"lower",1240,1080,0,320)
        var target=Target("editor.app","valid","content-1",TouchableBounds(0,0,1240,1080),"window-1","interaction-1")
        val snapshots=Snapshots(clock) {target}.apply {update(listOf(upper,lower));calibrate(0,4)}
        val observed=snapshots.capture()
        var executions=0
        var commits=0
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"TEST only","fake")
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                executions++
                checkpoint.effect {commits++}
                return ToolResult(clock.now(),"fake",availability(envelope.toolId),outcome="submitted")
            }
            override fun release(runId:String)="test"
        }
        val authority=ManualTestAuthority(clock).apply {set(Capability.SCREEN_CONTROL,true)}
        val lease=authority.begin(backend)
        fun envelope(tool:String)=Envelope(lease.session,lease.run,tool,tool,lease.epoch,lease.deadline,observed.id)
        fun execute(tool:String)=Gateway(authority,backend,snapshots,clock).execute(envelope(tool),emptyMap())
    }
    @Test fun unrelatedInteractionChangesReachNonCoordinateBackendsButNeverCoordinateInputs() {
        for(tool in semanticTools) {
            val f=Fixture();f.target=f.target.copy(revision="new-content",interactionRevision="other-list-scrolled")
            assertNull(tool,f.execute(tool).error)
            assertEquals(tool,1,f.executions);assertEquals(tool,1,f.commits)
        }
        val f=Fixture();f.target=f.target.copy(interactionRevision="other-list-scrolled")
        assertEquals(Code.TARGET_CHANGED,f.execute("input.tap").error)
        assertEquals(0,f.executions);assertEquals(0,f.commits)
    }
    @Test fun realWindowAppAndVisibleRegionChangesStillStopBeforeAnyBackend() {
        for(tool in semanticTools)for(change in listOf<(Target)->Target>(
            {it.copy(layoutRevision="dialog-or-other-window")},
            {it.copy(packageName="other.app")},
            {it.copy(touchableBounds=TouchableBounds(0,20,1240,1080))})) {
            val f=Fixture();f.target=change(f.target)
            assertEquals(tool,Code.TARGET_CHANGED,f.execute(tool).error)
            assertEquals(tool,0,f.executions);assertEquals(tool,0,f.commits)
        }
    }
    @Test fun calibrationRoleSwapAndDisplayEpochChangesInvalidateOldKeyboardObservations() {
        for(tool in semanticTools)for(swapRoles in listOf(false,true)) {
            val f=Fixture()
            if(swapRoles)f.snapshots.calibrate(4,0)
            else f.snapshots.update(listOf(f.upper.copy(rotation=1,width=1080,height=1920),f.lower))
            assertEquals(tool,Code.TARGET_CHANGED,f.execute(tool).error)
            assertEquals(tool,0,f.executions);assertEquals(tool,0,f.commits)
        }
    }
    @Test fun aRealWindowChangeAfterDispatchStillFailsTheFinalCommitCheckpoint() {
        for(tool in semanticTools) {
            val f=Fixture()
            val racingBackend=object:DeviceBackend by f.backend {
                override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                    f.target=f.target.copy(layoutRevision="new-modal-window")
                    return f.backend.execute(envelope,args,checkpoint)
                }
            }
            val result=Gateway(f.authority,racingBackend,f.snapshots,f.clock).execute(f.envelope(tool),emptyMap())
            assertEquals(tool,Code.TARGET_CHANGED,result.error)
            assertEquals(tool,1,f.executions);assertEquals(tool,0,f.commits)
            assertEquals("not_submitted",result.outcome)
        }
    }
    @Test fun runSelectedRoleAuthorizationIsStillCheckedBeforeSemanticDispatch() {
        for(tool in semanticTools) {
            val f=Fixture();var selectedRole="upper"
            val roleBoundAuthority=object:AuthorizationPort {
                fun checkRole(e:Envelope) {requireTool(f.snapshots.validateReadSource(e.snapshotId).role==selectedRole,
                    Code.TARGET_CHANGED,"Run-selected screen changed")}
                override fun validate(envelope:Envelope):Cancellation {val cancellation=f.authority.validate(envelope);checkRole(envelope);return cancellation}
                override fun claim(e:Envelope) {checkRole(e);f.authority.claim(e)}
                override fun commit(envelope:Envelope,effect:()->Unit) {checkRole(envelope);f.authority.commit(envelope,effect)}
            }
            // This leaves the old upper snapshot alive: refusal must be authority scope,
            // rather than incidental invalidation caused by recalibration or expiry.
            selectedRole="lower"
            val result=Gateway(roleBoundAuthority,f.backend,f.snapshots,f.clock).execute(f.envelope(tool),emptyMap())
            assertEquals(tool,Code.TARGET_CHANGED,result.error)
            assertEquals(tool,0,f.executions);assertEquals(tool,0,f.commits)
        }
    }
}
