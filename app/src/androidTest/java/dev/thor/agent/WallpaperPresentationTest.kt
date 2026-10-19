package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real window-buffer alpha and composed-display evidence; no sends, grants or wallpaper changes. */
@RunWith(AndroidJUnit4::class)
class WallpaperPresentationTest {
    @Test fun wallpaperCompositesOnTheChatDisplay() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physicalAcceptance") == "true")
        val automation = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = ins.targetContext
        val store = ChatBackgroundStore(context)
        val existed = ChatBackgroundStore.GLOBAL in store.all()
        val original = store.get(ChatBackgroundStore.GLOBAL)
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay", "4").toInt()).toBundle()) as MainActivity
        val ready = EmulatorRuntimeTestSupport.awaitReady(activity)
        val output = File(context.filesDir, "wallpaper-evidence").apply { mkdirs() }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        fun capture(name: String) { File(output, "$name.png").writeBytes(shell("screencap -p -d ${args.getString("physicalDisplay", "4630946482288158084")}")) }
        fun views(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        val report = JSONObject()
        val controller = MainActivity::class.java.getDeclaredField("backgrounds").apply { isAccessible = true }.get(activity) as ChatBackgroundController
        try {
            assertFalse("Do not alter appearance during a live task", ready.host.hasActiveRuns())
            ins.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
                store.save(ChatBackgroundStore.GLOBAL, original.copy(mode = "wallpaper", blur = 0, opacity = 100))
                controller.refresh()
            }
            ins.waitForIdleSync(); SystemClock.sleep(600); capture("wallpaper-chat")
            ins.runOnMainSync {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
                assertEquals(PixelFormat.TRANSLUCENT, activity.window.attributes.format)
                ready.web.visibility = View.INVISIBLE
            }
            ins.waitForIdleSync(); SystemClock.sleep(300)
            val pixelsDone = CountDownLatch(1)
            ins.runOnMainSync {
                val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(activity.window, bitmap, { code ->
                    report.put("pixelCopy", code).put("nativeAlpha", Color.alpha(bitmap.getPixel(100, 500)))
                        .put("expectedAlpha", (Appearance.palette(activity).scrimAlpha * 255).toInt())
                    bitmap.recycle(); pixelsDone.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue(pixelsDone.await(5, TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS, report.getInt("pixelCopy"))
            assertTrue("Window must expose wallpaper instead of retaining opaque startup pixels", report.getInt("nativeAlpha") < 255)
            assertEquals(report.getInt("expectedAlpha").toDouble(), report.getInt("nativeAlpha").toDouble(), 2.0)
            ins.runOnMainSync { ready.web.visibility = View.VISIBLE }
        } finally {
            ins.runOnMainSync {
                if (existed) store.save(ChatBackgroundStore.GLOBAL, original) else store.remove(ChatBackgroundStore.GLOBAL)
                ready.web.visibility = View.VISIBLE; controller.refresh()
            }
        }
        // Verify the actual secondary-page layout and live foreground identity on Thor.
        ins.runOnMainSync { MainActivity::class.java.getDeclaredMethod("chooseBackgroundApp").apply { isAccessible = true }.invoke(activity) }
        val end = SystemClock.elapsedRealtime() + 5000
        var loaded = false
        while (!loaded && SystemClock.elapsedRealtime() < end) {
            ins.runOnMainSync { loaded = views(activity.window.decorView).filterIsInstance<ListView>().singleOrNull()?.adapter?.count?.let { it > 0 } == true }
            if (!loaded) SystemClock.sleep(50)
        }
        assertTrue(loaded)
        val upper = ready.host.backgroundUpperPackage().get(5, TimeUnit.SECONDS)
        ins.runOnMainSync {
            val nodes = views(activity.window.decorView)
            assertTrue(nodes.filterIsInstance<ImageView>().any { it.drawable != null })
            if (upper != null) {
                assertFalse(nodes.filterIsInstance<TextView>().any { it.text.toString() == upper })
                report.put("upperAppIdentified", true)
            }
            report.put("displayId", activity.display?.displayId).put("appCount", nodes.filterIsInstance<ListView>().single().adapter.count)
        }
        ins.waitForIdleSync(); SystemClock.sleep(300); capture("apps-page")
        File(output, "verification.json").writeText(report.toString(2))
    }
}
