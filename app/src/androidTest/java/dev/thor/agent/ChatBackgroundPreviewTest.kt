package dev.thor.agent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChatBackgroundPreviewTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun until(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < end) { var done = false; ins.runOnMainSync { done = condition() }; if (done) return; SystemClock.sleep(40) }
        fail("Preview did not reach the expected state")
    }
    @Test fun draggingUpdatesFixedSyntheticPreviewAndChatFillWithoutChangingTheConversation() {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val context = ins.targetContext
        val store = ChatBackgroundStore(context)
        val key = "dev.thor.preview.fixture"
        val source = File(context.cacheDir, "preview-test.png")
        Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until 240) for (x in 0 until 320) bitmap.setPixel(x, y, if (x < 160) Color.rgb(45, 130, 140) else Color.rgb(220, 145, 65))
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val original = store.all()[key]
        val retain = "dev.thor.preview.retain"
        if (original != null) store.save(retain, original)
        store.save(key, BackgroundSpec("image", store.import(context, Uri.fromFile(source)), 0, 100, 84))
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val ready = EmulatorRuntimeTestSupport.awaitReady(activity)
            val session = ready.host.selectedSession()
            ins.runOnMainSync { MainActivity::class.java.getDeclaredMethod("showBackgroundSettings", String::class.java).apply { isAccessible = true }.invoke(activity, key) }
            until { views(activity.window.decorView).filterIsInstance<ChatBackgroundPreview>().singleOrNull()?.width?.let { it > 0 } == true }
            var preview: ChatBackgroundPreview? = null
            val before = IntArray(2)
            ins.runOnMainSync {
                preview = views(activity.window.decorView).filterIsInstance<ChatBackgroundPreview>().single()
                preview!!.getLocationOnScreen(before)
                val scroller = MainActivity::class.java.getDeclaredField("settingsScroll").apply { isAccessible = true }.get(activity) as android.widget.ScrollView
                val cardSlider = views(activity.window.decorView).filterIsInstance<SeekBar>().single { it.contentDescription == "卡片透明度" }
                cardSlider.requestRectangleOnScreen(android.graphics.Rect(0, -70, cardSlider.width, cardSlider.height), true)
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val after = IntArray(2); preview!!.getLocationOnScreen(after); assertArrayEquals(before, after)
                val slider = views(activity.window.decorView).filterIsInstance<SeekBar>().single { it.contentDescription == "卡片透明度" }
                assertTrue(slider.isEnabled)
                val track = slider.width - slider.paddingLeft - slider.paddingRight
                val y = slider.height / 2f; val start = slider.paddingLeft + track * slider.progress / 100f
                val end = slider.paddingLeft + track * .75f; val time = SystemClock.uptimeMillis()
                listOf(Triple(MotionEvent.ACTION_DOWN, start, 0L), Triple(MotionEvent.ACTION_MOVE, end, 16L), Triple(MotionEvent.ACTION_UP, end, 32L)).forEach { (action, x, offset) ->
                    MotionEvent.obtain(time, time + offset, action, x, y, 0).also { slider.onTouchEvent(it); it.recycle() }
                }
            }
            until { store.get(key).cardOpacity in 20..30 && preview!!.spec == store.get(key) }
            val current = store.get(key)
            assertEquals(100, current.opacity)
            assertEquals(session, ready.host.selectedSession())
            val jsDone = CountDownLatch(1); var alpha = ""
            ins.runOnMainSync { ready.web.evaluateJavascript("document.documentElement.style.getPropertyValue('--card-alpha')") { alpha = it; jsDone.countDown() } }
            assertTrue(jsDone.await(5, TimeUnit.SECONDS)); assertEquals("\"${current.cardOpacity / 100.0}\"", alpha)
            ins.runOnMainSync {
                assertTrue(preview!!.contentDescription.toString().contains("卡片透明度"))
                assertEquals("虚拟聊天 · 实时预览", views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString().contains("虚拟聊天 ·") }.text.toString())
                MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
            }
            assertEquals(session, ready.host.selectedSession())
        } finally {
            ins.runOnMainSync { activity.finish() }
            if (original == null) store.remove(key) else store.save(key, original)
            if (original != null) store.remove(retain)
            source.delete()
        }
    }
    @Test fun physicalWallpaperAndCardAdjustmentsStayVisibleInThePreview() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physicalAcceptance") == "true")
        val automation = ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = ins.targetContext
        val store = ChatBackgroundStore(context)
        val original = store.get(ChatBackgroundStore.GLOBAL)
        val existed = ChatBackgroundStore.GLOBAL in store.all()
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
        val ready = EmulatorRuntimeTestSupport.awaitReady(activity)
        val output = File(context.filesDir, "preview-evidence").apply { mkdirs() }
        val show = MainActivity::class.java.getDeclaredMethod("showBackgroundSettings", String::class.java).apply { isAccessible = true }
        try {
            assertFalse(ready.host.hasActiveRuns())
            fun capture(name: String) {
                ins.waitForIdleSync(); SystemClock.sleep(350)
                File(output, "$name.png").writeBytes(android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("screencap -p -d 4630946482288158084")).use { it.readBytes() })
            }
            ins.runOnMainSync {
                store.save(ChatBackgroundStore.GLOBAL, original.copy(mode = "wallpaper", blur = 0, opacity = 100, cardOpacity = 100))
                show.invoke(activity, ChatBackgroundStore.GLOBAL)
            }
            capture("wallpaper-solid-cards")
            var preview: ChatBackgroundPreview? = null
            ins.runOnMainSync {
                preview = views(activity.window.decorView).filterIsInstance<ChatBackgroundPreview>().single()
                val scroll = MainActivity::class.java.getDeclaredField("settingsScroll").apply { isAccessible = true }.get(activity) as android.widget.ScrollView
                val cardSlider = views(activity.window.decorView).filterIsInstance<SeekBar>().single { it.contentDescription == "卡片透明度" }
                cardSlider.requestRectangleOnScreen(android.graphics.Rect(0, -70, cardSlider.width, cardSlider.height), true)
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val slider = views(activity.window.decorView).filterIsInstance<SeekBar>().single { it.contentDescription == "卡片透明度" }
                val time = SystemClock.uptimeMillis(); val track = slider.width - slider.paddingLeft - slider.paddingRight
                val start = slider.paddingLeft.toFloat(); val target = slider.paddingLeft + track * .75f; val y = slider.height / 2f
                listOf(Triple(MotionEvent.ACTION_DOWN, start, 0L), Triple(MotionEvent.ACTION_MOVE, target, 16L), Triple(MotionEvent.ACTION_UP, target, 32L)).forEach { (action, x, offset) ->
                    MotionEvent.obtain(time, time + offset, action, x, y, 0).also { slider.onTouchEvent(it); it.recycle() }
                }
            }
            until { preview!!.spec.cardOpacity in 20..30 }
            ins.runOnMainSync {
                val scroll = MainActivity::class.java.getDeclaredField("settingsScroll").apply { isAccessible = true }.get(activity) as android.widget.ScrollView
                val slider = views(activity.window.decorView).filterIsInstance<SeekBar>().single { it.contentDescription == "卡片透明度" }
                val bounds = android.graphics.Rect(0, 0, slider.width, slider.height)
                (scroll.getChildAt(0) as ViewGroup).offsetDescendantRectToMyCoords(slider, bounds)
                scroll.scrollTo(0, maxOf(0, bounds.top - scroll.height / 2))
            }
            capture("wallpaper-transparent-cards")
            File(output, "verification.json").writeText(org.json.JSONObject().put("displayId", 4).put("wallpaperMode", preview!!.spec.mode)
                .put("cardOpacity", preview!!.spec.cardOpacity).put("previewWidth", preview!!.width).put("previewHeight", preview!!.height)
                .put("syntheticContent", true).put("dragUpdatedPreview", true).toString(2))
        } finally {
            ins.runOnMainSync {
                if (existed) store.save(ChatBackgroundStore.GLOBAL, original) else store.remove(ChatBackgroundStore.GLOBAL)
                MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
            }
        }
    }

}
