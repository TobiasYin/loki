package dev.thor.agent

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.RadioButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppearanceDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nextActivity(previous: MainActivity): MainActivity {
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var next: MainActivity? = null
            ins.runOnMainSync {
                next = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== previous }
            }
            if (next != null) return next!!
            android.os.SystemClock.sleep(40)
        }
        error("Appearance selection did not recreate its native surfaces")
    }
    private fun js(web: WebView, script: String): String {
        val done = CountDownLatch(1); var value = ""
        ins.runOnMainSync { web.evaluateJavascript(script) { value = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value
    }
    @Test fun threeModesRepaintNativeAndChatWhileKeepingSessionAndExecution() {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val context = ins.targetContext
        val systemMode = context.getSystemService(android.app.UiModeManager::class.java).nightMode
        var systemChanged = false
        fun systemNight(value: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("cmd uimode night $value")).use { it.readBytes() }
        }
        val prefs = context.getSharedPreferences("appearance", 0)
        val originalMode = prefs.getString("mode", null)
        val onboarding = context.getSharedPreferences("onboarding", 0)
        val completed = onboarding.getBoolean("completed", false)
        onboarding.edit().putBoolean("completed", true).commit()
        val store = ChatBackgroundStore(context)
        val originalBackground = store.get(ChatBackgroundStore.GLOBAL)
        val hadBackground = ChatBackgroundStore.GLOBAL in store.all()
        // Retain any existing image reference while exercising the wallpaper scrim.
        store.save(ChatBackgroundStore.GLOBAL, originalBackground.copy(mode = "wallpaper", opacity = 100))
        Appearance.save(context, ColorMode.SYSTEM)
        var activity: MainActivity? = null
        try {
            activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val first = EmulatorRuntimeTestSupport.awaitReady(activity!!)
            val session = first.host.selectedSession()
            val show = MainActivity::class.java.getDeclaredMethod("showAppearanceSettings").apply { isAccessible = true }
            for (mode in listOf(ColorMode.LIGHT, ColorMode.DARK, ColorMode.SYSTEM)) {
                val before = activity!!
                ins.runOnMainSync {
                    show.invoke(before)
                    views(before.window.decorView).filterIsInstance<RadioButton>().single { it.text.toString() == mode.label }.performClick()
                }
                activity = nextActivity(before)
                val ready = EmulatorRuntimeTestSupport.awaitReady(activity!!)
                val expectedDark = mode.isDark(context.resources.configuration.uiMode and 0x30 == 0x20)
                assertEquals(mode, Appearance.mode(activity!!))
                assertEquals(expectedDark, Appearance.palette(activity!!).dark)
                assertSame("Theme switch must preserve the display document", first.web, ready.web)
                assertSame("Theme switch must preserve execution", first.executionWeb, ready.executionWeb)
                assertSame(first.host, ready.host)
                assertEquals(session, ready.host.selectedSession())
                assertEquals(if (expectedDark) "\"dark\"" else "\"light\"", js(ready.web, "document.documentElement.dataset.theme"))
                assertEquals("true", js(ready.web, "document.documentElement.classList.contains('chat-background')"))
                ins.runOnMainSync {
                    val current = activity!!
                    val radios = views(current.window.decorView).filterIsInstance<RadioButton>()
                    assertEquals(mode.label, radios.single { it.isChecked }.text.toString())
                    val controller = MainActivity::class.java.getDeclaredField("backgrounds").apply { isAccessible = true }.get(current) as ChatBackgroundController
                    val veil = ChatBackgroundController::class.java.getDeclaredField("veil").apply { isAccessible = true }.get(controller) as View
                    assertEquals(AppearancePalette(expectedDark).scrimAlpha, veil.alpha, .0001f)
                    val flags = current.window.insetsController!!.systemBarsAppearance
                    assertEquals(!expectedDark, flags and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
                }
            }
            // The real system configuration must also repaint a follow-system page.
            val previous = activity!!
            val nextDark = !(context.resources.configuration.uiMode and 0x30 == 0x20)
            systemChanged = true
            systemNight(if (nextDark) "yes" else "no")
            activity = nextActivity(previous)
            val afterSystem = EmulatorRuntimeTestSupport.awaitReady(activity!!)
            assertEquals(nextDark, Appearance.palette(activity!!).dark)
            assertEquals(if (nextDark) "\"dark\"" else "\"light\"", js(afterSystem.web, "document.documentElement.dataset.theme"))
            assertSame(first.executionWeb, afterSystem.executionWeb)
            assertSame(first.web, afterSystem.web)
        } finally {
            prefs.edit().apply { if (originalMode == null) remove("mode") else putString("mode", originalMode) }.commit()
            if (hadBackground) store.save(ChatBackgroundStore.GLOBAL, originalBackground) else store.remove(ChatBackgroundStore.GLOBAL)
            onboarding.edit().putBoolean("completed", completed).commit()
            ins.runOnMainSync { activity?.finish() }
            if (systemChanged) systemNight(when (systemMode) { android.app.UiModeManager.MODE_NIGHT_YES -> "yes"; android.app.UiModeManager.MODE_NIGHT_NO -> "no"; android.app.UiModeManager.MODE_NIGHT_CUSTOM -> "custom"; else -> "auto" })
        }
    }
}
