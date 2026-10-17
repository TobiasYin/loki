package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.thor.tools.AccessibilityFrameworkState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the parser under Android's actual ICU Pattern implementation. Only
 * synthetic text: no activities, model, device state, settings or files. */
@RunWith(AndroidJUnit4::class)
class AccessibilityFrameworkParserDeviceTest {
    private val own="{dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService}"
    private fun dump(binding:String="",crashed:String="",enabled:String="{other.app/.Reader}",id:Int=0)="""
        ACCESSIBILITY MANAGER (dumpsys accessibility)
        currentUserId=0
        hasWindowMagnificationConnection=false
        User state[
             attributes:{id=$id, installedServiceCount=10, magnificationModes={0=1, 4=1}, magnificationCapabilities=3}
             Bound services:{Service[label=游戏助手, feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=0],
                             Service[label=Reader, feedbackType[FEEDBACK_GENERIC], capabilities=0]}
             Enabled services:{$enabled}
             Binding services:{$binding}
             Crashed services:{$crashed}
             Client list info:{
                  Registered clients:{
        }}]
        Ui Automation[eventTypes=TYPE_WINDOWS_CHANGED, notificationTimeout=0]
    """.trimIndent()
    @Test fun completeVendorInventoryParsesOnAndroidAndAcknowledgesOnlyOurAbsence() {
        val state=AccessibilityFrameworkState.inspect(dump(),0,"dev.thor.agent",false,publicBoundListPopulated=true)
        assertTrue(state.known);assertTrue(state.offAcknowledged);assertFalse(state.binding)
        val binding=AccessibilityFrameworkState.inspect(dump(binding=own),0,"dev.thor.agent",false,true)
        assertTrue(binding.known);assertTrue(binding.binding);assertFalse(binding.offAcknowledged)
        val crashed=AccessibilityFrameworkState.inspect(dump(crashed=own),0,"dev.thor.agent",false,true)
        assertTrue(crashed.known);assertTrue(crashed.crashed);assertFalse(crashed.offAcknowledged)
        val enabled=AccessibilityFrameworkState.inspect(dump(enabled=own),0,"dev.thor.agent",false,true)
        assertTrue(enabled.known);assertTrue(enabled.enabled);assertFalse(enabled.offAcknowledged)
    }
    @Test fun truncatedRequiredInventoryAndWrongUserRemainUnknownOnAndroid() {
        assertFalse(AccessibilityFrameworkState.inspect(dump().replace("Binding services:{}",""),0,"dev.thor.agent",false,true,false).known)
        assertFalse(AccessibilityFrameworkState.inspect(dump(id=10),0,"dev.thor.agent",false,true,false).known)
        val prefix=dump()+"\n*** SERVICE DUMP TIMEOUT ***"
        assertTrue(AccessibilityFrameworkState.inspect(prefix,0,"dev.thor.agent",false,true,false).offAcknowledged)
        assertFalse(AccessibilityFrameworkState.inspect(prefix,0,"dev.thor.agent",false,false,false).offAcknowledged)
    }
    @Test fun shortAttributeClosingBraceAlsoCompilesOnAndroid() {
        val minimal=dump().replace("id=0, installedServiceCount=10, magnificationModes={0=1, 4=1}, magnificationCapabilities=3","id=0")
        assertTrue(AccessibilityFrameworkState.inspect(minimal,0,"dev.thor.agent",false,true).offAcknowledged)
    }
}
