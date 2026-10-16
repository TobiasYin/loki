package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvdevButtonsTest {
    private val metadata=javaClass.getResource("/thor-odin-getevent-lp.txt")!!.readText()
    private val layout=javaClass.getResource("/thor-odin-keylayout.kl")!!.readText()
    private fun refuses(code: Code,action:()->Unit) {try {action();fail("Expected evdev button rejection")}catch(t:ToolFailure){assertEquals(code,t.code)}}
    @Test fun actualThorLabelsAndKeyLayoutCoverTheAllowedButtonsWithoutGuessingAliases() {
        val states=EvdevButtons.states(metadata)
        assertEquals(EvdevButtons.mappings.keys,states.keys)
        assertTrue(states.values.none {it})
        for(button in states.keys){EvdevButtons.verifyLayout(button,layout);EvdevButtons.requireReleased(button,states)}
        assertEquals(0x130,EvdevButtons.mappings.getValue("A").code)
        assertEquals("BTN_GAMEPAD",EvdevButtons.mappings.getValue("A").label)
        assertEquals(0x133,EvdevButtons.mappings.getValue("X").code)
        assertEquals(0x134,EvdevButtons.mappings.getValue("Y").code)
    }
    @Test fun currentlyPressedStarBlocksOnlyTheRequestedButtonBeforeSubmission() {
        val states=EvdevButtons.states(metadata.replace("BTN_GAMEPAD ","BTN_GAMEPAD*"))
        refuses(Code.BACKEND_UNAVAILABLE) {EvdevButtons.requireReleased("A",states)}
        EvdevButtons.requireReleased("B",states)
        assertTrue(states.getValue("A"))
    }
    @Test fun missingLabelsAmbiguousLabelsAndChangedFirmwareMappingAreUnsupported() {
        refuses(Code.UNSUPPORTED) {EvdevButtons.requireReleased("A",EvdevButtons.states(metadata.replace("BTN_GAMEPAD","BTN_UNKNOWN")))}
        refuses(Code.UNSUPPORTED) {EvdevButtons.states(metadata.replace("BTN_GAMEPAD","BTN_GAMEPAD BTN_GAMEPAD"))}
        refuses(Code.UNSUPPORTED) {EvdevButtons.verifyLayout("A",layout.replace("0x130 BUTTON_A","0x130 BUTTON_B"))}
        refuses(Code.UNSUPPORTED) {EvdevButtons.verifyLayout("A",layout+"\nkey 304 BUTTON_B\n")}
    }
    @Test fun buttonFramesOnlyContainAnAllowedEvKeyThenSynAndReleaseUsesZero() {
        for(pressed in listOf(true,false)) {
            val bytes=EvdevButtons.frame("A",pressed);assertEquals(48,bytes.size)
            val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0L,data.long);assertEquals(0L,data.long)
            assertEquals(1,data.short.toInt());assertEquals(0x130,data.short.toInt());assertEquals(if(pressed)1 else 0,data.int)
            assertEquals(0L,data.long);assertEquals(0L,data.long)
            assertEquals(0,data.short.toInt());assertEquals(0,data.short.toInt());assertEquals(0,data.int)
        }
        for(button in listOf("POWER","HOME","304","KEY_0x130","BTN_GAMEPAD"))refuses(Code.UNSUPPORTED) {EvdevButtons.frame(button,true)}
    }
}
