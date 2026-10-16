package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class GamepadCommandTest {
    private fun rejects(action:()->Unit) {try {action();fail("Expected invalid gamepad command")}catch(f:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,f.code)}}
    @Test fun boundedButtonsHaveSafeDefaultAndNoPersistentDownOrUp() {
        assertEquals(100,GamepadCommand.parse("input.press_button",mapOf("button" to "A","phase" to "hold")).durationMs)
        listOf("down","up","keyboard").forEach {phase->rejects {GamepadCommand.parse("input.press_button",mapOf("button" to "A","phase" to phase))}}
        rejects {GamepadCommand.parse("input.press_button",mapOf("button" to "HOME","phase" to "hold"))}
        rejects {GamepadCommand.parse("input.press_button",mapOf("button" to "A","phase" to "hold","deviceId" to 30))}
    }
    @Test fun normalizedAxesKeepTriggerAndStickDomainsSeparate() {
        val command=GamepadCommand.parse("input.set_axes",mapOf("axes" to mapOf("X" to -1.0,"Y" to 1,"LTRIGGER" to 0.5),"durationMs" to 5000))
        assertEquals(mapOf("X" to -1f,"Y" to 1f,"LTRIGGER" to .5f),command.axes)
        listOf(emptyMap<String,Any>(),mapOf("HOME" to 1),mapOf("X" to Double.NaN),mapOf("X" to 1.01),mapOf("LTRIGGER" to -0.1),mapOf("X" to "1")).forEach {axes->
            rejects {GamepadCommand.parse("input.set_axes",mapOf("axes" to axes,"durationMs" to 100))}
        }
    }
    @Test fun durationCannotEscapeTheBoundThroughCoercion() {
        listOf(0,-1,5001,1.5,Double.POSITIVE_INFINITY,"100").forEach {duration->
            rejects {GamepadCommand.parse("input.set_axes",mapOf("axes" to mapOf("X" to 1),"durationMs" to duration))}
        }
        rejects {GamepadCommand.parse("input.set_axes",mapOf("axes" to mapOf("X" to 1)))}
    }
}
