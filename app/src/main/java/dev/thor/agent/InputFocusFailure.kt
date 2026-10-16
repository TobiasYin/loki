package dev.thor.agent

import dev.thor.tools.Code

/** Only audited local Native refusal strings become a public fixed reason.
 * Never inspect tool body, match a substring, or return the original message. */
internal object InputFocusFailure {
    const val REASON="input_focus_mismatch"
    private val physical=setOf("Physical controller focus is not the authorized upper app; no focus-lock change was made",
        "Physical controller requires the observed selected-screen app to have window focus")
    private val navigation=setOf("Focused selected-screen application is not the authorized navigation target",
        "Focused selected-screen window is not the authorized navigation target")
    fun classify(toolId:String,error:Code?,message:String?):String? {
        if(error!=Code.TARGET_CHANGED)return null
        val approved=when(toolId){"input.press_button","input.set_axes"->physical;"input.navigate"->navigation;else->emptySet()}
        return if(message in approved)REASON else null
    }
}
