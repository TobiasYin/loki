package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test

class DeviceContinuityTest {
    private val window=mapOf<String,Any?>("hasWindowFocus" to true,"lifecycle" to "resumed","actualWindowDisplayId" to 0,"layoutHash" to "fixed","rotation" to 1)
    private val displays=listOf(mapOf<String,Any?>("displayId" to 0,"rotation" to 1),mapOf("displayId" to 4,"rotation" to 1))
    private fun device(id: Int)=mapOf<String,Any?>("available" to true,"deviceId" to id,"descriptor" to "descriptor-$id","name" to "device-$id",
        "sources" to Sources.TOUCHSCREEN,"isEnabled" to true,"supportedKeyCodes" to listOf(96),"motionRanges" to listOf(mapOf("axis" to 0,"max" to 1920f,"fuzz" to 0f)))
    private val devices=mapOf(7 to device(7),30 to device(30))
    private fun decision(id: Int=7,change: String="changed",current: Map<Int,Map<String,Any?>> = devices,
        currentWindow: Map<String,Any?> = window,currentDisplays: List<Map<String,Any?>> = displays,
        held: Boolean=false,uncertain: Boolean=false)=DeviceContinuity(devices,window,displays).assess(id,change,current,currentWindow,currentDisplays,held,uncertain)

    @Test fun identicalIdleCallbackRemainsDiagnosticAndCannotSupplyInput() {
        assertTrue(decision().continuous)
        val a=Attempt("LAB-02",Bounds(0f,0f,100f,100f),Bounds(200f,0f,300f,100f),0).also { it.arm() }
        assertEquals("fail",a.finish(5000).status)
        assertEquals(false,decision().map()["routingProven"])
    }
    @Test fun identicalCallbacksAfterRealTapAndControllerPairPreserveSuccessfulEvidence() {
        val target=Bounds(0f,0f,100f,100f)
        val touch=Attempt("LAB-02",target,target,0).also { it.arm() }
        touch.accept(Raw("touch",0,10,10,device=7,samples=listOf(Sample(10,50f,50f))))
        touch.accept(Raw("touch",1,100,10,device=7,samples=listOf(Sample(100,50f,50f))))
        val touchDecision=decision(held=touch.hasHeldInputs(7))
        touchDecision.reasons.forEach { touch.fault(it) }
        assertEquals("pass",touch.finish(5000).status)
        val controller=Attempt("LAB-06",target,target,0).also { it.arm() }
        controller.accept(Raw("key",0,10,10,Sources.GAMEPAD,30,96))
        controller.accept(Raw("key",1,100,10,Sources.GAMEPAD,30,96))
        val controllerDecision=decision(held=controller.hasHeldInputs(7))
        controllerDecision.reasons.forEach { controller.fault(it) }
        assertEquals("pass",controller.finish(5000).status)
    }
    @Test fun addRemoveAndUnknownDeviceAreNeverNoOps() {
        assertFalse(decision(change="added").continuous)
        assertFalse(decision(change="removed").continuous)
        assertFalse(decision(id=99).continuous)
        assertFalse(decision(current=devices - 7).continuous)
        assertFalse(decision(current=devices + (99 to device(99))).continuous)
        assertFalse(decision(current=devices + (7 to mapOf("available" to false))).continuous)
    }
    @Test fun identitySourceRangeAndPublicCapabilityChangesInvalidate() {
        val changes=listOf("descriptor" to "different","sources" to Sources.GAMEPAD,"isEnabled" to false,
            "supportedKeyCodes" to listOf(97),"motionRanges" to listOf(mapOf("axis" to 0,"max" to 1919f,"fuzz" to 0f)),
            "motionRanges" to listOf(mapOf("axis" to 0,"max" to 1920f,"fuzz" to 1f)))
        changes.forEach { (key,value) -> assertFalse(key,decision(current=devices + (7 to (device(7) + (key to value)))).continuous) }
        // A changed callback must also detect a different device's configuration drift.
        assertFalse(decision(current=devices + (30 to (device(30) + ("sources" to Sources.GAMEPAD)))).continuous)
    }
    @Test fun focusLifecycleWindowDisplayAndPriorUncertaintyStayFailClosed() {
        listOf("hasWindowFocus" to false,"lifecycle" to "paused","layoutHash" to "changed","actualWindowDisplayId" to 4,"rotation" to 0).forEach { (key,value) ->
            assertFalse(key,decision(currentWindow=window + (key to value)).continuous)
        }
        assertFalse(decision(currentDisplays=displays.map { it + ("rotation" to 0) }).continuous)
        assertFalse(decision(uncertain=true).continuous)
        assertFalse(decision(held=true).continuous)
    }
    @Test fun heldGateBelongsToAffectedDeviceAndNeverManufacturesRelease() {
        val a=Attempt("LAB-06",Bounds(0f,0f,100f,100f),Bounds(200f,0f,300f,100f),0).also { it.arm() }
        a.accept(Raw("key",0,10,10,Sources.GAMEPAD,30,96))
        assertTrue(a.hasHeldInputs(30)); assertFalse(a.hasHeldInputs(7))
        assertTrue(decision(held=a.hasHeldInputs(7)).continuous)
        val result=decision(id=30,held=a.hasHeldInputs(30))
        assertFalse(result.continuous)
        result.reasons.forEach { a.fault(it) }; a.stale=true
        a.accept(Raw("key",1,100,10,Sources.GAMEPAD,30,96))
        assertFalse(a.hasHeldInputs(30)); assertEquals("inconclusive",a.finish(5000).status)
    }
    @Test fun heldTouchAndExcursedAxisRemainHeldUntilActualReleaseOrNeutral() {
        val a=Attempt("LAB-02",Bounds(0f,0f,100f,100f),Bounds(200f,0f,300f,100f),0).also { it.arm() }
        a.accept(Raw("touch",0,10,device=7,samples=listOf(Sample(10,50f,50f))))
        assertTrue(a.hasHeldInputs(7)); assertFalse(a.hasHeldInputs(30))
        a.ranges(30,listOf(Range(0,Sources.JOYSTICK,-1f,1f,0f)))
        a.accept(Raw("generic",2,20,source=Sources.JOYSTICK,device=30,samples=listOf(Sample(20,0f,0f,mapOf(0 to 1f)))))
        assertTrue(a.hasHeldInputs(30)); assertEquals(1f,a.axes[30 to 0])
        a.accept(Raw("generic",2,30,source=Sources.JOYSTICK,device=30,samples=listOf(Sample(30,0f,0f,mapOf(0 to 0f)))))
        assertFalse(a.hasHeldInputs(30)); assertTrue(a.hasHeldInputs(7))
    }
}
