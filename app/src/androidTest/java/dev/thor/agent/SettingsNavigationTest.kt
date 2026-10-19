package dev.thor.agent

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real system back dispatch without saving settings or requesting permissions. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun field(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun page(activity: MainActivity) = field(activity, "settingsView") as? View
    private fun scroll(activity: MainActivity) = views(requireNotNull(page(activity))).filterIsInstance<ScrollView>().single()
    private fun click(activity: MainActivity, label: String) {
        var target: View = views(requireNotNull(page(activity))).filterIsInstance<TextView>().single { it.text.toString() == label }
        while (!target.isClickable) target = target.parent as View
        assertTrue(target.performClick())
    }
    private fun awaitPage(activity: MainActivity, title: String, position: Int? = null) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matches = false
            instrumentation.runOnMainSync {
                val current = page(activity)
                matches = current != null && current.isLaidOut && !current.isLayoutRequested &&
                    views(current).filterIsInstance<TextView>().any { it.text.toString() == title } &&
                    (position == null || scroll(activity).scrollY == position)
                assertFalse("System back must not finish the Activity inside settings", activity.isFinishing)
            }
            if (matches) return
            SystemClock.sleep(40)
        }
        fail("Did not return to $title at scroll position $position")
    }

    @Test fun systemAndToolbarBackPreserveParentScroll() {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        EmulatorRuntimeTestSupport.awaitReady(activity)
        instrumentation.runOnMainSync {
            // Keep a phone-sized viewport even on the tall desktop emulator display.
            val container = field(activity, "container") as View
            container.layoutParams = container.layoutParams.apply {
                height = (480 * activity.resources.displayMetrics.density).toInt()
            }
            // Test normal settings even on a fresh emulator that still shows onboarding.
            MainActivity::class.java.getDeclaredField("onboardingStep").apply { isAccessible = true }.set(activity, null)
            MainActivity::class.java.getDeclaredMethod("showSettings", String::class.java).apply { isAccessible = true }.invoke(activity, null)
        }
        awaitPage(activity, "设置")
        var rootPosition = 0
        instrumentation.runOnMainSync {
            scroll(activity).scrollTo(0, scroll(activity).getChildAt(0).height)
            rootPosition = scroll(activity).scrollY
            assertTrue("Settings list must be scrolled for this regression", rootPosition > 0)
        }
        for ((entry, title) in listOf("设备与调试" to "设备与权限", "页面配色" to "页面配色",
            "联网搜索" to "联网搜索", "语音输入" to "语音输入", "应用权限" to "应用权限")) {
            instrumentation.runOnMainSync { click(activity, entry) }
            awaitPage(activity, title)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitPage(activity, "设置", rootPosition)
        }
        instrumentation.runOnMainSync { click(activity, "聊天背景") }
        awaitPage(activity, "聊天背景")
        var backgroundPosition = 0
        instrumentation.runOnMainSync {
            scroll(activity).scrollTo(0, scroll(activity).getChildAt(0).height)
            backgroundPosition = scroll(activity).scrollY
            assertTrue(backgroundPosition > 0)
            click(activity, "上屏应用背景")
        }
        awaitPage(activity, "上屏应用背景")
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("showBackgroundSettings", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "dev.thor.navigation.test")
        }
        awaitPage(activity, "应用背景")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPage(activity, "上屏应用背景")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPage(activity, "聊天背景", backgroundPosition)
        instrumentation.runOnMainSync { click(activity, "‹ 返回") }
        awaitPage(activity, "设置", rootPosition)
        instrumentation.runOnMainSync { click(activity, "模型服务") }
        awaitPage(activity, "模型服务")
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("showProviderEditor", org.json.JSONObject::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(activity, null, "custom")
        }
        awaitPage(activity, "添加模型服务")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPage(activity, "模型服务")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPage(activity, "设置", rootPosition)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertNull(page(activity))
            assertFalse(activity.isFinishing)
            MainActivity::class.java.getDeclaredMethod("showSettings", String::class.java).apply { isAccessible = true }.invoke(activity, null)
        }
        awaitPage(activity, "设置", 0)
    }
}
