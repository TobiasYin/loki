package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The host temporarily revokes only the pre-existing Shizuku grant and restores it. */
@RunWith(AndroidJUnit4::class)
class PhysicalAccessibilityDeniedTest {
    @Test fun absentGrantLeavesAccessibilityAndPermissionPromptUntouched() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true" && args.getString("permissionMissingAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val context=instrumentation.targetContext
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component="com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService"
        val before=Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services")
        assertFalse(before.orEmpty().split(':').contains(component))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val debug=ProductHost::class.java.getDeclaredField("debug").apply {isAccessible=true}.get(host) as dev.thor.tools.diagnostic.DebugConnection
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val evidence=JSONObject().put("initialOwnServiceEnabled",false)
        try {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
            while(debug.availability().status!=dev.thor.tools.Status.PERMISSION_MISSING && System.nanoTime()<end)Thread.sleep(100)
            assertEquals("Running server must report the missing grant",dev.thor.tools.Status.PERMISSION_MISSING,debug.availability().status)
            assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission("moe.shizuku.manager.permission.API_V23"))
            Thread.sleep(2000)
            val latch=CountDownLatch(1);var ready=false
            instrumentation.runOnMainSync {web.evaluateJavascript("window.productReady === true") {ready=it=="true";latch.countDown()}}
            assertTrue(latch.await(5,TimeUnit.SECONDS));assertTrue(ready)
            assertEquals(before,Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services"))
            var focused=false;instrumentation.runOnMainSync {focused=activity.hasWindowFocus()}
            assertTrue("No unsolicited permission dialog should cover the app",focused)
            assertFalse(host.accessibilitySetupStatus().contains("已通过 Shizuku"))
            evidence.put("serverRunning",true).put("shizukuPermission","denied").put("servicesUnchanged",true).put("appFocused",true).put("passed",true)
        } finally {
            File(context.filesDir,"physical-accessibility-denied.json").writeText(evidence.toString(2))
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
        }
    }
}
