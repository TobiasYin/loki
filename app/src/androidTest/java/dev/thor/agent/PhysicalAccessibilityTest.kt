package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.provider.Settings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.diagnostic.ThorAccessibilityService
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Starts with our service disabled and an existing Shizuku grant. No settings
 * writes, permission prompts, debug binding, or fixture configuration in this test. */
@RunWith(AndroidJUnit4::class)
class PhysicalAccessibilityTest {
    @Test fun grantedShizukuEnablesOnlyOwnAccessibilityOnLaunch() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        val context=instrumentation.targetContext
        assertEquals("AYN Thor",android.os.Build.MODEL)
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component="com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService"
        fun services()=Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty().split(':').filter {it.isNotBlank() && it!="null"}.toSet()
        val before=services();assertEquals("Host must match requested initial service setting",args.getString("alreadyEnabled")=="true",component in before)
        val savedProfile=context.getSharedPreferences("profiles",0).getString("url",null)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val web=activity.findViewById<WebView>(android.R.id.custom)
        fun js(script: String): String {
            val latch=CountDownLatch(1);var result=""
            instrumentation.runOnMainSync {web.evaluateJavascript(script) {result=it;latch.countDown()}}
            assertTrue(latch.await(5,TimeUnit.SECONDS));return result
        }
        fun until(condition: () -> Boolean) {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
            while(System.nanoTime()<end) {if(condition())return;Thread.sleep(100)}
            fail("Automatic accessibility timed out: ${host.accessibilitySetupStatus()}")
        }
        val evidence=JSONObject().put("before",JSONArray(before.toList())).put("manualEnable",false).put("manualBind",false)
        try {
            until {ThorAccessibilityService.instance!=null && host.accessibilitySetupStatus().contains("已通过 Shizuku")}
            val after=services();assertEquals(before+component,after)
            assertEquals(1,Settings.Secure.getInt(context.contentResolver,"accessibility_enabled"))
            until {js("window.productReady === true && !document.getElementById('new').disabled")=="true"}
            val count=js("document.querySelectorAll('.session-row').length").toInt()
            js("document.getElementById('new').click()")
            until {js("document.querySelectorAll('.session-row').length === ${count+1}")=="true"}
            val session=SqliteStore(context,"product.db").use {it.load().getJSONArray("sessions").objects().single {it.getString("id")==host.selectedSession()}}
            assertFalse(session.getJSONObject("capabilities").getBoolean("screen_control"))
            assertFalse(session.getJSONObject("capabilities").getBoolean("debug"))
            assertEquals(savedProfile,context.getSharedPreferences("profiles",0).getString("url",null))
            evidence.put("after",JSONArray(after.toList())).put("status",host.accessibilitySetupStatus()).put("serviceConnected",true).put("screenControl",false).put("debug",false).put("passed",true)
        } finally {
            File(context.filesDir,"physical-accessibility.json").writeText(evidence.toString(2))
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
        }
    }
}
