package dev.thor.agent

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real asset/CSP/image loading with synthetic pixels in a detached WebView.
 * Never opens product.db, invokes a model or injects any device input. */
@RunWith(AndroidJUnit4::class)
class ChatToolImagePreviewTest {
    @Test fun secureChatLoadsHistoricalPixelsAndDrawsItsExactMarkerOnlyWhenExpanded() {
        val ins=InstrumentationRegistry.getInstrumentation();val reads=AtomicInteger();val commands=mutableListOf<String>()
        val bitmap=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().use {out->bitmap.eraseColor(0xff3474b8.toInt());check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));out.toByteArray()};bitmap.recycle()
        val transform=obj("left" to 80,"top" to 40,"displayWidth" to 320,"displayHeight" to 180,"imageWidth" to 160,"imageHeight" to 90)
        val fixture=obj("selected" to "image-test","sessions" to JSONArray().put(obj("id" to "image-test","title" to "图片组件测试","capabilities" to obj())),
            "runs" to JSONArray().put(obj("id" to "image-run","sessionId" to "image-test","status" to "completed")),
            "messages" to JSONArray().put(obj("id" to "message","sessionId" to "image-test","runId" to "image-run","role" to "assistant","text" to "组件验证")),"attachments" to JSONArray(),
            "events" to JSONArray().put(obj("runId" to "image-run","sequence" to 1,"type" to "tool_result","toolCallId" to "shot","data" to obj("toolId" to "perception.capture_screen","outcome" to "observed","content" to JSONArray().put(obj("type" to "attachment","attachmentId" to "image")).put(obj("type" to "text","text" to obj("width" to 160,"height" to 90,"frameId" to "frame","transform" to transform).toString())))))
                .put(obj("runId" to "image-run","sequence" to 2,"type" to "tool_result","toolCallId" to "tap","data" to obj("toolId" to "input.tap","outcome" to "submitted","submission" to "submitted","visual" to obj("kind" to "input.tap","attachmentId" to "image","frameId" to "frame","coordinateSpace" to "display_logical","transform" to transform,"points" to JSONArray().put(obj("x" to 240,"y" to 130))))))
                .put(obj("runId" to "image-run","sequence" to 3,"type" to "tool_started","toolCallId" to "search","data" to obj("toolId" to "web.search","arguments" to obj("query" to "星露谷 攻略","count" to 10)))),"availability" to obj())
        lateinit var web:WebView;lateinit var bridge:ChatUiBridge
        fun js(code:String):String {
            val done=CountDownLatch(1);var value=""
            ins.runOnMainSync {web.evaluateJavascript(code){value=it;done.countDown()}}
            assertTrue("WebView did not answer",done.await(5,TimeUnit.SECONDS));return value
        }
        fun waitFor(code:String) {
            val end=SystemClock.elapsedRealtime()+10000
            while(SystemClock.elapsedRealtime()<end){if(js(code)=="true")return;Thread.sleep(50)}
            fail("Image component condition did not settle: $code")
        }
        ins.runOnMainSync {
            web=WebView(ins.targetContext);web.onResume()
            bridge=ChatUiBridge(web,{commands.add(it.optString("op"))},{bridge.post("state",fixture);bridge.post("ready",obj())},{},{},readImage={request->
                reads.incrementAndGet()
                if(request==ChatImageRequest("image-test","image-run","image"))ChatImage("image/jpeg",bytes) else null
            })
            assertTrue(bridge.supported);bridge.load()
        }
        try {
            waitFor("window.productReady===true && document.querySelectorAll('.tool-card').length===3")
            assertEquals(0,reads.get())
            js("Element.prototype.animate=undefined;document.querySelector('.execution-history > summary').click();true")
            assertEquals(0,reads.get())
            js("document.querySelectorAll('.tool-card > summary')[0].click();true")
            waitFor("document.querySelector('.tool-image')?.naturalWidth===160")
            assertTrue(reads.get()>0);assertEquals("true",js("document.querySelector('.tool-body .structured')===null"))
            js("document.querySelectorAll('.tool-card > summary')[0].click();document.querySelectorAll('.tool-card > summary')[1].click();true")
            waitFor("document.querySelector('.tool-image')?.naturalWidth===160 && !document.querySelector('.tool-markers')?.hasAttribute('hidden')")
            assertEquals("true",js("document.querySelector('.tool-marker-ring').getAttribute('cx')==='80' && document.querySelector('.tool-marker-ring').getAttribute('cy')==='45'"))
            js("document.querySelectorAll('.tool-card > summary')[2].click();true")
            assertEquals("true",js("document.querySelector('.tool-search-query').textContent==='星露谷 攻略' && document.querySelectorAll('.tool-body .structured').length===0"))
            js("document.querySelector('.execution-history > summary').click();true")
            waitFor("document.querySelectorAll('.tool-image').length===0")
            assertTrue("No device or model commands",commands.all {it=="ready"})
        }finally {ins.runOnMainSync {bridge.close();web.destroy()}}
    }
}
