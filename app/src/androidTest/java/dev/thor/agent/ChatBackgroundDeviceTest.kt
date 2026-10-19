package dev.thor.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RadioButton
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
class ChatBackgroundDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun emulator() = assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun fixture(context: Context): File {
        val file = File(context.cacheDir, "background-test.png")
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until 160) for (x in 0 until 240) bitmap.setPixel(x, y, if (x < 120) Color.rgb(48, 100, 160) else Color.rgb(230, 160, 90))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return file
    }
    @Test fun privateImageImportBlurPersistenceAndCleanup() {
        emulator()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "background-store-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("background-test-$name", mode)
        }
        context.getSharedPreferences("chat-background", 0).edit().clear().commit()
        val store = ChatBackgroundStore(context)
        try {
            val name = store.import(context, Uri.fromFile(fixture(base)))
            val spec = BackgroundSpec("image", name, 20, 60)
            store.save(ChatBackgroundStore.GLOBAL, spec)
            assertEquals(spec, ChatBackgroundStore(context).effective(null))
            val file = store.file(spec)!!
            val plain = backgroundBitmap(file, 0)!!; val blurred = backgroundBitmap(file, 30)!!
            assertNotEquals(plain.getPixel(118, 80), blurred.getPixel(118, 80))
            plain.recycle(); blurred.recycle()
            store.save("dev.test.game", spec)
            store.remove(ChatBackgroundStore.GLOBAL)
            assertTrue(file.exists()) // Shared image remains referenced by an app.
            assertEquals(spec, store.effective("dev.test.game"))
            assertEquals(BackgroundSpec(), store.effective("dev.test.other"))
            store.remove("dev.test.game")
            assertFalse(file.exists())
            val invalid = File(root, "invalid.txt").apply { writeText("this is not an image") }
            assertTrue(runCatching { store.import(context, Uri.fromFile(invalid)) }.isFailure)
        } finally { root.deleteRecursively(); context.getSharedPreferences("chat-background", 0).edit().clear().commit() }
    }
    @Test fun foregroundAppSwitchesOverrideAndUnknownReturnsToGlobal() {
        emulator()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "background-switch-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("background-switch-$name", mode)
        }
        context.getSharedPreferences("chat-background", 0).edit().clear().commit()
        val store = ChatBackgroundStore(context)
        store.save(ChatBackgroundStore.GLOBAL, BackgroundSpec("wallpaper"))
        store.save("dev.game", BackgroundSpec("image", store.import(context, Uri.fromFile(fixture(base)))))
        store.save("dev.reader", BackgroundSpec())
        val pkg = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val activity = instrumentation.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var controller: ChatBackgroundController? = null
        fun awaitWallpaper(expected: Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 5000
            while (android.os.SystemClock.elapsedRealtime() < end) {
                var current = false
                instrumentation.runOnMainSync { current = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0 }
                if (current == expected) return
                android.os.SystemClock.sleep(50)
            }
            fail("Background did not follow the foreground app")
        }
        try {
            val ready = EmulatorRuntimeTestSupport.awaitReady(activity)
            instrumentation.runOnMainSync {
                (MainActivity::class.java.getDeclaredField("backgrounds").apply { isAccessible = true }.get(activity) as ChatBackgroundController).close()
                controller = ChatBackgroundController(activity, store, { ready.web }, { java.util.concurrent.CompletableFuture.completedFuture(pkg.get()) })
                val container = android.widget.FrameLayout(activity)
                activity.addContentView(container, android.view.ViewGroup.LayoutParams(-1, -1))
                controller!!.attach(container); controller!!.start()
            }
            awaitWallpaper(true)
            pkg.set("dev.game"); awaitWallpaper(false)
            pkg.set(null); awaitWallpaper(true)
            pkg.set("dev.reader"); awaitWallpaper(false)
            pkg.set("dev.unknown"); awaitWallpaper(true)
        } finally {
            instrumentation.runOnMainSync { controller?.close(); activity.finish() }
            context.getSharedPreferences("chat-background", 0).edit().clear().commit(); root.deleteRecursively()
        }
    }
    @Test fun settingsChangeWallpaperFlagsAndImagePresentation() {
        emulator()
        val context = instrumentation.targetContext
        val store = ChatBackgroundStore(context)
        val original = store.get(ChatBackgroundStore.GLOBAL)
        val existed = ChatBackgroundStore.GLOBAL in store.all()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val ready = EmulatorRuntimeTestSupport.awaitReady(activity)
            val show = MainActivity::class.java.getDeclaredMethod("showBackgroundSettings", String::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync {
                show.invoke(activity, ChatBackgroundStore.GLOBAL)
                assertEquals(ChatBackgroundStore.GLOBAL, MainActivity::class.java.getDeclaredField("backgroundPageKey").apply { isAccessible = true }.get(activity))
                val nodes = views(activity.window.decorView)
                assertEquals(3, nodes.filterIsInstance<SeekBar>().size)
                nodes.filterIsInstance<RadioButton>().single { it.text.toString() == "透传系统当前壁纸" }.performClick()
                assertEquals("wallpaper", store.get(ChatBackgroundStore.GLOBAL).mode)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
                val bars = views(activity.window.decorView).filterIsInstance<SeekBar>()
                bars[0].progress = 18; bars[1].progress = 70
            }
            val source = fixture(context)
            // Keep the pre-test image referenced until restoration.
            val retain = "dev.thor.background.retain.${java.util.UUID.randomUUID()}"
            store.save(retain, original)
            try {
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredField("backgroundPickerKey").apply { isAccessible = true }.set(activity, ChatBackgroundStore.GLOBAL)
                    MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                        .apply { isAccessible = true }.invoke(activity, 75, android.app.Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
                }
                val imported = android.os.SystemClock.elapsedRealtime() + 5000
                var selected = false
                while (!selected && android.os.SystemClock.elapsedRealtime() < imported) {
                    instrumentation.runOnMainSync {
                        selected = views(activity.window.decorView).filterIsInstance<RadioButton>()
                            .any { it.text.toString() == "手动设置背景图片" && it.isChecked }
                    }
                    if (!selected) android.os.SystemClock.sleep(40)
                }
                assertTrue("Picker result must refresh the background settings page", selected)
                assertEquals("image", store.get(ChatBackgroundStore.GLOBAL).mode)
                instrumentation.runOnMainSync {
                    store.save(ChatBackgroundStore.GLOBAL, store.get(ChatBackgroundStore.GLOBAL).copy(blur = 12, opacity = 65))
                    val controller = MainActivity::class.java.getDeclaredField("backgrounds").apply { isAccessible = true }.get(activity) as ChatBackgroundController
                    controller.refresh()
                    MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
                }
                val done = CountDownLatch(1)
                var transparent = false
                instrumentation.runOnMainSync {
                    ready.web.evaluateJavascript("document.documentElement.classList.contains('chat-background') && getComputedStyle(document.body).backgroundColor==='rgba(0, 0, 0, 0)'") { transparent = it == "true"; done.countDown() }
                    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                }
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(transparent)
                instrumentation.waitForIdleSync()
                val output = File(context.filesDir, "background-evidence").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot().let { screenshot -> File(output, "image-chat.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle() }
                instrumentation.runOnMainSync {
                    show.invoke(activity, ChatBackgroundStore.GLOBAL)
                    assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "背景来源" })
                }
                instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(250)
                instrumentation.uiAutomation.takeScreenshot().let { screenshot -> File(output, "settings.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle() }
            } finally {
                store.save(ChatBackgroundStore.GLOBAL, original); store.remove(retain)
            }
        } finally {
            if (!existed) store.remove(ChatBackgroundStore.GLOBAL) else store.save(ChatBackgroundStore.GLOBAL, original)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
