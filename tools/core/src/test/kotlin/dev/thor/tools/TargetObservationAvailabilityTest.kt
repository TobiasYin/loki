package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class TargetObservationAvailabilityTest {
    private val base=Availability(Status.READY,"Actual backend ready","real-backend")
    private val input=ToolPack.descriptors.getValue("input.tap")
    private val display=DisplayFact(4,"lower",960,640,0,160)
    private val snapshot=Snapshot("observed",display,1,Target("target.app","valid","revision",TouchableBounds(0,0,960,640)),10000,"lower")
    @Test fun staleObservationStaysNonReadyAndHasExactRecoveryReasonWithoutExecutingAnything() {
        var validations=0
        val value=TargetObservationAvailability.project(base,input,snapshot,"lower") {validations++;throw ToolFailure(Code.TARGET_CHANGED,"Observed layout changed")}
        assertEquals(1,validations);assertEquals(Status.TEMPORARILY_UNAVAILABLE,value.status);assertEquals(Code.TARGET_CHANGED,value.reasonCode);assertEquals(base.backend,value.backend)
    }
    @Test fun realBackendFailuresHavePriorityAndNeverInvokeTargetValidator() {
        for(status in Status.entries.filter {it!=Status.READY}) {
            val actual=Availability(status,"Real platform state","real-backend")
            val result=TargetObservationAvailability.project(actual,input,snapshot,"upper") {fail("Unavailable backend must remain untouched")}
            assertEquals(actual,result);assertNull(result.reasonCode)
        }
    }
    @Test fun hardTargetRefusalsKeepTheirOwnCategoryRatherThanRecoverableTargetChanged() {
        for(code in listOf(Code.PERMISSION_MISSING,Code.SECURE_CONTENT,Code.DISPLAY_GONE)) {
            val result=TargetObservationAvailability.project(base,input,snapshot,"lower") {throw ToolFailure(code,"Fixed Native refusal")}
            assertEquals(code,result.reasonCode);assertNotEquals(Status.READY,result.status)
            if(code==Code.PERMISSION_MISSING)assertEquals(Status.PERMISSION_MISSING,result.status)
        }
    }
    @Test fun genericValidationFailuresDoNotAcquireTheTargetRecoveryTag() {
        for(code in listOf(Code.BACKEND_UNAVAILABLE,Code.CANCELLED,Code.IO_ERROR,Code.UNSUPPORTED)) {
            val result=TargetObservationAvailability.project(base,input,snapshot,"lower") {throw ToolFailure(code,"Not a target drift signal")}
            assertEquals(Status.TEMPORARILY_UNAVAILABLE,result.status);assertNull(result.reasonCode)
        }
        assertNull(TargetObservationAvailability.project(base,input,snapshot,"lower") {error("Unexpected implementation error")}.reasonCode)
    }
    @Test fun calibrationAndRoleLossRemainExplicitWhileFixedBackgroundRequiresNoTarget() {
        assertEquals(Code.DISPLAY_GONE,TargetObservationAvailability.project(base,input,null,"lower") {}.reasonCode)
        assertEquals(Code.TARGET_CHANGED,TargetObservationAvailability.project(base,input,snapshot,"upper") {}.reasonCode)
        assertEquals(base,TargetObservationAvailability.project(base,ToolPack.descriptors.getValue("agent.background"),null,"lower") {fail("Background has no screen target")})
    }
    @Test fun freshReadsAndHealthyInputKeepExistingPolicyAndUnknownTargetNeverBecomesReady() {
        assertEquals(base,TargetObservationAvailability.project(base,input,snapshot,"lower") {})
        val unknown=snapshot.copy(target=snapshot.target.copy(validity="unknown"))
        val lost=TargetObservationAvailability.project(base,input,unknown,"lower") {}
        assertEquals(Status.TEMPORARILY_UNAVAILABLE,lost.status);assertEquals(Code.TARGET_CHANGED,lost.reasonCode)
        assertEquals(base,TargetObservationAvailability.project(base,ToolPack.descriptors.getValue("perception.capture_screen"),snapshot,"lower") {fail("Fresh reads own their admission checks")})
        assertEquals(base,TargetObservationAvailability.project(base,ToolPack.descriptors.getValue("perception.select_screen"),snapshot,"upper") {})
    }
}
