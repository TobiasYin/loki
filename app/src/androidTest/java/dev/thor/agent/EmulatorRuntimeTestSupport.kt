package dev.thor.agent

import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only synchronization with the asynchronously bound, single-writer Service engine. */
internal object EmulatorRuntimeTestSupport {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    data class Ready(val web: WebView, val host: ProductHost, val executionWeb: WebView)

    fun awaitReady(activity: MainActivity, previousWeb: WebView? = null): Ready {
        val started=SystemClock.elapsedRealtime()
        val progress=JSONObject().put("attached",false).put("chatJsReady",false).put("executionJsReady",false)
        try {
        val end = SystemClock.elapsedRealtime() + 45_000
        val hostField = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
        while (SystemClock.elapsedRealtime() < end) {
            var attached: Ready? = null
            instrumentation.runOnMainSync {
                val web = activity.findViewById<WebView>(android.R.id.custom)
                if (web != null && web !== previousWeb) {
                    val host = hostField.get(activity) as? ProductHost
                    val execution = executionOrNull(activity)
                    progress.put("attached",true).put("hostAssigned",host!=null).put("executionAssigned",execution!=null)
                        .put("chatAttached",web.isAttachedToWindow).put("chatVisibility",web.windowVisibility)
                        .put("chatRoute",if(web.url==BridgePolicy.PAGE)"chat" else "other_or_unloaded")
                        .put("executionAttached",execution?.isAttachedToWindow ?: false)
                    if (host != null && execution != null) attached = Ready(web, host, execution)
                }
            }
            val ready = attached
            if (ready != null) {
                val done = CountDownLatch(1)
                var initialized = false
                instrumentation.runOnMainSync {
                    ready.web.evaluateJavascript("window.productReady===true && typeof window.receiveNative==='function'") {
                        initialized = it == "true"
                        done.countDown()
                    }
                }
                assertTrue("Attached Service WebView did not answer readiness check", done.await(5, TimeUnit.SECONDS))
                progress.put("chatJsReady",initialized)
                val executionReady=initialized && documentReady(ready.executionWeb, "window.productReady===true && typeof window.receiveProductState==='function'")
                progress.put("executionJsReady",executionReady)
                if (executionReady) {
                    assertNotSame("Chat must not own the execution document", ready.web, ready.executionWeb)
                    startupReport(activity,started,progress,true)
                    return ready
                }
            }
            Thread.sleep(40)
        }
        fail("Service engine/WebView did not attach and initialize within 45 seconds")
        error("unreachable")
        }catch(error:Throwable){startupReport(activity,started,progress,false);throw error}
    }

    /** DEBUG instrumentation only: counts, fixed phase names and screen enums.
     * No URLs, arguments, model text, keys, leases, prefs or database reads. */
    private fun startupReport(activity:MainActivity,started:Long,progress:JSONObject,ready:Boolean) {
        runCatching {
            val context=instrumentation.targetContext
            val report=JSONObject().put("schemaVersion",1).put("ready",ready).put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("readiness",progress).put("phases",RuntimeStartupDiagnostics.snapshot())
                .put("deviceInteractive",context.getSystemService(android.os.PowerManager::class.java).isInteractive)
                .put("activityDestroyed",activity.isDestroyed).put("activityFinishing",activity.isFinishing)
                .put("displays",JSONArray(context.getSystemService(android.hardware.display.DisplayManager::class.java).displays.map {display->
                    JSONObject().put("displayId",display.displayId).put("state",when(display.state){android.view.Display.STATE_ON->"on";android.view.Display.STATE_OFF->"off";android.view.Display.STATE_DOZE,android.view.Display.STATE_DOZE_SUSPEND->"doze";else->"other"})
                }))
            File(context.filesDir,"runtime-startup-last.json").writeText(report.toString(2))
            if(!ready)File(context.filesDir,"runtime-startup-timeout.json").writeText(report.toString(2))
        }
    }

    private fun executionOrNull(activity: MainActivity): WebView? {
        val engine = MainActivity::class.java.getDeclaredField("attachedEngine").apply {isAccessible=true}.get(activity) ?: return null
        return runCatching {engine.javaClass.getDeclaredField("executionWeb").apply {isAccessible=true}.get(engine) as? WebView}.getOrNull()
    }

    /** Test reflection only. Runtime hooks/bridge RPC never belong to chat. */
    fun executionWeb(activity: MainActivity): WebView {
        var result: WebView? = null
        instrumentation.runOnMainSync {result=executionOrNull(activity)}
        return requireNotNull(result) {"Service execution document is not attached to this Activity's engine yet"}
    }

    private fun documentReady(web: WebView, expression: String): Boolean {
        val done=CountDownLatch(1);var ready=false
        instrumentation.runOnMainSync {web.evaluateJavascript(expression){ready=it=="true";done.countDown()}}
        assertTrue("Execution document did not answer readiness check",done.await(5,TimeUnit.SECONDS))
        return ready
    }

    fun awaitExecutionReady(activity: MainActivity): WebView {
        val end=SystemClock.elapsedRealtime()+45_000
        while(SystemClock.elapsedRealtime()<end) {
            var web:WebView?=null
            instrumentation.runOnMainSync {web=executionOrNull(activity)}
            val current=web
            if(current!=null && documentReady(current,"window.productReady===true && typeof window.receiveProductState==='function'"))return current
            Thread.sleep(40)
        }
        fail("Service execution document did not initialize within 45 seconds")
        error("unreachable")
    }

    /** Reload only through the Service owner, which closes the old writer before creating another. */
    fun reloadIdle(activity: MainActivity): Ready {
        val previous = awaitReady(activity)
        assertFalse("Never reload an engine with existing user work", previous.host.hasActiveRuns())
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("reloadRuntime").apply { isAccessible = true }.invoke(activity)
        }
        val current = awaitReady(activity, previous.web)
        assertNotSame("Idle configuration reload must replace the old host", previous.host, current.host)
        return current
    }
}
