package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvdevAxesTest {
    private val proc="""
        I: Bus=0003 Vendor=2020 Product=0111 Version=0000
        N: Name="Odin Controller"
        P: Phys=
        S: Sysfs=/devices/virtual/input/input29
        U: Uniq=
        H: Handlers=event9 cpufreq
        B: EV=b
    """.trimIndent()
    private val metadata="""
        add device 1: /dev/input/event9
          name:     "Odin Controller"
          events:
            ABS (0003): ABS_X : value 0, min -32767, max 32767, fuzz 0, flat 15, resolution 0
                        ABS_Y : value 0, min -32767, max 32767, fuzz 0, flat 15, resolution 0
                        ABS_Z : value 0, min -32767, max 32767, fuzz 0, flat 15, resolution 0
                        ABS_RZ : value 0, min -32767, max 32767, fuzz 0, flat 15, resolution 0
                        ABS_GAS : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        ABS_BRAKE : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        ABS_HAT0X : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
    """.trimIndent()
    private fun refuses(action:()->Unit) {try {action();fail("Expected evdev metadata rejection")}catch(_:ToolFailure){}}
    @Test fun discoversExactControllerAndDoesNotBindFixedEventNumber() {
        val node=EvdevAxes.node(proc.replace("event9","event27").replace("input29","input41"))
        assertEquals("/dev/input/event27",node.path);assertEquals(0x2020,node.vendor);assertEquals(0x111,node.product)
        assertEquals("/devices/virtual/input/input41",node.sysfs)
        refuses {EvdevAxes.node(proc+"\n\n"+proc.replace("event9","event10"))}
        refuses {EvdevAxes.node(proc.replace("event9","event9 event10"))}
        refuses {EvdevAxes.node(proc.replace("Odin Controller","Odin Controller Keyboard"))}
        refuses {EvdevAxes.node(proc.replace("event9","../../event9"))}
    }
    @Test fun parsesActualSixAxisDomainsWithoutTreatingHatAsAnAllowedWrite() {
        val profile=EvdevAxes.profile(metadata,EvdevAxes.node(proc))
        assertEquals(setOf("X","Y","Z","RZ","GAS","BRAKE"),profile.keys)
        assertEquals(-32767,profile.getValue("X").value(-1f))
        assertEquals(32767,profile.getValue("Y").value(1f))
        assertEquals(0,profile.getValue("BRAKE").value(0f))
        assertEquals(32767,profile.getValue("GAS").value(1f))
        refuses {profile.getValue("GAS").value(-1f)}
    }
    @Test fun actualNonNeutralValuesAreNotHiddenByReceiverFivePercentTolerance() {
        val profile=EvdevAxes.profile(metadata.replace("ABS_X : value 0","ABS_X : value 20").replace("ABS_GAS : value 0","ABS_GAS : value 1"),EvdevAxes.node(proc))
        assertFalse(profile.getValue("X").isNeutral());assertFalse(profile.getValue("GAS").isNeutral())
        assertTrue(profile.getValue("Y").isNeutral())
    }
    @Test fun discoveryNeverAcceptsAnotherNodeDuplicateAxisOrMissingRange() {
        val node=EvdevAxes.node(proc)
        refuses {EvdevAxes.profile(metadata.replace("/dev/input/event9","/dev/input/event10"),node)}
        refuses {EvdevAxes.profile(metadata+"\nABS_X : value 0, min -1, max 1, fuzz 0, flat 0",node)}
        refuses {EvdevAxes.profile(metadata.replace("ABS_BRAKE","ABS_UNKNOWN"),node)}
        refuses {EvdevAxes.profile(metadata.replace("min -32767, max 32767","min 32767, max -32767"),node)}
    }
    @Test fun oneArm64FrameContainsOnlyRequestedAbsAxesAndOneSynBoundary() {
        val profile=EvdevAxes.profile(metadata,EvdevAxes.node(proc))
        val bytes=EvdevAxes.frame(linkedMapOf("X" to 32767,"BRAKE" to 12345),profile)
        assertEquals(72,bytes.size)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for((type,code,value) in listOf(Triple(3,0,32767),Triple(3,10,12345),Triple(0,0,0))) {
            assertEquals(0L,buffer.long);assertEquals(0L,buffer.long)
            assertEquals(type,buffer.short.toInt());assertEquals(code,buffer.short.toInt());assertEquals(value,buffer.int)
        }
    }
    @Test fun noUnknownEventCodeOrOutOfRangeRawValueCanReachFrameEncoding() {
        val profile=EvdevAxes.profile(metadata,EvdevAxes.node(proc))
        refuses {EvdevAxes.frame(mapOf("HAT_X" to 1),profile)}
        refuses {EvdevAxes.frame(mapOf("KEY_POWER" to 1),profile)}
        refuses {EvdevAxes.frame(mapOf("X" to 32768),profile)}
        refuses {EvdevAxes.frame(emptyMap(),profile)}
    }
}
