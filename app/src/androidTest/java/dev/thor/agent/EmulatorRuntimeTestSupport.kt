package dev.thor.agent

import android.os.SystemClock
import android.webkit.WebView
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
    data class Ready(val web: WebView, val host: ProductHost)

    fun awaitReady(activity: MainActivity, previousWeb: WebView? = null): Ready {
        val end = SystemClock.elapsedRealtime() + 45_000
        val hostField = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
        while (SystemClock.elapsedRealtime() < end) {
            var attached: Ready? = null
            instrumentation.runOnMainSync {
                val web = activity.findViewById<WebView>(android.R.id.custom)
                if (web != null && web !== previousWeb) {
                    val host = hostField.get(activity) as? ProductHost
                    if (host != null) attached = Ready(web, host)
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
                if (initialized) return ready
            }
            Thread.sleep(40)
        }
        fail("Service engine/WebView did not attach and initialize within 45 seconds")
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
