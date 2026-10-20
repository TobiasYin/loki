package dev.thor.agent

import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Loads the shipped Markdown assets and CSP with synthetic messages only.
 * Does not open product storage, use credentials or contact a model. */
@RunWith(AndroidJUnit4::class)
class ChatMarkdownDeviceTest {
    @Test fun bundledSanitizerRendersMarkdownAndRejectsExecutableContent() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val displayId = InstrumentationRegistry.getArguments().getString("chatDisplay")?.toInt() ?: 4
        val display = requireNotNull(ins.targetContext.getSystemService(DisplayManager::class.java).getDisplay(displayId))
        val context = ins.targetContext.createDisplayContext(display)
        val commands = mutableListOf<org.json.JSONObject>()
        val markdown = """
            # 安全渲染

            **正常强调**

            1. 第一项
            2. 第二项

            | 字段 | 值 |
            | --- | --- |
            | 合成数据 | 42 |

            [公开链接](https://example.org/help)
            [危险链接](javascript:alert(1))
            ![远程图片](https://example.invalid/image.png)
            <img src=x onerror="window.__markdownExecuted=true">
            <script>window.__markdownExecuted=true</script>
            <svg onload="window.__markdownExecuted=true"></svg>
        """.trimIndent()
        val fixture = obj(
            "selected" to "markdown-component",
            "sessions" to JSONArray().put(obj("id" to "markdown-component", "title" to "合成 Markdown 测试", "capabilities" to obj())),
            "messages" to JSONArray().put(obj("id" to "message", "sessionId" to "markdown-component", "role" to "assistant", "text" to markdown)),
            "runs" to JSONArray(), "events" to JSONArray(), "attachments" to JSONArray(), "availability" to obj()
        )
        lateinit var web: WebView
        lateinit var bridge: ChatUiBridge
        fun js(code: String): String {
            val done = CountDownLatch(1)
            var result = ""
            ins.runOnMainSync { web.evaluateJavascript(code) { result = it; done.countDown() } }
            assertTrue("WebView did not answer", done.await(5, TimeUnit.SECONDS))
            return result
        }
        ins.runOnMainSync {
            web = WebView(context)
            web.onResume()
            bridge = ChatUiBridge(web, { commands.add(it) }, { bridge.post("state", fixture); bridge.post("ready", obj()) }, {}, {})
            assertTrue(bridge.supported)
            bridge.load()
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline && js("window.productReady===true && !!document.querySelector('.markdown h1')") != "true") Thread.sleep(50)
            assertEquals("true", js("document.querySelector('.markdown h1')?.textContent==='安全渲染'"))
            assertEquals("true", js("document.querySelector('.markdown strong')?.textContent==='正常强调' && document.querySelectorAll('.markdown ol li').length===2 && !!document.querySelector('.markdown table')"))
            assertEquals("true", js("!window.__markdownExecuted && !document.querySelector('.markdown script,.markdown img,.markdown svg,.markdown [onerror],.markdown [onload],.markdown a[href^=\"javascript:\"]')"))
            assertEquals("true", js("document.querySelectorAll('.markdown a[href]').length===1 && document.querySelector('.markdown a[href]').getAttribute('href')==='https://example.org/help'"))
            assertTrue(commands.all { it.optString("op") == "ready" })
        } finally {
            ins.runOnMainSync { bridge.close(); web.destroy() }
        }
    }
}
