package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Status
import dev.thor.tools.Code
import dev.thor.tools.diagnostic.AndroidBackend
import dev.thor.tools.diagnostic.DebugConnection
import dev.thor.tools.diagnostic.ThorAccessibilityService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccessibilityRecoveryDeviceTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun field(host:ProductHost,name:String)=ProductHost::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host)
    private fun until(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+25000
        while(!condition() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
        assertTrue(message,condition())
    }
    @Test fun authorizedMonitorReconnectsServiceAndRecordsActualCallbackWithoutMisclassifyingLaunch() {
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val display=InstrumentationRegistry.getArguments().getString("chatDisplay","0").toInt()
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
        val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
        val debug=field(ready.host,"debug") as DebugConnection
        val backend=field(ready.host,"backend") as AndroidBackend
        until("Authorized Shizuku and accessibility must initially connect") {
            debug.availability().status==Status.READY && ThorAccessibilityService.instance!=null
        }
        val original=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').toSet()
        val own="${context.packageName}/${ThorAccessibilityService::class.java.name}"
        val startId=debug.accessibilityRecoveryEvents().lastOrNull()?.optLong("id") ?: 0L
        ins.runOnMainSync {requireNotNull(ThorAccessibilityService.instance).disableSelf()}
        until("Service must become disconnected") {ThorAccessibilityService.instance==null}
        val launch=backend.availability("apps.launch")
        assertNotEquals(Status.READY,launch.status)
        assertNotEquals(Code.TARGET_CHANGED,launch.reasonCode)
        until("Recovery must record a settings cycle followed by a real connection callback") {
            val phases=debug.accessibilityRecoveryEvents().filter {it.optLong("id")>startId}.map {it.getString("phase")}
            ThorAccessibilityService.instance!=null && "recovering" in phases && phases.lastOrNull()=="connected" &&
                debug.accessibilityRecoveryDiagnostics().optString("frameworkPhase")=="OFF_ACKNOWLEDGED"
        }
        val after=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').toSet()
        assertTrue("Recovery must preserve other enabled services",after.containsAll(original-own))
        assertTrue(after.contains(own))
        assertEquals(Status.READY,backend.availability("apps.launch").status)
        assertFalse(debug.accessibilityRecoveryPending)
        File(context.filesDir,"accessibility-recovery-device-test.json").writeText(JSONObject()
            .put("passed",true).put("otherServicesPreserved",true).put("disconnectedLaunchStatus",launch.status.name)
            .put("disconnectedLaunchReasonCode",launch.reasonCode?.name ?: JSONObject.NULL)
            .put("recovery",debug.accessibilityRecoveryDiagnostics()).toString(2))
    }
}
