package dev.thor.agent

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class AgentActionFeedbackDeviceTest {
    @Test fun overlayDoesNotChangeInputTargetAndActionsReturnFreshImages() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val automation = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val beforeServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val beforeEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val beforeFeedback = AgentActionFeedback.enabled(context)
        val image = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay("Action feedback fixture", 640, 480, 160,
            image.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        fun feedbackWindows(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys window windows")).use { it.readBytes().toString(Charsets.UTF_8) }
            .split(Regex("(?m)^  Window #")).drop(1).filter { it.substringBefore('\n').contains("Loki action feedback") }.joinToString("\n")
        val temporary = File(context.cacheDir, "feedback-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val debug = DebugConnection(context) {}
        try {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                val name = ComponentName(context, ThorAccessibilityService::class.java).flattenToString()
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    beforeServices.orEmpty().split(':').filter { it.isNotBlank() && it != name }.joinToString(":"))
                Thread.sleep(500)
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (beforeServices.orEmpty().split(':').filter { it.isNotBlank() } + name).distinct().joinToString(":"))
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } finally { automation.dropShellPermissionIdentity() }
            val end = SystemClock.elapsedRealtime() + 15_000
            while (ThorAccessibilityService.instance == null && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            val service = ThorAccessibilityService.instance ?: error("Accessibility service did not bind")
            context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab", "dev.thor.inputlab.ScenarioActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1000)
            ins.runOnMainSync { AgentActionFeedback.setEnabled(context, true) }
            val targetDeadline = SystemClock.elapsedRealtime() + 8000
            while (service.target(0).packageName != "dev.thor.inputlab" && SystemClock.elapsedRealtime() < targetDeadline) Thread.sleep(100)
            val target = service.target(0)
            assertEquals("Target unavailable: $target; windows=${service.windowsOnAllDisplays}", "dev.thor.inputlab", target.packageName)
            var shown = false
            ins.runOnMainSync { shown = service.actionOverlay.show(0, listOf(Point(300.0, 500.0), Point(500.0, 700.0)), "input.swipe", 500) }
            assertTrue("Real accessibility overlay must attach", shown)
            Thread.sleep(200)
            assertTrue("Own feedback must not invalidate the target", target.sameInputTarget(service.target(0)))
            assertTrue("Overlay must exist on display 0", feedbackWindows().contains("mDisplayId=0"))
            automation.takeScreenshot()?.let { bitmap ->
                try { File(context.filesDir, "agent-action-overlay.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            ins.runOnMainSync { AgentActionFeedback.setEnabled(context, false) }
            Thread.sleep(200)
            assertTrue("Disabling must remove the real window", feedbackWindows().isEmpty())
            ins.runOnMainSync { shown = service.actionOverlay.show(0, listOf(Point(300.0, 500.0)), "input.tap", 80) }
            assertFalse(shown)
            // Verify overlay routing on the second display as well.
            ins.runOnMainSync {
                AgentActionFeedback.setEnabled(context, true)
                shown = service.actionOverlay.show(display.display.displayId, listOf(Point(100.0, 100.0)), "input.tap", 400)
            }
            assertTrue("Secondary-display overlay must attach", shown)
            Thread.sleep(200)
            assertTrue("Overlay must use the secondary display", feedbackWindows().contains("mDisplayId=${display.display.displayId}"))
            ins.runOnMainSync { service.actionOverlay.clear() }
            Thread.sleep(200)
            val clock = Clock { SystemClock.elapsedRealtime() }
            val facts = displayFacts(context)
            val snapshots = Snapshots(clock) { service.target(it) }.apply { update(facts); calibrate(0, display.display.displayId) }
            val local = LocalFiles(File(temporary, "config").toPath())
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val tx = ConfigTransactions(local, EncryptedVault(File(temporary, "backups").toPath(), key, clock)) {}
            val backend = AndroidBackend(context, clock, snapshots, debug, local, tx).apply { postActionCaptureCheck = {} }
            val authority = ManualTestAuthority(clock).apply { set(Capability.SCREEN_CONTROL, true) }
            val lease = authority.begin(backend)
            val gateway = Gateway(authority, backend, snapshots, clock)
            val performance = org.json.JSONArray()
            fun recordTiming(result: ToolResult, scenario: String) {
                val timing = result.presentation["screenshotTiming"] as? Map<*, *> ?: error("Missing native screenshot timings")
                assertEquals(false, timing["copyOnMainThread"])
                assertFalse("Native diagnostics must not add model content", result.data.containsKey("screenshotTiming"))
                performance.put(org.json.JSONObject(timing).put("scenario", scenario))
            }
            for (enabled in listOf(true, false)) {
                ins.runOnMainSync { AgentActionFeedback.setEnabled(context, enabled) }
                val source = snapshots.capture()
                val clip = source.target.touchableBounds!!
                val x = (clip.left + clip.right) / 2.0
                val y = (clip.top + clip.bottom) / 2.0
                val e = Envelope(lease.session, lease.run, java.util.UUID.randomUUID().toString(), "input.tap", lease.epoch, lease.deadline, source.id)
                val result = gateway.execute(e, mapOf("x" to x, "y" to y, "coordinateSpace" to "display_logical"))
                assertNull("Action failed: ${result.message}", result.error)
                assertEquals("Automatic screenshot failed: ${result.data}", true, result.data["postActionScreenshot"])
                assertEquals("completed", result.data["systemCallback"])
                assertEquals(80, result.data["quality"])
                recordTiming(result, "automatic-tap-feedback-$enabled")
                assertNotEquals(source.id, result.snapshotId)
                assertTrue(File(result.data["attachmentRef"] as String).length() > 0)
                assertNotNull(result.data["frameId"])
                assertEquals(0, snapshots.validateObservation(result.snapshotId).display.id)
                assertTrue("Feedback must be absent from post-action capture", feedbackWindows().isEmpty())
                val transform = result.data["transform"] as Map<*, *>
                val imageWidth = (transform["imageWidth"] as Number).toDouble()
                val imageHeight = (transform["imageHeight"] as Number).toDouble()
                val swipe = gateway.execute(e.copy(toolCallId = java.util.UUID.randomUUID().toString(), requestId = java.util.UUID.randomUUID().toString(),
                    toolId = "input.swipe", snapshotId = result.snapshotId), mapOf("points" to listOf(
                        listOf(imageWidth * 0.5, imageHeight * 0.55), listOf(imageWidth * 0.5, imageHeight * 0.45)),
                        "durationMs" to 300, "coordinateSpace" to "frame_pixels", "frameId" to result.data["frameId"]))
                assertNull("Returned frame must support the next gesture: ${swipe.message}", swipe.error)
                assertEquals(true, swipe.data["postActionScreenshot"])
                assertEquals("completed", swipe.data["systemCallback"])
                assertEquals(80, swipe.data["quality"])
                recordTiming(swipe, "automatic-swipe-feedback-$enabled")
                File(result.data["attachmentRef"] as String).copyTo(File(context.filesDir, "agent-action-after.jpg"), overwrite = true)
            }
            // Alternate quality on the same static fixture, including cold/warm samples.
            repeat(6) { sample ->
                for (quality in listOf(80, 60)) {
                    val observed = snapshots.capture()
                    val capture = gateway.execute(Envelope(lease.session, lease.run, "quality-$quality-$sample",
                        "perception.capture_screen", lease.epoch, lease.deadline, observed.id), mapOf("quality" to quality))
                    assertNull("Benchmark screenshot failed: ${capture.message}", capture.error)
                    assertEquals(quality, capture.data["quality"])
                    recordTiming(capture, "quality-comparison-$sample")
                    if (sample == 5) File(capture.data["attachmentRef"] as String).copyTo(
                        File(context.filesDir, "screenshot-quality-$quality.jpg"), overwrite = true)
                }
            }
            File(context.filesDir, "screenshot-performance.json").writeText(org.json.JSONObject()
                .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("scene", "offline-inputlab-selector").put("samples", performance).toString(2))
            // Capture denial preserves the submitted action and reports a separate screenshot failure.
            backend.postActionCaptureCheck = { fail(Code.CAPABILITY_DISABLED, "Test disabled perception") }
            val source = snapshots.capture(); val clip = source.target.touchableBounds!!
            val result = gateway.execute(Envelope(lease.session, lease.run, "denied-image", "input.tap", lease.epoch, lease.deadline, source.id),
                mapOf("x" to (clip.left + clip.right) / 2.0, "y" to (clip.top + clip.bottom) / 2.0, "coordinateSpace" to "display_logical"))
            assertNull(result.error); assertEquals("submitted", result.outcome)
            assertEquals(false, result.data["postActionScreenshot"])
            assertEquals("CAPABILITY_DISABLED", result.data["screenshotError"])
            authority.finish(lease.run)
        } finally {
            ins.runOnMainSync { ThorAccessibilityService.instance?.actionOverlay?.clear(); AgentActionFeedback.setEnabled(context, beforeFeedback) }
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, beforeServices)
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, beforeEnabled)
            } finally { automation.dropShellPermissionIdentity() }
            debug.close(); display.release(); image.close(); temporary.deleteRecursively()
        }
    }
}
