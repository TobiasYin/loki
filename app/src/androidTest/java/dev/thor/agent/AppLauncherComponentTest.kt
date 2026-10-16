package dev.thor.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.AppLauncher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real PackageManager + recorded SDK submission construction only. No activity
 * is actually opened, and this is not model/foreground acceptance. Emulator only. */
@RunWith(AndroidJUnit4::class)
class AppLauncherComponentTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class RecordingContext(base:Context):ContextWrapper(base) {
        var launched:Intent?=null;var options:Bundle?=null
        override fun startActivity(intent:Intent,options:Bundle?) {check(launched==null);launched=Intent(intent);this.options=options?.let {Bundle(it)}}
    }
    @Before fun emulatorOnly() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("emulatorAcceptance")=="true")
        org.junit.Assume.assumeFalse(android.os.Build.MODEL=="AYN Thor")
        assertTrue("Only the explicitly selected emulator may run this component",android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
    }
    @Test fun launcherDiscoveryReturnsOnlyQueryMatchesAndNeverOwnChat() {
        val helper=AppLauncher(context);val cp=Checkpoint({},Cancellation())
        val result=helper.find(mapOf("query" to "com.android.settings","limit" to 1),cp)
        val matches=result["apps"] as List<*>;assertEquals(1,matches.size)
        val app=matches.single() as Map<*,*>;assertEquals("com.android.settings",app["packageName"])
        assertEquals(setOf("packageName","activityName","label","launchable"),app.keys)
        assertFalse(cp.submitted)
        assertTrue((helper.find(mapOf("query" to context.packageName),cp)["apps"] as List<*>).isEmpty())
    }
    @Test fun sdkSubmissionIsExplicitPublicMainLauncherOnRequestedDisplayWithoutExtraCapabilities() {
        val recorded=RecordingContext(context);val helper=AppLauncher(recorded);val cp=Checkpoint({},Cancellation());var finalChecks=0
        val result=helper.launch(mapOf("packageName" to "com.android.settings"),0,cp){finalChecks++}
        val sent=recorded.launched ?: error("SDK submission was not invoked")
        assertEquals(Intent.ACTION_MAIN,sent.action);assertEquals(setOf(Intent.CATEGORY_LAUNCHER),sent.categories)
        assertEquals("com.android.settings",sent.component!!.packageName);assertEquals("com.android.settings",sent.`package`)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,sent.flags);assertNull(sent.data);assertNull(sent.extras);assertNull(sent.selector);assertNull(sent.clipData)
        assertEquals(0,recorded.options!!.getInt("android.activity.launchDisplayId",-1))
        assertTrue(finalChecks>=2);assertTrue(cp.submitted);assertEquals(false,result["foregroundVerified"])
        assertEquals("upper",result["displayRole"])
    }
    @Test fun cancellationTargetChangeAndOwnAppDenialNeverSubmit() {
        val recorded=RecordingContext(context);val helper=AppLauncher(recorded)
        val cancelled=Cancellation().apply {cancel()};val stopped=Checkpoint({},cancelled)
        try {helper.launch(mapOf("packageName" to "com.android.settings"),0,stopped){};fail("Cancelled launch")}
        catch(e:ToolFailure){assertEquals(Code.CANCELLED,e.code)}
        val stale=Checkpoint({},Cancellation())
        try {helper.launch(mapOf("packageName" to "com.android.settings"),0,stale){fail(Code.TARGET_CHANGED,"Display epoch changed")};fail("Stale display launch")}
        catch(e:ToolFailure){assertEquals(Code.TARGET_CHANGED,e.code)}
        try {helper.launch(mapOf("packageName" to context.packageName),0,stale){};fail("Own chat launch")}
        catch(e:ToolFailure){assertEquals(Code.COMMAND_BLOCKED,e.code)}
        assertNull(recorded.launched);assertFalse(stopped.submitted);assertFalse(stale.submitted)
    }
}
