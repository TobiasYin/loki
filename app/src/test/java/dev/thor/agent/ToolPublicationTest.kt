package dev.thor.agent

import dev.thor.tools.*
import org.junit.Assert.*
import org.junit.Test

class ToolPublicationTest {
    private val input=ToolPack.descriptors.getValue("input.tap")
    private val ready=Availability(Status.READY,"Backend ready","native-fixture")
    private val upper=DisplayFact(0,"upper",800,600,0,160)
    private val lower=DisplayFact(4,"lower",640,600,0,160)
    private val rect=TouchableBounds(0,0,800,600)
    private fun rejection(a:Availability)=ToolPublication.projectedRejection(a,true,true)?.let {mapOf(input.toolId to it)}.orEmpty()
    private fun assertTyped(code:Code,rejections:Map<String,Code>) {
        val failure=assertThrows(ToolFailure::class.java) {ToolPublication.requirePublished(input.toolId,emptySet(),rejections)}
        assertEquals(code,failure.code)
    }
    @Test fun realDialogObservationInvalidationSurvivesRemovalFromThePublishedSet() {
        val app=TargetWindowEvidence(10,1,1,true,true,rect,listOf(rect),true,"target.app",rect)
        val revisions=TargetWindowRevisions();var windows=listOf(app)
        val snapshots=Snapshots(Clock {1}) {UpperTargetResolver.resolve(upper,windows,revisions)}
        snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val original=snapshots.capture()
        val dialog=app.copy(id=11,layer=2,bounds=TouchableBounds(100,100,700,500),region=listOf(TouchableBounds(100,100,700,500)),rootBounds=TouchableBounds(100,100,700,500))
        windows=listOf(app.copy(focused=false),dialog)
        val projected=TargetObservationAvailability.project(ready,input,original,"upper") {snapshots.validateObservation(original.id)}
        assertEquals(Status.TEMPORARILY_UNAVAILABLE,projected.status)
        val published=if(projected.status==Status.READY)setOf(input.toolId) else emptySet()
        assertTrue(published.isEmpty())
        assertTyped(Code.TARGET_CHANGED,rejection(projected))
    }
    @Test fun exactPermissionSecureAndDisplayRefusalsKeepTheirOriginalCodes() {
        val snapshot=Snapshot("observation",upper,1,Target("app","valid","v1",rect,"window"),100)
        for(code in listOf(Code.PERMISSION_MISSING,Code.SECURE_CONTENT,Code.DISPLAY_GONE)) {
            val projected=TargetObservationAvailability.project(ready,input,snapshot,"upper") {throw ToolFailure(code,"Fixed Native admission refusal")}
            assertTyped(code,rejection(projected))
        }
        val missing=TargetObservationAvailability.project(ready,input,null,"upper") {fail("Missing snapshot must not validate")}
        assertTyped(Code.DISPLAY_GONE,rejection(missing))
    }
    @Test fun nonReadyHardwareUnknownReasonsAndMissingPublicationAreNotTargetRecovery() {
        for(a in listOf(Availability(Status.UNSUPPORTED,"No hardware","native"),Availability(Status.PERMISSION_MISSING,"No permission","native"),
            Availability(Status.TEMPORARILY_UNAVAILABLE,"Real backend busy","native"),
            Availability(Status.TEMPORARILY_UNAVAILABLE,"Unknown failure","native",Code.IO_ERROR),
            Availability(Status.UNSUPPORTED,"Unsupported wins","native",Code.TARGET_CHANGED),
            Availability(Status.READY,"Ready does not carry a refusal","native",Code.TARGET_CHANGED))) {
            assertNull(ToolPublication.projectedRejection(a,true,true))
            assertThrows(IllegalArgumentException::class.java) {ToolPublication.requirePublished(input.toolId,emptySet(),rejection(a))}
        }
        assertThrows(IllegalArgumentException::class.java) {ToolPublication.requirePublished("unknown.tool",emptySet(),mapOf(input.toolId to Code.TARGET_CHANGED))}
        assertThrows(IllegalArgumentException::class.java) {ToolPublication.requirePublished(input.toolId,emptySet(),mapOf(input.toolId to Code.BACKEND_UNAVAILABLE))}
    }
    @Test fun disabledCapabilityAndModelWithoutToolsCannotInheritAnOldTargetReason() {
        val stale=ready.copy(status=Status.TEMPORARILY_UNAVAILABLE,reasonCode=Code.TARGET_CHANGED)
        for((capability,model) in listOf(false to true,true to false,false to false)) {
            val next=ToolPublication.projectedRejection(stale,capability,model)?.let {mapOf(input.toolId to it)}.orEmpty()
            assertTrue(next.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {ToolPublication.requirePublished(input.toolId,emptySet(),next)}
        }
    }
    @Test fun aFreshProjectionReplacesOldReasonsAndPublishedToolsStillRequireNormalExecutionChecks() {
        val old=rejection(ready.copy(status=Status.TEMPORARILY_UNAVAILABLE,reasonCode=Code.TARGET_CHANGED))
        assertTyped(Code.TARGET_CHANGED,old)
        val fresh=rejection(ready)
        assertTrue(fresh.isEmpty())
        ToolPublication.requirePublished(input.toolId,setOf(input.toolId),old) // No authorization is granted by this membership check.
        assertThrows(IllegalArgumentException::class.java) {ToolPublication.requirePublished(input.toolId,emptySet(),fresh)}
    }
}
