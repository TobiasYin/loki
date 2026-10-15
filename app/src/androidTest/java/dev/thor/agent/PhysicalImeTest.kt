package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
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

/** Uses the saved live profile without resetting Native history or credentials. */
@RunWith(AndroidJUnit4::class)
class PhysicalImeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun js(web: WebView, script: String): String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync {web.evaluateJavascript(script) {result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun until(seconds: Int=30, condition: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end) {if(condition())return;Thread.sleep(100)}
        fail("Physical IME condition timed out")
    }
    private fun bounds(web: WebView)=JSONObject(js(web,"(() => {const i=document.getElementById('input').getBoundingClientRect(),s=document.getElementById('send').getBoundingClientRect();return {viewportHeight:innerHeight,inputTop:i.top,inputBottom:i.bottom,sendTop:s.top,sendBottom:s.bottom,sendWidth:s.width,sendHeight:s.height}})()"))
    private fun visible(b: JSONObject) {
        assertTrue("Input must remain above IME: $b",b.getDouble("inputTop")>=0 && b.getDouble("inputBottom")<=b.getDouble("viewportHeight")+1)
        assertTrue("Send/stop must remain above IME: $b",b.getDouble("sendTop")>=0 && b.getDouble("sendBottom")<=b.getDouble("viewportHeight")+1)
        assertTrue(b.getDouble("sendWidth")>=44 && b.getDouble("sendHeight")>=44)
    }
    @Test fun lowerScreenImeKeepsLiveSendAndStopVisible() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit real-device test required",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        assertEquals("passed",context.getSharedPreferences("profiles",0).getString("probe",null))
        val display=args.getString("chatDisplay")!!.toInt()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val ime=context.getSystemService(InputMethodManager::class.java)
        val evidence=JSONObject().put("displayId",display)
        try {
            until {js(web,"window.productReady === true && document.querySelectorAll('.session-row').length>0 && !document.getElementById('new').disabled")=="true"}
            val count=js(web,"document.querySelectorAll('.session-row').length").toInt()
            js(web,"document.getElementById('new').click()")
            until {js(web,"document.querySelectorAll('.session-row').length === ${count+1}")=="true"}
            instrumentation.runOnMainSync {web.requestFocus()}
            js(web,"document.getElementById('input').focus()")
            // WebView does not necessarily open IME for a JavaScript-only focus.
            // Tap the measured editor on the explicit lower display to provide a
            // real platform user gesture; this is UI setup, not a device tool test.
            val editor=JSONObject(js(web,"(() => {const r=document.getElementById('input').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:innerWidth,h:innerHeight}})()"))
            val location=IntArray(2);var x=0;var y=0
            instrumentation.runOnMainSync {web.getLocationOnScreen(location);x=(location[0]+web.width*editor.getDouble("x")/editor.getDouble("w")).toInt();y=(location[1]+web.height*editor.getDouble("y")/editor.getDouble("h")).toInt()}
            val automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input -d $display tap $x $y")).use {it.readBytes()}
            instrumentation.runOnMainSync {ime.showSoftInput(web,InputMethodManager.SHOW_IMPLICIT)}
            until {var shown=false;instrumentation.runOnMainSync {shown=web.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true};shown}
            Thread.sleep(900) // Observe the settled IME, not its small initial animation frame.
            until {runCatching {visible(bounds(web));true}.getOrDefault(false)}
            val before=bounds(web);visible(before);evidence.put("composer",before)
            var bottom=0
            instrumentation.runOnMainSync {bottom=web.rootWindowInsets!!.getInsets(WindowInsets.Type.ime()).bottom}
            evidence.put("imeBottomPixels",bottom)
            assertTrue("Actual software keyboard must occupy the lower screen",bottom>0)
            js(web,"document.getElementById('input').value='Reply only IME_OK. Do not use tools.';document.getElementById('send').click()")
            until {js(web,"document.getElementById('send').textContent === '停止'")=="true"}
            val active=bounds(web);visible(active);evidence.put("activeStop",active)
            until(180) {js(web,"document.getElementById('send').textContent === '发送'")=="true"}
            val state=SqliteStore(context,"product.db").use {it.load()}
            val run=state.getJSONArray("runs").objects().last()
            assertEquals("completed",run.getString("status"))
            assertTrue(state.getJSONArray("messages").objects().any {it.optString("runId")==run.getString("id") && it.optString("role")=="assistant" && it.optString("text").contains("IME_OK")})
            evidence.put("runId",run.getString("id")).put("status",run.getString("status")).put("responseMarker","IME_OK").put("passed",true)
        } finally {
            File(context.filesDir,"physical-ime.json").writeText(evidence.toString(2))
            instrumentation.runOnMainSync {ime.hideSoftInputFromWindow(web.windowToken,0);activity.finish()}
            instrumentation.waitForIdleSync()
        }
    }
}
