package dev.thor.agent

import android.content.Intent
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun javascript(web: WebView, script: String): String {
        var value="";val latch=CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { value=it;latch.countDown() } }
        assertTrue("WebView callback timeout",latch.await(5,TimeUnit.SECONDS));return value
    }
    private fun waitFor(web: WebView, condition: String, seconds: Int=15) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end) { if(javascript(web,condition)=="true")return;Thread.sleep(100) }
        fail("WebView condition not met: $condition")
    }
    @Test fun realLocalBridgeStreamsPersistsAndCollapses() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val web=activity.findViewById<WebView>(android.R.id.custom)
            waitFor(web,"document.getElementById('title')?.textContent !== 'Thor Agent' && !!window.ThorNative")
            val previousCount=javascript(web,"document.querySelectorAll('.session-row').length").toInt()
            javascript(web,"document.getElementById('new').click()")
            waitFor(web,"document.querySelectorAll('.session-row').length === ${previousCount+1}")
            javascript(web,"document.getElementById('input').value='instrumented test';document.getElementById('composer').requestSubmit()")
            waitFor(web,"document.getElementById('send').textContent === '停止'")
            waitFor(web,"document.querySelector('.message.assistant .text')?.textContent.includes('模拟 Agent')")
            waitFor(web,"document.querySelector('.run summary')?.textContent.includes('已完成') && document.getElementById('send').textContent === '发送'")
            assertEquals("false",javascript(web,"document.querySelector('details.run').open"))
            assertTrue(javascript(web,"document.querySelector('#chat').textContent").contains("模拟文本附件"))
            javascript(web,"document.querySelector('details.run').open=true")
            waitFor(web,"document.querySelector('details.run').open === true")
            javascript(web,"document.querySelector('details.run').open=false;document.getElementById('chat').scrollTop=0")
            waitFor(web,"document.querySelector('details.run').open === false")
            instrumentation.waitForIdleSync();Thread.sleep(250) // Allow the compositor to present the verified DOM before capture.
            val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { web.draw(android.graphics.Canvas(bitmap)) }
            java.io.File(instrumentation.targetContext.filesDir,"validation-chat.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val state=SqliteStore(instrumentation.targetContext).use { it.load() }
            assertTrue(state.getJSONArray("runs").objects().any {it.getString("status")=="completed"})
            assertTrue(state.getJSONArray("attachments").length()>0)
            instrumentation.runOnMainSync {
                assertEquals("https://appassets.androidplatform.net/assets/chat/index.html", web.url)
                assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess)
            }
        } finally { instrumentation.runOnMainSync {activity.finish()}; instrumentation.waitForIdleSync() }
    }
    @Test fun realBridgeRejectsUnknownCommandAndRemoteNavigationHasNoBridge() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val web=activity.findViewById<WebView>(android.R.id.custom)
            waitFor(web,"!!window.ThorNative && !!window.receiveNative")
            javascript(web,"ThorNative.postMessage(JSON.stringify({op:'readCredentials'}))")
            waitFor(web,"document.getElementById('notice').textContent.includes('操作被拒绝')")
            instrumentation.runOnMainSync {web.loadUrl("https://example.invalid/attacker")}
            waitFor(web,"document.body?.textContent.includes('Blocked')")
            assertEquals("undefined",javascript(web,"typeof ThorNative").trim('"'))
        } finally {instrumentation.runOnMainSync {activity.finish()}; instrumentation.waitForIdleSync() }
    }
}
