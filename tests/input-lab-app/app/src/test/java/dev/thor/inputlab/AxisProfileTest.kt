package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test

class AxisProfileTest {
    private val axes=linkedSetOf(0,1,11,14,23,22)
    private val device=mapOf<String,Any?>("available" to true,"name" to "Odin Controller","vendorId" to 8224,"productId" to 273,
        "isVirtual" to false,"isEnabled" to true,"sources" to (Sources.JOYSTICK or Sources.GAMEPAD))
    private val ranges=axes.map { Range(it,Sources.JOYSTICK,if(it==23 || it==22) 0f else -1f,1f,0f) }
    private fun select(d: Map<String,Any?> = device,r: List<Range> = ranges)=AxisProfile.select("AYN","AYN Thor",mapOf(30 to d),mapOf(30 to r))
    private fun sample(values: Map<Int,Float>,id: Int=30)=Raw("generic",2,100,source=Sources.JOYSTICK,device=id,samples=listOf(Sample(100,0f,0f,values)))
    private fun attempt(profile: AxisProfile=select())=Attempt("LAB-07",Bounds(0f,0f,100f,100f),Bounds(200f,0f,300f,100f),0,
        requiredAxes=profile.axes,requiredAxisDeviceId=profile.deviceId).also { it.arm(); it.ranges(30,ranges) }
    @Test fun thorProfileFreezesAdvertisedBrakeGasBeforeInput() {
        val p=select(); assertEquals(axes,p.axes); assertEquals(30,p.deviceId)
        assertEquals("ayn_thor_odin_xy_zrz_brake_gas",p.name)
        val a=attempt(p); a.accept(sample(axes.associateWith { 1f })); a.accept(sample(axes.associateWith { 0f }))
        assertEquals("pass",a.finish(5000).status)
    }
    @Test fun keyboardVirtualUnknownAmbiguousAndMissingRangesCannotSelectThorProfile() {
        assertEquals(AxisProfile.standard,select(device + ("sources" to 0x101)))
        assertEquals(AxisProfile.standard,select(device + ("isVirtual" to true)))
        assertEquals(AxisProfile.standard,select(device + ("vendorId" to 0)))
        assertEquals(AxisProfile.standard,select(device + ("isEnabled" to false)))
        assertEquals(AxisProfile.standard,select(r=ranges.filter { it.axis!=23 }))
        assertEquals(AxisProfile.standard,AxisProfile.select("AYN","other",mapOf(30 to device),mapOf(30 to ranges)))
        assertEquals(AxisProfile.standard,AxisProfile.select("AYN","AYN Thor",mapOf(30 to device,31 to device),mapOf(30 to ranges,31 to ranges)))
    }
    @Test fun unadvertisedStandardTriggerValuesCannotSubstituteForRequiredBrakeGas() {
        val a=attempt(); a.accept(sample(setOf(0,1,11,14,17,18).associateWith { 1f })); a.accept(sample(setOf(0,1,11,14,17,18).associateWith { 0f }))
        assertEquals("fail",a.finish(5000).status)
    }
    @Test fun selectedControllerCannotBorrowEvidenceFromAnotherDevice() {
        val a=attempt(); a.ranges(31,ranges)
        a.accept(sample(axes.associateWith { 1f },31)); a.accept(sample(axes.associateWith { 0f },31))
        assertEquals("fail",a.finish(5000).status)
    }
    @Test fun incompleteNeutralAndHeldExtraAdvertisedAxisCannotPass() {
        val a=attempt(); a.accept(sample(axes.associateWith { 1f })); a.accept(sample((axes-23).associateWith { 0f }))
        assertEquals("fail",a.finish(5000).status)
        val b=attempt(); b.ranges(30,ranges + Range(15,Sources.JOYSTICK,-1f,1f,0f))
        b.accept(sample(axes.associateWith { 1f })); b.accept(sample(axes.associateWith { 0f } + (15 to 1f)))
        assertEquals("fail",b.finish(5000).status)
    }
}
