package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class GamepadHoldTargetTest {
    @Test fun partialSubmissionThenRemoteFailurePropagatesThroughNestedTargetCheckpoints() {
        for(partiallySubmitted in listOf(false,true)) {
            val clock=Clock {1000};val snapshots=Snapshots(clock){Target("game","valid","content",layoutRevision="window")}
            snapshots.update(listOf(DisplayFact(0,"upper",800,600,0,240),DisplayFact(4,"lower",640,600,0,240)));snapshots.calibrate(0,4)
            val observed=snapshots.capture();val authority=ManualTestAuthority(clock);authority.set(Capability.SCREEN_CONTROL,true)
            var cleanups=0
            val backend=object:DeviceBackend {
                override fun availability(toolId:String)=Availability(Status.READY,"test","test")
                override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                    val held=checkpoint.checkingTarget(snapshots,envelope.snapshotId)
                    val nested=held.checkingTarget(snapshots,envelope.snapshotId)
                    try {
                        nested.effect {
                            // Same signal as a Userservice error with submitted=true.
                            if(partiallySubmitted)nested.submitted()
                            fail(Code.IO_ERROR,"Remote failed while starting held input")
                        }
                    } finally {cleanups++}
                    error("unreachable")
                }
                override fun release(runId:String)="test"
            }
            val lease=authority.begin(backend)
            val result=Gateway(authority,backend,snapshots,clock).execute(Envelope(lease.session,lease.run,"partial-hold","input.set_axes",lease.epoch,lease.deadline,observed.id),emptyMap())
            assertEquals(Code.IO_ERROR,result.error);assertEquals(if(partiallySubmitted)"unknown" else "not_submitted",result.outcome)
            assertTrue(result.data.isEmpty());assertEquals(1,cleanups)
        }
    }

    @Test fun pureContentFeedbackDoesNotCancelHoldOrPreventNextButton() {
        val clock=Clock {1000};var target=Target("game","valid","content-1",layoutRevision="window-1:focused:root-1")
        val snapshots=Snapshots(clock){target};snapshots.update(listOf(DisplayFact(0,"upper",800,600,0,240),DisplayFact(4,"lower",640,600,0,240)));snapshots.calibrate(0,4)
        val observed=snapshots.capture();val authority=ManualTestAuthority(clock);authority.set(Capability.SCREEN_CONTROL,true)
        var submissions=0;var cleanups=0
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"test","test")
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                val held=checkpoint.checkingTarget(snapshots,envelope.snapshotId)
                try {
                    held.check();held.effect {submissions++}
                    target=target.copy(revision="content-feedback-from-button")
                    held.check();assertTrue(checkpoint.submitted)
                    return ToolResult(clock.now(),"test",availability(envelope.toolId),outcome="submitted")
                } finally {cleanups++}
            }
            override fun release(runId:String)="test"
        }
        val lease=authority.begin(backend);val gate=Gateway(authority,backend,snapshots,clock)
        fun input(call:String)=Envelope(lease.session,lease.run,call,"input.press_button",lease.epoch,lease.deadline,observed.id)
        assertNull(gate.execute(input("hold-with-feedback"),emptyMap()).error);assertEquals(1,submissions);assertEquals(1,cleanups)
        assertNull(gate.execute(input("new-input-with-old-snapshot"),emptyMap()).error)
        assertEquals(2,submissions);assertEquals(2,cleanups)
    }
    @Test fun legacyTargetsStillTreatChangedRevisionAsChangedStructure() {
        val original=Target("game","valid","revision-1")
        assertFalse(original.sameStructure(original.copy(revision="revision-2")))
    }

    @Test fun submittedHoldStillChecksAppLayoutExpiryAndRevocationAndRunsCleanup() {
        for(change in listOf("app","layout","focus","window","root-bounds","validity","epoch","expiry","revoke")) {
            var now=1000L;val clock=Clock {now};var target=Target("original.game","valid","content-1",layoutRevision="window-1:focus-1:root-1")
            val snapshots=Snapshots(clock){target}
            snapshots.update(listOf(DisplayFact(0,"upper",800,600,0,240),DisplayFact(4,"lower",640,600,0,240)));snapshots.calibrate(0,4)
            val observed=snapshots.capture();now+=1000;val authority=ManualTestAuthority(clock);authority.set(Capability.SCREEN_CONTROL,true)
            var submitted=0;var released=0
            val backend=object:DeviceBackend {
                override fun availability(toolId:String)=Availability(Status.READY,"test","test")
                override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                    val held=checkpoint.checkingTarget(snapshots,envelope.snapshotId)
                    try {
                        held.effect {submitted++}
                        assertTrue("The outer gateway must retain submitted status",checkpoint.submitted)
                        when(change) {
                            "app"->target=target.copy(packageName="new.game")
                            "layout","focus","window","root-bounds"->target=target.copy(layoutRevision="$change-changed")
                            "validity"->target=target.copy(validity="ambiguous")
                            "epoch"->snapshots.calibrate(0,4)
                            "expiry"->now=observed.expires
                            "revoke"->authority.stop()
                        }
                        held.check() // The real IPC poll uses this same bound checkpoint.
                        fail("Changed held target must not continue: $change")
                    } finally {released++}
                    error("unreachable")
                }
                override fun release(runId:String)="test"
            }
            val lease=authority.begin(backend)
            val result=Gateway(authority,backend,snapshots,clock).execute(Envelope(lease.session,lease.run,"hold","input.press_button",lease.epoch,lease.deadline,observed.id),emptyMap())
            assertEquals(change,if(change=="revoke")Code.CANCELLED else Code.TARGET_CHANGED,result.error)
            assertEquals("unknown",result.outcome);assertTrue(result.data.isEmpty());assertEquals(1,submitted);assertEquals(1,released)
        }
    }
}
