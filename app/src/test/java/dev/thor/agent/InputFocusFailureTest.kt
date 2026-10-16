package dev.thor.agent

import dev.thor.tools.Code
import org.junit.Assert.*
import org.junit.Test

class InputFocusFailureTest {
    private val physical="Physical controller focus is not the authorized upper app; no focus-lock change was made"
    private val navigation=listOf("Focused selected-screen application is not the authorized navigation target",
        "Focused selected-screen window is not the authorized navigation target")
    @Test fun onlyAuditedToolAndExactLocalMismatchPairsReceiveFixedReason() {
        for(tool in listOf("input.press_button","input.set_axes"))assertEquals("input_focus_mismatch",InputFocusFailure.classify(tool,Code.TARGET_CHANGED,physical))
        for(tool in listOf("input.press_button","input.set_axes"))assertEquals("input_focus_mismatch",InputFocusFailure.classify(tool,Code.TARGET_CHANGED,"Physical controller requires the observed selected-screen app to have window focus"))
        navigation.forEach {assertEquals("input_focus_mismatch",InputFocusFailure.classify("input.navigate",Code.TARGET_CHANGED,it))}
        assertNull(InputFocusFailure.classify("input.navigate",Code.TARGET_CHANGED,physical))
        assertNull(InputFocusFailure.classify("input.press_button",Code.TARGET_CHANGED,navigation.first()))
    }
    @Test fun DifferentErrorsMissingFocusAndRebuiltControllersStayGeneric() {
        for(error in Code.entries)if(error!=Code.TARGET_CHANGED)assertNull(InputFocusFailure.classify("input.press_button",error,physical))
        assertNull(InputFocusFailure.classify("input.press_button",null,physical))
        for(message in listOf(null,"No focused task for physical controller routing","Physical controller was rebuilt during gamepad action",
            "Navigation focus section is absent or ambiguous","Current per-display navigation focus could not be read","Upper windows/app/layout changed"))
            for(tool in listOf("input.press_button","input.set_axes","input.navigate"))assertNull(InputFocusFailure.classify(tool,Code.TARGET_CHANGED,message))
    }
    @Test fun UntrustedBodiesAndMessageSubstringsCannotBecomeHintsOrLeakTheirContent() {
        for(message in listOf(physical+" private.package",physical+"\n",physical.lowercase(),"prefix $physical","{\"message\":\"$physical\"}","https://private.invalid/$physical"))
            assertNull(InputFocusFailure.classify("input.press_button",Code.TARGET_CHANGED,message))
        for(tool in listOf("input.tap","debug.shell","apps.launch","perception.capture_screen","input.release_inputs"))
            assertNull(InputFocusFailure.classify(tool,Code.TARGET_CHANGED,physical))
    }
}
