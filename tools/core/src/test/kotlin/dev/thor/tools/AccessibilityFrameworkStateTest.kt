package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AccessibilityFrameworkStateTest {
    private val own="{com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService}"
    private fun user(id:Int=0,enabled:String="",binding:String="",crashed:String="",bound:String="")="""
        User state[
             attributes:{id=$id, installedServiceCount=2}
             Bound services:{$bound}
             Enabled services:{$enabled}
             Binding services:{$binding}
             Crashed services:{$crashed}
             Client list info:{
             Registered clients:{}}]
    """.trimIndent()
    private fun inspect(section:String,ownBound:Boolean?=false,user:Int=0)=AccessibilityFrameworkState.inspect("currentUserId=$user\n$section",user,"com.tobias.loki",ownBound)
    @Test fun enabledReadbackRemovalCannotAcknowledgeOrphanBinding() {
        val state=inspect(user(binding=own))
        assertTrue(state.known);assertTrue(state.binding);assertFalse(state.enabled)
        assertFalse(state.offAcknowledged);assertEquals("FRAMEWORK_BINDING",state.phase)
        assertTrue(state.retainEnabledForRestart)
        val other="other.app/.Reader:second.app/second.app.Reader"
        val retained=AccessibilitySetup.mergedServices(other,"com.tobias.loki")
        assertEquals("$other:com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService",retained)
        assertEquals(retained,AccessibilitySetup.mergedServices(retained,"com.tobias.loki"))
    }
    @Test fun onlyOutstandingBindingRequiresRestartWhileCrashCanBeCycled() {
        assertFalse(inspect(user(crashed=own)).retainEnabledForRestart)
        assertTrue(inspect(user(binding=own,crashed=own)).retainEnabledForRestart)
        assertFalse(inspect(user()).retainEnabledForRestart)
        assertFalse(inspect(user(enabled=own)).retainEnabledForRestart)
        assertFalse(inspect(user(bound="Service[label=Loki]"),true).retainEnabledForRestart)
        assertFalse(AccessibilityFrameworkState.State(false,binding=true).retainEnabledForRestart)
    }
    @Test fun acknowledgesOnlyOurAbsencePreservingUnrelatedServices() {
        val other="{other.app/.Reader}"
        val state=inspect(user(enabled=other,binding=other,crashed=other,bound="Service[label=Reader]"))
        assertTrue(state.offAcknowledged)
        assertFalse(inspect(user(),ownBound=true).offAcknowledged)
        assertFalse(inspect(user(crashed=own)).offAcknowledged)
        assertFalse(inspect(user(enabled=own)).offAcknowledged)
    }
    @Test fun scopesToExactlyTheRequestedCurrentUser() {
        assertTrue(inspect(user(10,binding=own)+"\n"+user(0)).offAcknowledged)
        assertFalse(inspect(user(0)+"\n"+user(10,binding=own),user=10).offAcknowledged)
        assertFalse(inspect(user(0)+"\n"+user(0)).known)
        assertFalse(inspect(user(10)).known)
        assertFalse(AccessibilityFrameworkState.inspect("currentUserId=10\n"+user(0),0,"com.tobias.loki",false).known)
    }
    @Test fun unknownOrIncompleteDumpNeverAcknowledges() {
        for(value in listOf("",user().replace("Binding services:{}",""),user().replace("Client list info:{",""),
            user(binding="unexpected"),user().replace("Enabled services:{}","Enabled services:{{broken}"))) {
            assertFalse(inspect(value).offAcknowledged)
        }
        assertFalse(inspect(user(bound="Service[label=Reader]"),ownBound=null).offAcknowledged)
    }
    @Test fun automationCannotTurnSuppressedEmptyPublicListIntoOffProof() {
        assertFalse(inspect(user(bound="Service[label=Loki]")+"\nUi Automation[eventTypes=all]",false).offAcknowledged)
        assertTrue(inspect(user()+"\nUi Automation[eventTypes=all]",false).offAcknowledged)
        assertTrue(AccessibilityFrameworkState.inspect("currentUserId=0\n"+user(bound="Service[label=Other]")+"\nUi Automation[eventTypes=all]",
            0,"com.tobias.loki",false,publicBoundListPopulated=true).offAcknowledged)
    }
    @Test fun completeRequiredInventoriesSurviveUnrelatedTailTimeoutButCannotInferMissingAutomation() {
        val prefix="currentUserId=0\n"+user(bound="Service[label=Other]")+"\n*** SERVICE DUMP TIMEOUT ***"
        val state=AccessibilityFrameworkState.inspect(prefix,0,"com.tobias.loki",false,publicBoundListPopulated=true,dumpComplete=false)
        assertTrue(state.known);assertTrue(state.offAcknowledged)
        val noPublicProof=AccessibilityFrameworkState.inspect(prefix,0,"com.tobias.loki",false,publicBoundListPopulated=false,dumpComplete=false)
        assertTrue(noPublicProof.known);assertNull(noPublicProof.bound);assertFalse(noPublicProof.offAcknowledged)
        val missingRequired=prefix.replace("Crashed services:{}","Crashed services:{")
        assertFalse(AccessibilityFrameworkState.inspect(missingRequired,0,"com.tobias.loki",false,true,false).offAcknowledged)
        assertFalse(AccessibilityFrameworkState.inspect(prefix.replace("Binding services:{}",""),0,"com.tobias.loki",false,true,false).known)
        assertFalse(AccessibilityFrameworkState.inspect("currentUserId=0\n"+user(binding=own)+"\n*** DUMP TIMEOUT ***",0,"com.tobias.loki",false,true,false).offAcknowledged)
    }
    @Test fun vendorDumpWithSeveralOtherBoundServicesRemainsKnownWithoutPublicBoundProof() {
        // Shape copied from the real vendor dump; names and unrelated content
        // are synthetic. In particular Bound uses labels, not component ids.
        val vendor="""
            AccessibilityManagerService state:
            currentUserId=0
            User state[
                 attributes:{id=0, touchExplorationEnabled=false, installedServiceCount=10, magnificationModes={0=1, 4=1}}
                 Bound services:{Service[label=游戏助手, feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=0, eventTypes=TYPE_WINDOW_STATE_CHANGED],
                                 Service[label=Reader, feedbackType[FEEDBACK_GENERIC], capabilities=0]}
                 Enabled services:{{other.app/other.app.Reader}, {second.app/.Reader}, {third.app/third.app.Reader}, {fourth.app/.Reader}}
                 Binding services:{}
                 Crashed services:{}
                 Client list info:{
                      Registered clients:{
            }}]
                Registered clients:{}
        """.trimIndent()
        val known=AccessibilityFrameworkState.inspect(vendor,0,"com.tobias.loki",null)
        assertTrue(known.known);assertNull(known.bound);assertFalse(known.offAcknowledged)
        val notBound=AccessibilityFrameworkState.inspect(vendor,0,"com.tobias.loki",false)
        assertTrue(notBound.known);assertTrue(notBound.offAcknowledged)
    }
}
